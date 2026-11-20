package com.useinoski.gallerycleanup.scan

import android.util.Log
import com.useinoski.gallerycleanup.data.RoomScanResultStore
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Classifies one MediaStore page and writes the review rows.
 *
 * The scanner can deliver the same page again. A crash after this returns
 * and before the checkpoint is saved is the case the scan contract describes.
 * [RoomScanResultStore.upsert] replaces a pending row with the same
 * [MediaKey], so the second delivery does not insert a second row. A row the
 * user has marked kept is skipped and is not rewritten.
 *
 * Videos and audio are not decoded. Videos go to [ScanCategory.Video] and
 * audio goes to [ScanCategory.Audio], which are the manual-review buckets
 * already on the review row. Photos go through [classifyImage].
 *
 * A failure on one item becomes [ScanCategory.Unflagged] with model version
 * [FlagPolicy.NONE]. It does not fail the batch. The scanner treats an
 * exception from this method as a failed scan, so the exception stays here.
 * [CancellationException] is rethrown. Cancelling the worker must stop the
 * page, not record the cancelled item as unflagged.
 *
 * At most [parallelism] images are decoded at once. The detectors hop to
 * their own dispatcher inside `classify`, so a dispatcher view would not
 * hold the bound across that hop. The [Semaphore] does. The permit is held
 * for the whole item, including the suspend inside the model.
 */
internal class DetectionBatchObserver(
    private val results: RoomScanResultStore,
    private val classifyImage: suspend (MediaItem) -> ImageSignals,
    private val parallelism: Int = PARALLELISM,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScanBatchObserver {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val slots = Semaphore(parallelism)

    override suspend fun onBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val latest = LinkedHashMap<MediaKey, MediaItem>(items.size)
        for (item in items) {
            latest[MediaKey(item.type, item.id)] = item
        }
        val kept = results.keptKeys()
        val pending = ArrayList<MediaItem>(latest.size)
        for ((key, item) in latest) {
            if (key !in kept) pending += item
        }
        if (pending.isEmpty()) return
        val written = coroutineScope {
            pending.map { item ->
                async {
                    slots.withPermit {
                        try {
                            toResult(item)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.w(TAG, "item ${item.type} ${item.id} was not classified", failure)
                            unflagged(item)
                        }
                    }
                }
            }.awaitAll()
        }
        results.upsert(written)
    }

    private suspend fun toResult(item: MediaItem): ScanResult {
        val decision = when (item.type) {
            MediaType.Video -> FlagDecision(ScanCategory.Video, RULE_CONFIDENCE, FlagPolicy.VIDEO_RULE)
            MediaType.Audio -> FlagDecision(ScanCategory.Audio, RULE_CONFIDENCE, FlagPolicy.AUDIO_RULE)
            MediaType.Image -> FlagPolicy.decide(classifyImage(item))
        }
        return row(item, decision)
    }

    private fun unflagged(item: MediaItem): ScanResult {
        return row(item, FlagDecision(ScanCategory.Unflagged, 0f, FlagPolicy.NONE))
    }

    private fun row(item: MediaItem, decision: FlagDecision): ScanResult {
        return ScanResult(
            mediaStoreId = item.id,
            contentUri = item.uri,
            mediaType = item.type,
            category = decision.category,
            confidence = decision.confidence,
            modelVersion = decision.modelVersion,
            scannedAtEpochMillis = clock(),
        )
    }

    private companion object {
        const val TAG = "GalleryScan"
        const val PARALLELISM = 2
        const val RULE_CONFIDENCE = 1f
    }
}
