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
 * The detectors are created once and reused. Loading the LiteRT model on
 * every batch would dominate the scan. Each detector locks itself, so two
 * images may call in. They do not run on the main thread. Their own
 * `classify` checks that.
 *
 * One detector throwing does not drop the other scores. A face failure can
 * still leave an NSFW hit, and the other way around. Decode failures
 * propagate. The observer turns those into an unflagged row so the scan
 * continues.
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

    override fun close() {
        nsfw.close()
        faces.close()
        labels.close()
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
        } catch (failure: Exception) {
            Log.w(TAG, "detector $name failed for ${item.type} ${item.id}", failure)
            null
        }
    }

    private companion object {
        const val TAG = "GalleryScan"
    }
}
