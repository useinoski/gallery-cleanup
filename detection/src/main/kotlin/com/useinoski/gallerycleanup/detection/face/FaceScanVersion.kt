package com.useinoski.gallerycleanup.detection.face

import com.useinoski.gallerycleanup.detection.toVersionToken

/**
 * Face detection reports a count, not a probability and not an identity.
 * The only score key is [FACE_COUNT].
 */
object FaceScanVersion {
    const val ID = "mlkit-face-count"
    const val ARTIFACT = "16.1.7"
    const val FACE_COUNT = "face_count"

    /** Long side passed to ML Kit. Higher keeps small faces; 480 is ML Kit's speed hint, not our recall target. */
    const val DEFAULT_MAX_LONG_SIDE_PX = 1280

    /** ML Kit's own default: the face must cover this fraction of the image width. Not a review threshold. */
    const val DEFAULT_MIN_FACE_SIZE = 0.15f

    fun of(maxLongSidePx: Int, minFaceSize: Float): String =
        "mlkit-face-detection-$ARTIFACT-bundled-fast-count" +
            "-long$maxLongSidePx-min${minFaceSize.toVersionToken()}"
}
