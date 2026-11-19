package com.useinoski.gallerycleanup.detection

import java.util.Locale

/**
 * Turns a [DetectionResult] into a yes/no without baking the cutoff into the
 * model. There is no default threshold on purpose: the number will be tuned
 * on a private set later, and a stored score can be compared with a new
 * number without running the detector again.
 *
 * A score equal to the threshold is a flag (`>=`).
 */
object DetectionScoring {
    fun exceeds(score: Float, threshold: Float): Boolean {
        require(score.isFinite()) { "score must be finite" }
        require(threshold.isFinite()) { "threshold must be finite" }
        return score >= threshold
    }

    /**
     * Missing categories count as 0. That is what "the detector did not see
     * this" means, including the optional meme hint when it was not requested.
     */
    fun categoryExceeds(result: DetectionResult, category: String, threshold: Float): Boolean {
        val score = result.scores[category] ?: 0f
        return exceeds(score, threshold)
    }

    /**
     * Identity stored next to a review. The threshold is not part of it.
     * Scores stay in the result, so the same identity can be re-checked with
     * a different threshold. A new model changes [DetectionResult.modelVersion],
     * which changes this string, so an old score is never mistaken for a new one.
     */
    fun reviewIdentity(result: DetectionResult): String {
        require(result.detectorId.isNotBlank()) { "detectorId is blank" }
        require(result.modelVersion.isNotBlank()) { "modelVersion is blank" }
        return result.detectorId + "|" + result.modelVersion
    }
}

/** Stable, locale-independent token so a version string does not change with the phone's language. */
internal fun Float.toVersionToken(): String {
    require(isFinite()) { "version number must be finite" }
    return String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')
}
