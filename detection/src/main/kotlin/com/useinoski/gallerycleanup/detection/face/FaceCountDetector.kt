package com.useinoski.gallerycleanup.detection.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.MediaDetector
import com.useinoski.gallerycleanup.detection.image.capLongSide
import com.useinoski.gallerycleanup.detection.image.requireUsableBitmap
import com.useinoski.gallerycleanup.detection.mlkit.awaitResult
import com.useinoski.gallerycleanup.detection.nsfw.checkOffMainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bundled ML Kit face detection. The result is a face count and nothing else.
 *
 * Landmarks, contours, smile classification, and tracking ids are turned off
 * and are not read. A tracking id is not a name, but this detector does not
 * need it, so it is not requested. Photos with a count at or above the caller's
 * threshold (usually 1) are the ones to route to manual review.
 *
 * [minFaceSize] is ML Kit's smallest-face filter, as a fraction of the image
 * width. It is not the review threshold. [maxLongSidePx] caps the bitmap so a
 * 12 megapixel photo is not copied whole into ML Kit. Aspect ratio is kept,
 * because squashing a face makes it harder to find.
 */
class FaceCountDetector(
    context: Context,
    private val maxLongSidePx: Int = FaceScanVersion.DEFAULT_MAX_LONG_SIDE_PX,
    private val minFaceSize: Float = FaceScanVersion.DEFAULT_MIN_FACE_SIZE,
) : MediaDetector, AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var client: FaceDetector? = null
    private var closed = false

    init {
        require(maxLongSidePx > 0) { "maxLongSidePx must be positive" }
        require(minFaceSize > 0f && minFaceSize <= 1f) { "minFaceSize must be in (0, 1]" }
    }

    override val id: String = FaceScanVersion.ID
    override val modelVersion: String = FaceScanVersion.of(maxLongSidePx, minFaceSize)

    /** Face detection does not want the caller to squash the photo into a square. */
    override val inputSizePx: Int = 0

    override suspend fun classify(input: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        checkOffMainThread()
        requireUsableBitmap(input)
        val scaled = capLongSide(input, maxLongSidePx)
        try {
            val image = InputImage.fromBitmap(scaled.bitmap, 0)
            val faces = synchronized(lock) {
                check(!closed) { "detector is closed" }
                client().process(image)
            }.awaitResult()
            // Size only. Do not read getTrackingId, landmarks, or contours.
            val count = (faces ?: emptyList()).size.toFloat()
            DetectionResult(
                scores = mapOf(FaceScanVersion.FACE_COUNT to count),
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

    private fun client(): FaceDetector {
        client?.let { return it }
        MlKit.initialize(appContext)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(minFaceSize)
            .build()
        // getClient() with no options would enable defaults that include more work.
        // The bundled artifact supplies the model. We do not call a download API.
        return FaceDetection.getClient(options).also { created ->
            client = created
        }
    }
}
