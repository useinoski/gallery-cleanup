package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.media.SCAN_ACCESS_REVOKED_DETAIL
import com.useinoski.gallerycleanup.media.SCAN_BATCH_SIZE
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanStore
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Classifies rows [AnalysisRecords.keysNeedingRescan] still wants, once.
 *
 * A pass whose MediaStore generation did not move never opens those files.
 * This runs after every scan that reached [ScanPhase.Completed], including
 * that skipped walk. It asks for keys once and does not ask again, so a
 * file that fails here is not classified a second time in the same run.
 *
 * Keys with no present `media_item` row are skipped. Removed rows are
 * already absent from the key list. [resolvePresent] is what drops both.
 */
internal class PendingRescan(
    private val records: AnalysisRecords,
    private val runs: ScanStore,
    private val currentModelVersions: Set<String>,
    private val resolvePresent: suspend (List<MediaKey>) -> List<MediaItem>,
    private val observer: ScanBatchObserver,
    private val batchSize: Int = SCAN_BATCH_SIZE,
) {
    init {
        require(currentModelVersions.isNotEmpty()) { "Pass at least one current model version." }
        require(batchSize > 0) { "batchSize must be positive" }
    }

    suspend fun afterPass() {
        if (runs.readRun().phase != ScanPhase.Completed) return
        val keys = records.keysNeedingRescan(currentModelVersions)
        if (keys.isEmpty()) return
        val present = resolvePresent(keys).associateBy { item -> MediaKey(item.type, item.id) }
        val items = ArrayList<MediaItem>(keys.size)
        val seen = HashSet<MediaKey>(keys.size)
        for (key in keys) {
            if (!seen.add(key)) continue
            present[key]?.let { item -> items += item }
        }
        if (items.isEmpty()) return
        try {
            for (batch in items.chunked(batchSize)) {
                coroutineContext.ensureActive()
                observer.onBatch(batch)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val current = runs.readRun()
                runs.saveRun(current.copy(phase = ScanPhase.Cancelled, detail = null))
            }
            throw cancelled
        } catch (revoked: SecurityException) {
            val current = runs.readRun()
            runs.saveRun(
                current.copy(
                    phase = ScanPhase.AccessRevoked,
                    detail = SCAN_ACCESS_REVOKED_DETAIL,
                ),
            )
        }
    }
}
