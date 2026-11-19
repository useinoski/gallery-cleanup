package com.useinoski.gallerycleanup.detection.label

import com.useinoski.gallerycleanup.detection.toVersionToken
import kotlin.math.max

/**
 * One label returned by ML Kit, before it is mapped onto a review category.
 * [text] is matched exactly against the English base-model names.
 */
internal data class LabelHit(
    val text: String,
    val confidence: Float,
    val index: Int? = null,
)

/**
 * v1 of the experimental label map.
 *
 * Names and indexes were checked against Google's published base-model list
 * (https://developers.google.com/ml-kit/vision/image-labeling/label-map)
 * on 2026-11-19. That list has Alcohol, Wine, Bar, Nightclub, Casino, Money,
 * Screenshot, Comics, and Poster. It has no smoking or drug label, so those
 * categories are not inferred from a nearby word.
 *
 * Money is its own score. It is not added into [GAMBLING]. A caller that wants
 * a weak money signal passes a weight to [gamblingScore] and a threshold to
 * [com.useinoski.gallerycleanup.detection.DetectionScoring]. Neither number is
 * hard-coded here.
 *
 * Category scores are the max confidence of the labels in that group, or 0
 * when none of them were returned. Max stays inside 0..1. A sum would not be
 * a probability.
 */
object LabelCategories {
    const val ALCOHOL = "alcohol"
    const val GAMBLING = "gambling"
    const val MONEY = "money"
    const val MEME = "meme"

    val alcoholLabels: Set<String> = setOf("Alcohol", "Wine", "Bar", "Nightclub")
    val gamblingLabels: Set<String> = setOf("Casino")
    val moneyLabels: Set<String> = setOf("Money")
    val memeLabels: Set<String> = setOf("Screenshot", "Comics", "Poster")

    /** Not in the base model. Present so a test can lock that we do not map them. */
    val outOfScopeLabels: Set<String> = setOf(
        "Smoking",
        "Cigarette",
        "Tobacco",
        "Drug",
        "Drugs",
    )

    internal fun scores(labels: List<LabelHit>, includeMemeHint: Boolean): Map<String, Float> {
        val mapped = linkedMapOf(
            ALCOHOL to maxConfidence(labels, alcoholLabels),
            GAMBLING to maxConfidence(labels, gamblingLabels),
            MONEY to maxConfidence(labels, moneyLabels),
        )
        if (includeMemeHint) {
            mapped[MEME] = maxConfidence(labels, memeLabels)
        }
        return mapped
    }

    /**
     * Casino, unless [moneyWeight] times the Money score is higher.
     * [moneyWeight] of 0 ignores Money. There is no default: the caller chooses it.
     */
    fun gamblingScore(scores: Map<String, Float>, moneyWeight: Float): Float {
        require(moneyWeight.isFinite() && moneyWeight >= 0f) { "moneyWeight must be >= 0" }
        val casino = scores[GAMBLING] ?: 0f
        val money = scores[MONEY] ?: 0f
        require(casino.isFinite() && money.isFinite()) { "gambling scores must be finite" }
        return max(casino, money * moneyWeight)
    }

    private fun maxConfidence(labels: List<LabelHit>, names: Set<String>): Float {
        var best = 0f
        for (label in labels) {
            if (label.text in names && label.confidence > best) {
                require(label.confidence.isFinite()) { "label confidence must be finite" }
                best = label.confidence
            }
        }
        return best
    }
}

internal object LabelScanVersion {
    const val ID = "mlkit-image-labels-v1"
    const val ARTIFACT = "17.0.9"
    const val DEFAULT_MAX_LONG_SIDE_PX = 640
    const val DEFAULT_CONFIDENCE_FLOOR = 0f

    fun of(maxLongSidePx: Int, confidenceFloor: Float, includeMemeHint: Boolean): String {
        val meme = if (includeMemeHint) "1" else "0"
        return "mlkit-image-labeling-$ARTIFACT-bundled-categories-v1" +
            "-long$maxLongSidePx-floor${confidenceFloor.toVersionToken()}-meme$meme"
    }
}
