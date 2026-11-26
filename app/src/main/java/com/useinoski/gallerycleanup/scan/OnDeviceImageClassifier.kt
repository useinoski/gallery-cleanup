package com.useinoski.gallerycleanup.scan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.face.FaceCountDetector
import com.useinoski.gallerycleanup.detection.label.ImageLabelCategoryDetector
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import com.useinoski.gallerycleanup.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the three on-device detectors on one upright, downscaled bitmap.
 *
 * The detectors are created once for the scan and reused across its batches.
 * Loading the LiteRT model on every batch would dominate the scan. Each
 * detector locks itself, so two
 * images may call in. They do not run on the main thread. Their own
 * `classify` checks that.
 *
 * One detector throwing does not drop the other scores. A face failure can
 * still leave an NSFW hit, and the other way around. Decode failures
 * propagate. The observer records those with [AnalysisRecords.recordFailures]
 * so the scan continues and the file is not stored as a clean photo.
 *
 * [SecurityException] also propagates. That is access disappearing
 * mid-decode, not a bad file. [close] releases every detector. The scan
 * calls it when the pass ends, fails, or is cancelled. The next pass builds
 * a new classifier. PR #8 frees the Marqo interpreter only from [close].
 */
internal class OnDeviceImageClassifier(
    context: Context,
    private val maxLongSidePx: Int = BitmapDecode.MAX_LONG_SIDE_PX,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val decoder = ContentUriBitmapDecoder(appContext.contentResolver, maxLongSidePx)
    private val nsfw = MarqoNsfwDetector(appContext)
    private val faces = FaceCountDetector(
        appContext,
        maxLongSidePx = maxLongSidePx,
    )
    private val labels = ImageLabelCategoryDetector(
        appContext,
        maxLongSidePx = maxLongSidePx,
        includeMemeHint = true,
    )

    suspend fun classify(item: MediaItem): ImageSignals {
        // ImageDecoder blocks. IO keeps that off the worker's compute dispatcher.
        // The observer's semaphore is held across this call, so two images at most.
        val bitmap = withContext(Dispatchers.IO) { decoder.decode(item.uri) }
        try {
            return ImageSignals(
                nsfw = runDetector("nsfw", item, bitmap) { nsfw.classify(bitmap) },
                faces = runDetector("faces", item, bitmap) { faces.classify(bitmap) },
                labels = runDetector("labels", item, bitmap) { labels.classify(bitmap) },
                screenshotPath = isScreenshotFolder(item.path),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Versions this classifier writes, plus the rule names and [FlagPolicy.NONE]. */
    fun currentModelVersions(): Set<String> = pipelineModelVersions(listOf(nsfw, faces, labels))

    override fun close() {
        // One close() throwing must not skip the others. The Marqo thread
        // is released only from close.
        try {
            nsfw.close()
        } finally {
            try {
                faces.close()
            } finally {
                labels.close()
            }
        }
    }

    private suspend fun runDetector(
        name: String,
        item: MediaItem,
        bitmap: Bitmap,
        block: suspend () -> DetectionResult,
    ): DetectionResult? {
        if (bitmap.isRecycled) return null
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: SecurityException) {
            throw revoked
        } catch (failure: Exception) {
            Log.w(TAG, "detector $name failed for ${item.type} ${item.id}", failure)
            null
        }
    }

    private companion object {
        const val TAG = "GalleryScan"
    }
}
