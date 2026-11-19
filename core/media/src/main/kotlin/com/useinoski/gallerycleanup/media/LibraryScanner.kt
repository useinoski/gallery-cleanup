package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.ScanStore
import com.useinoski.gallerycleanup.model.TypeProgress
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Rows asked from MediaStore per round trip. Tests pass a smaller number. */
const val SCAN_BATCH_SIZE = 40

internal const val SCAN_ACCESS_REVOKED_DETAIL = "Access changed while the scan was running."
internal const val SCAN_FAILED_DETAIL = "The scan failed before it finished."

/**
 * Walks the granted collections, saves each batch, and can be started again
 * after a cancel or a process death.
 *
 * The checkpoint lives in [ScanStore], not in this object. Dropping the
 * scanner and constructing another one with the same store is the resume.
 * [InMemoryScanStore][com.useinoski.gallerycleanup.model.InMemoryScanStore]
 * only survives inside one process. A Room [ScanStore] is what makes the
 * same resume survive a reboot. This class does not know which one it has.
 *
 * Nothing here deletes a file or opens a socket. [observer] is the only
 * extension point, and the production binding is [ScanBatchObserver.NONE].
 *
 * Access is re-read before every batch. A grant that disappears stops the
 * pass, leaves the checkpoint, and records [ScanPhase.AccessRevoked].
 * The worker treats that as a finished job so WorkManager does not retry
 * a scan the user can no longer authorize.
 */
class LibraryScanner(
    private val repository: MediaRepository,
    private val store: ScanStore,
    private val readable: suspend () -> ReadableMedia,
    private val stamps: VolumeStamps,
    private val observer: ScanBatchObserver = ScanBatchObserver.NONE,
    private val batchSize: Int = SCAN_BATCH_SIZE,
) {
    private var lastBoard: Map<MediaType, TypeProgress> = emptyMap()

    suspend fun scan() {
        val prior = store.readRun()
        try {
            val stamp = stamps.read()
            val checkpoint = checkpointIfSameVersion(stamp)
            val access = readable()
            if (!access.any()) {
                save(prior, emptyMap(), ScanPhase.AccessRevoked, SCAN_ACCESS_REVOKED_DETAIL)
                return
            }
            val bounds = passBounds(stamp, prior, checkpoint)
            if (bounds == null) {
                store.saveRun(
                    ScanRun(
                        phase = ScanPhase.Completed,
                        byType = unchangedTotals(access),
                        mediaStoreVersion = stamp.version,
                        completedVolumeGeneration = stamp.storedGeneration(),
                        detail = null,
                    ),
                )
                return
            }
            val granted = MediaType.entries.filter { type -> access.allows(type) }
            val board = linkedMapOf<MediaType, TypeProgress>()
            granted.forEach { type -> board[type] = TypeProgress(type, scanned = 0, total = 0) }
            save(prior, board, ScanPhase.Running, null)
            if (!walk(granted, bounds, checkpoint, prior, board)) return
            if (!reconcile(granted, prior, board)) return
            finish(stamp, bounds, board)
        } catch (cancelled: CancellationException) {
            // A cancelled coroutine cannot suspend, and saving the phase is a
            // suspend call. NonCancellable lets that write finish. The
            // checkpoint from the last completed batch is already stored.
            withContext(NonCancellable) {
                save(prior, lastBoard, ScanPhase.Cancelled, null)
            }
            throw cancelled
        } catch (revoked: SecurityException) {
            save(prior, lastBoard, ScanPhase.AccessRevoked, SCAN_ACCESS_REVOKED_DETAIL)
        } catch (error: Exception) {
            save(prior, lastBoard, ScanPhase.Failed, error.message ?: SCAN_FAILED_DETAIL)
            throw error
        }
    }

    private suspend fun checkpointIfSameVersion(stamp: VolumeStamp): ScanCheckpoint? {
        val checkpoint = store.readCheckpoint() ?: return null
        if (checkpoint.mediaStoreVersion == stamp.version) return checkpoint
        store.clearCheckpoint()
        return null
    }

    /**
     * Null means the volume has not changed since the last completed pass,
     * so there is nothing to page.
     */
    private fun passBounds(
        stamp: VolumeStamp,
        prior: ScanRun,
        checkpoint: ScanCheckpoint?,
    ): PassBounds? {
        if (checkpoint != null) {
            return PassBounds(
                generationModifiedAfter = checkpoint.generationModifiedAfter,
                generationModifiedAtMost = checkpoint.generationModifiedAtMost,
                version = checkpoint.mediaStoreVersion,
            )
        }
        val covered = prior.completedVolumeGeneration
        val sameLibrary = prior.mediaStoreVersion != null && prior.mediaStoreVersion == stamp.version
        if (sameLibrary && covered != null && covered == stamp.storedGeneration()) {
            return null
        }
        val incremental = sameLibrary && covered != null
        return PassBounds(
            generationModifiedAfter = if (incremental) covered else null,
            generationModifiedAtMost = stamp.storedGeneration(),
            version = stamp.version,
        )
    }

    private suspend fun unchangedTotals(access: ReadableMedia): List<TypeProgress> {
        val counts = repository.count(access)
        return buildList {
            if (access.images) {
                val count = counts.images ?: 0
                add(TypeProgress(MediaType.Image, count, count))
            }
            if (access.videos) {
                val count = counts.videos ?: 0
                add(TypeProgress(MediaType.Video, count, count))
            }
            if (access.audio) {
                val count = (counts.music ?: 0) + (counts.otherAudio ?: 0)
                add(TypeProgress(MediaType.Audio, count, count))
            }
        }
    }

    private suspend fun walk(
        granted: List<MediaType>,
        bounds: PassBounds,
        checkpoint: ScanCheckpoint?,
        prior: ScanRun,
        board: MutableMap<MediaType, TypeProgress>,
    ): Boolean {
        val startIndex = checkpoint
            ?.let { saved -> granted.indexOf(saved.type) }
            ?.takeIf { index -> index >= 0 }
            ?: 0
        for ((index, type) in granted.withIndex()) {
            coroutineContext.ensureActive()
            if (!guard(granted, prior, board)) return false
            if (index < startIndex) {
                val total = repository.countScan(bounds.count(type))
                board[type] = TypeProgress(type, scanned = total, total = total)
                save(prior, board, ScanPhase.Running, null)
                continue
            }
            val resume = checkpoint?.takeIf { saved -> saved.type == type }
            if (!pageType(type, bounds, resume, granted, prior, board)) return false
        }
        return true
    }

    private suspend fun pageType(
        type: MediaType,
        bounds: PassBounds,
        resume: ScanCheckpoint?,
        granted: List<MediaType>,
        prior: ScanRun,
        board: MutableMap<MediaType, TypeProgress>,
    ): Boolean {
        val total = repository.countScan(bounds.count(type))
        var scanned = resume?.scanned ?: 0
        var cursor = resume?.let { saved -> ScanCursor(dateModified = saved.dateModified, id = saved.id) }
        board[type] = TypeProgress(type, scanned = scanned, total = total)
        save(prior, board, ScanPhase.Running, null)
        while (true) {
            coroutineContext.ensureActive()
            if (!guard(granted, prior, board)) return false
            val page = repository.scanPage(
                ScanPageRequest(
                    type = type,
                    limit = batchSize,
                    after = cursor,
                    generationModifiedAfter = bounds.generationModifiedAfter,
                    generationModifiedAtMost = bounds.generationModifiedAtMost,
                ),
            )
            if (page.rows.isEmpty()) return true
            observer.onBatch(page.rows.map { row -> row.item })
            store.upsertBatch(page.rows.map { row -> row.item })
            val last = page.rows.last()
            scanned += page.rows.size
            cursor = ScanCursor(dateModified = last.dateModified, id = last.item.id)
            store.saveCheckpoint(
                ScanCheckpoint(
                    type = type,
                    dateModified = last.dateModified,
                    id = last.item.id,
                    scanned = scanned,
                    generationModifiedAfter = bounds.generationModifiedAfter,
                    generationModifiedAtMost = bounds.generationModifiedAtMost,
                    mediaStoreVersion = bounds.version,
                ),
            )
            board[type] = TypeProgress(type, scanned = scanned, total = total)
            save(prior, board, ScanPhase.Running, null)
            if (!page.hasMore) return true
        }
    }

    private suspend fun reconcile(
        granted: List<MediaType>,
        prior: ScanRun,
        board: Map<MediaType, TypeProgress>,
    ): Boolean {
        for (type in granted) {
            coroutineContext.ensureActive()
            if (!guard(granted, prior, board)) return false
            val live = HashSet<Long>()
            var afterId: Long? = null
            while (true) {
                val page = repository.pageIds(IdPageRequest(type = type, afterId = afterId, limit = batchSize))
                if (page.ids.isEmpty()) break
                live += page.ids
                if (!page.hasMore) break
                afterId = page.ids.last()
            }
            val missing = store.presentKeys(type).filter { key -> key.id !in live }
            if (missing.isNotEmpty()) store.markRemoved(missing)
        }
        return true
    }

    private suspend fun finish(
        stamp: VolumeStamp,
        bounds: PassBounds,
        board: Map<MediaType, TypeProgress>,
    ) {
        val end = stamps.read()
        store.clearCheckpoint()
        if (end.version == stamp.version) {
            store.saveRun(
                ScanRun(
                    phase = ScanPhase.Completed,
                    byType = snapshot(board),
                    mediaStoreVersion = stamp.version,
                    completedVolumeGeneration = bounds.generationModifiedAtMost,
                    detail = null,
                ),
            )
        } else {
            // The version moved while we were paging. Generation numbers may
            // have been reset. Leave both fields empty so the next start is
            // a full walk instead of an incremental one against a stale counter.
            store.saveRun(
                ScanRun(
                    phase = ScanPhase.Completed,
                    byType = snapshot(board),
                    mediaStoreVersion = null,
                    completedVolumeGeneration = null,
                    detail = null,
                ),
            )
        }
    }

    private suspend fun guard(
        expected: List<MediaType>,
        prior: ScanRun,
        board: Map<MediaType, TypeProgress>,
    ): Boolean {
        val now = readable()
        if (expected.all { type -> now.allows(type) }) return true
        save(prior, board, ScanPhase.AccessRevoked, SCAN_ACCESS_REVOKED_DETAIL)
        return false
    }

    private suspend fun save(
        prior: ScanRun,
        board: Map<MediaType, TypeProgress>,
        phase: ScanPhase,
        detail: String?,
    ) {
        lastBoard = board
        store.saveRun(
            ScanRun(
                phase = phase,
                byType = snapshot(board),
                mediaStoreVersion = prior.mediaStoreVersion,
                completedVolumeGeneration = prior.completedVolumeGeneration,
                detail = detail,
            ),
        )
    }

    private fun snapshot(board: Map<MediaType, TypeProgress>): List<TypeProgress> {
        return MediaType.entries.mapNotNull { type -> board[type] }
    }
}

private data class PassBounds(
    val generationModifiedAfter: Long?,
    val generationModifiedAtMost: Long,
    val version: String,
) {
    fun count(type: MediaType) = ScanCountRequest(
        type = type,
        generationModifiedAfter = generationModifiedAfter,
        generationModifiedAtMost = generationModifiedAtMost,
    )
}
