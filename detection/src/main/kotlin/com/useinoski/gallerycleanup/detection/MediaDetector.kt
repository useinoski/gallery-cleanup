package com.useinoski.gallerycleanup.detection

import android.graphics.Bitmap

/**
 * One on-device classifier.
 *
 * [inputSizePx] is the square a fixed-input model expects. `0` means this
 * detector scales the bitmap itself (face count and image labels do that,
 * and they keep the photo's aspect ratio). Callers can still pass the full
 * upright bitmap either way: every implementation scales internally.
 *
 * [classify] is a suspend function so the work can leave the main thread
 * without the caller managing its own executor. The bitmap must already be
 * upright. This interface has no rotation argument, so EXIF orientation is
 * the caller's job.
 *
 * Do not add a Play Services ML Kit artifact (`com.google.android.gms:play-services-mlkit-*`).
 * Those variants download models. The module README names the bundled artifacts.
 */
interface MediaDetector {
    val id: String
    val modelVersion: String
    val inputSizePx: Int

    suspend fun classify(input: Bitmap): DetectionResult
}

/**
 * Scores from one detector run. Keys are that detector's own labels
 * (category names, or `face_count`).
 *
 * [detectorId] and [modelVersion] travel with the scores so a stored row can
 * still be explained after the implementation is swapped. The version
 * identifies the model and the preprocessing, not the decision threshold.
 * [DetectionScoring] applies a threshold later, to these stored numbers, so
 * a threshold change does not require running the model again and does not
 * by itself invent a new version.
 */
data class DetectionResult(
    val scores: Map<String, Float>,
    val detectorId: String,
    val modelVersion: String,
)
