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
 *
 * This is not its own WorkManager job. It runs inside the same unique scan
 * that just finished the walk, on that worker's coroutine. Cancel stops it
 * with the walk. The stored phase is put back to [ScanPhase.Running] for
 * the duration, because the walk has already saved [ScanPhase.Completed]
 * and the screen would otherwise hide Cancel while this pass is still
 * classifying. [ensureActive] runs at least once per batch, so a cancel
 * finishes the batch in hand and does not start the next one. The run is
 * then saved as [ScanPhase.Cancelled].
 *
 * Nothing here starts itself. Rows the upgrade marked failed are classified
 * only when a scan the user started (or WorkManager resumed) reaches this
 * pass.
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
        try {
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
            coroutineContext.ensureActive()
            val open = runs.readRun()
            runs.saveRun(open.copy(phase = ScanPhase.Running, detail = null))
            for (batch in items.chunked(batchSize)) {
                // Once per batch, before the batch starts. The batch in hand
                // may finish; the next one must not start after Cancel.
                coroutineContext.ensureActive()
                observer.onBatch(batch)
            }
            val done = runs.readRun()
            if (done.phase == ScanPhase.Running) {
                runs.saveRun(done.copy(phase = ScanPhase.Completed, detail = null))
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
        } catch (error: Exception) {
            // The walk already stored Completed. Record Failed before this
            // exception reaches the worker, which reports failure.
            val current = runs.readRun()
            runs.saveRun(
                current.copy(
                    phase = ScanPhase.Failed,
                    detail = error.message ?: "The scan failed before it finished.",
                ),
            )
            throw error
        }
    }
}
