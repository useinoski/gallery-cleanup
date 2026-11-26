package com.useinoski.gallerycleanup.scan

import android.util.Log
import com.useinoski.gallerycleanup.data.AnalysisFailure
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Classifies one MediaStore page and writes the review rows.
 *
 * The scanner can deliver the same page again. A crash after this returns
 * and before the checkpoint is saved is the case the scan contract describes.
 * [AnalysisRecords.upsert] replaces a pending row with the same [MediaKey],
 * so the second delivery does not insert a second row. A row the user has
 * marked kept is skipped and is not rewritten.
 *
 * Videos and audio are not decoded. Videos go to [ScanCategory.Video] and
 * audio goes to [ScanCategory.Audio], which are the manual-review buckets
 * already on the review row. Photos go through [classifyImage]. A photo that
 * scores as clean is still [ScanCategory.Unflagged] with [FlagPolicy.NONE],
 * written by [AnalysisRecords.upsert].
 *
 * A failure on one item is [AnalysisRecords.recordFailures], not a clean
 * photo. It does not fail the batch. [CancellationException] is rethrown.
 * Cancelling the worker must stop the page, not record the cancelled item
 * as a failure. [SecurityException] stops the page the same way and writes
 * nothing for it: the scanner records access revoked. A sibling that already
 * finished in this page is discarded with the rest. Partial photo access is
 * not a [SecurityException]; MediaStore just returns the smaller library.
 *
 * At most [parallelism] images are decoded at once. The detectors hop to
 * their own dispatcher inside `classify`, so a dispatcher view would not
 * hold the bound across that hop. The [Semaphore] does. The permit is held
 * for the whole item, including the suspend inside the model.
 */
internal class DetectionBatchObserver(
    private val results: AnalysisRecords,
    private val parallelism: Int = PARALLELISM,
    private val clock: () -> Long = System::currentTimeMillis,
    private val classifyImage: suspend (MediaItem) -> ImageSignals,
) : ScanBatchObserver {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val slots = Semaphore(parallelism)

    override suspend fun onBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        coroutineContext.ensureActive()
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
        val revoked = AtomicReference<SecurityException?>(null)
        val outcomes = ArrayList<ItemOutcome>(pending.size)
        val sink = Mutex()
        supervisorScope {
            pending.map { item ->
                async {
                    // A cancel during this batch must not start another item.
                    coroutineContext.ensureActive()
                    slots.withPermit {
                        coroutineContext.ensureActive()
                        if (revoked.get() != null) return@withPermit
                        try {
                            val scored = ItemOutcome.Scored(toResult(item))
                            sink.withLock {
                                if (revoked.get() == null) outcomes += scored
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: SecurityException) {
                            revoked.compareAndSet(null, failure)
                        } catch (failure: Exception) {
                            Log.w(TAG, "item ${item.type} ${item.id} was not classified", failure)
                            val failed = ItemOutcome.Failed(
                                AnalysisFailure(
                                    key = MediaKey(item.type, item.id),
                                    contentUri = item.uri,
                                    scannedAtEpochMillis = clock(),
                                ),
                            )
                            sink.withLock {
                                if (revoked.get() == null) outcomes += failed
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        revoked.get()?.let { failure -> throw failure }
        val scored = ArrayList<ScanResult>(outcomes.size)
        val failed = ArrayList<AnalysisFailure>(outcomes.size)
        for (outcome in outcomes) {
            when (outcome) {
                is ItemOutcome.Scored -> scored += outcome.result
                is ItemOutcome.Failed -> failed += outcome.failure
            }
        }
        if (scored.isNotEmpty()) results.upsert(scored)
        if (failed.isNotEmpty()) results.recordFailures(failed)
    }

    private suspend fun toResult(item: MediaItem): ScanResult {
        val decision = when (item.type) {
            MediaType.Video -> FlagDecision(ScanCategory.Video, RULE_CONFIDENCE, FlagPolicy.VIDEO_RULE)
            MediaType.Audio -> FlagDecision(ScanCategory.Audio, RULE_CONFIDENCE, FlagPolicy.AUDIO_RULE)
            MediaType.Image -> FlagPolicy.decide(classifyImage(item))
        }
        return row(item, decision)
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

    private sealed interface ItemOutcome {
        data class Scored(val result: ScanResult) : ItemOutcome
        data class Failed(val failure: AnalysisFailure) : ItemOutcome
    }

    private companion object {
        const val TAG = "GalleryScan"
        const val PARALLELISM = 2
        const val RULE_CONFIDENCE = 1f
    }
}
