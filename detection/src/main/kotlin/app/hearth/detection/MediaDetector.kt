package app.hearth.detection

import android.graphics.Bitmap

/**
 * One on-device classifier.
 *
 * Week 1 has the contract and no implementation. A later model must run on the
 * phone. Do not add a Play-services ML Kit artifact: those can phone home.
 * The module README names the bundled artifacts that are allowed.
 *
 * [inputSizePx] is the square the model expects. Callers scale the bitmap.
 * [classify] is a suspend function so the work can leave the main thread
 * without the caller managing its own executor.
 */
interface MediaDetector {
    val id: String
    val modelVersion: String
    val inputSizePx: Int

    suspend fun classify(input: Bitmap): DetectionResult
}

/**
 * Scores from one detector run. Keys are the detector's own labels.
 * [detectorId] and [modelVersion] are copied onto the result so a stored
 * score can still be explained after the implementation is swapped.
 */
data class DetectionResult(
    val scores: Map<String, Float>,
    val detectorId: String,
    val modelVersion: String,
)
