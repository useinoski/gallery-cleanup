package com.useinoski.gallerycleanup.detection.label

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.MediaDetector
import com.useinoski.gallerycleanup.detection.image.capLongSide
import com.useinoski.gallerycleanup.detection.image.requireUsableBitmap
import com.useinoski.gallerycleanup.detection.mlkit.awaitResult
import com.useinoski.gallerycleanup.detection.nsfw.checkOffMainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bundled ML Kit image labeling, mapped onto review categories.
 *
 * [confidenceFloor] is the floor handed to ML Kit before mapping. `0` keeps
 * every label ML Kit returns, so a later category threshold can still see a
 * weak Money score. It is not the decision threshold.
 *
 * [includeMemeHint] adds the [LabelCategories.MEME] score (Screenshot, Comics,
 * Poster). It is off unless a caller asks, and the model version records that
 * choice so a result with the hint is not treated as the same model as one without it.
 */
class ImageLabelCategoryDetector(
    context: Context,
    private val maxLongSidePx: Int = LabelScanVersion.DEFAULT_MAX_LONG_SIDE_PX,
    private val confidenceFloor: Float = LabelScanVersion.DEFAULT_CONFIDENCE_FLOOR,
    private val includeMemeHint: Boolean = false,
) : MediaDetector, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var client: ImageLabeler? = null
    private var closed = false

    init {
        require(maxLongSidePx > 0) { "maxLongSidePx must be positive" }
        require(confidenceFloor in 0f..1f) { "confidenceFloor must be in [0, 1]" }
    }

    override val id: String = LabelScanVersion.ID
    override val modelVersion: String =
        LabelScanVersion.of(maxLongSidePx, confidenceFloor, includeMemeHint)

    /** Labeling keeps aspect ratio. 0 tells callers not to pre-squash the photo. */
    override val inputSizePx: Int = 0

    override suspend fun classify(input: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        checkOffMainThread()
        requireUsableBitmap(input)
        val scaled = capLongSide(input, maxLongSidePx)
        try {
            val image = InputImage.fromBitmap(scaled.bitmap, 0)
            val labels = synchronized(lock) {
                check(!closed) { "detector is closed" }
                client().process(image)
            }.awaitResult()
            val hits = (labels ?: emptyList()).map { label ->
                LabelHit(
                    text = label.text,
                    confidence = label.confidence,
                    index = label.index,
                )
            }
            DetectionResult(
                scores = LabelCategories.scores(hits, includeMemeHint),
                detectorId = id,
                modelVersion = modelVersion,
            )
        } finally {
            scaled.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            client?.close()
            client = null
        }
    }

    private fun client(): ImageLabeler {
        client?.let { return it }
        MlKit.initialize(appContext)
        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(confidenceFloor)
            .build()
        return ImageLabeling.getClient(options).also { created ->
            client = created
        }
    }
}
