package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.DetectionScoring
import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.detection.label.LabelCategories
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import kotlin.math.max

/**
 * Scores from one photo, already computed. A null detector means that
 * detector threw or was not run. The others still count.
 *
 * [screenshotPath] is the folder rule, not a model. ML Kit's meme hint is
 * [labels] under [LabelCategories.MEME].
 */
internal data class ImageSignals(
    val nsfw: DetectionResult? = null,
    val faces: DetectionResult? = null,
    val labels: DetectionResult? = null,
    val screenshotPath: Boolean = false,
)

/**
 * The one category written for an item, plus the score and the model or
 * rule that produced it.
 *
 * [modelVersion] is [DetectionScoring.reviewIdentity] when a detector won,
 * a rule name when a rule won, or [FlagPolicy.NONE] when nothing matched.
 * The threshold is not part of the string. [DetectionScoring] owns that split.
 */
internal data class FlagDecision(
    val category: ScanCategory,
    val confidence: Float,
    val modelVersion: String,
)

/**
 * Picks one [ScanCategory] for a photo.
 *
 * Order is the one documented on [ScanCategory]: Sensitive, Immodest,
 * AlcoholGambling, People, Screenshot, then Unflagged. Video and Audio are
 * not decided here. Those files never become a bitmap. The observer files
 * them with [VIDEO_RULE] and [AUDIO_RULE].
 *
 * Thresholds are a first pass so a phone can flag something before anyone
 * tunes them on a private set. [DetectionScoring] compares the stored score
 * with the number. Changing a number here does not change [modelVersion].
 *
 * Sensitive is an NSFW probability at or above [SENSITIVE_NSFW]. Immodest is
 * the same score at or above [IMMODEST_NSFW] (the model is at least as sure
 * it is NSFW as SFW). Alcohol or Casino at or above [LABEL_THRESHOLD] is
 * AlcoholGambling. Money is ignored ([MONEY_WEIGHT] is 0) so a receipt is not
 * gambling. A face count at or above 1 is People, which is the manual-review
 * route the face detector describes. A meme label, then a `Screenshots`
 * folder, is Screenshot. People wins over Screenshot, so a photo of a person
 * is not filed as a screenshot.
 */
internal object FlagPolicy {
    const val SENSITIVE_NSFW = 0.85f
    const val IMMODEST_NSFW = 0.50f
    const val LABEL_THRESHOLD = 0.70f
    const val MONEY_WEIGHT = 0f

    const val VIDEO_RULE = "video-manual-review|v1"
    const val AUDIO_RULE = "audio-manual-review|v1"
    const val SCREENSHOT_PATH_RULE = "screenshot-path|v1"
    const val NONE = "none"

    fun decide(signals: ImageSignals): FlagDecision {
        val nsfw = signals.nsfw
        if (nsfw != null) {
            val score = nsfw.scores[MarqoNsfwDetector.LABEL_NSFW] ?: 0f
            if (DetectionScoring.exceeds(score, SENSITIVE_NSFW)) {
                return FlagDecision(ScanCategory.Sensitive, score, DetectionScoring.reviewIdentity(nsfw))
            }
            if (DetectionScoring.exceeds(score, IMMODEST_NSFW)) {
                return FlagDecision(ScanCategory.Immodest, score, DetectionScoring.reviewIdentity(nsfw))
            }
        }
        val labels = signals.labels
        if (labels != null) {
            val alcohol = labels.scores[LabelCategories.ALCOHOL] ?: 0f
            val gambling = LabelCategories.gamblingScore(labels.scores, MONEY_WEIGHT)
            if (DetectionScoring.exceeds(alcohol, LABEL_THRESHOLD) ||
                DetectionScoring.exceeds(gambling, LABEL_THRESHOLD)
            ) {
                return FlagDecision(
                    ScanCategory.AlcoholGambling,
                    max(alcohol, gambling),
                    DetectionScoring.reviewIdentity(labels),
                )
            }
        }
        val faces = signals.faces
        if (faces != null) {
            val count = faces.scores[FaceScanVersion.FACE_COUNT] ?: 0f
            if (DetectionScoring.exceeds(count, 1f)) {
                return FlagDecision(ScanCategory.People, count, DetectionScoring.reviewIdentity(faces))
            }
        }
        if (labels != null && DetectionScoring.categoryExceeds(labels, LabelCategories.MEME, LABEL_THRESHOLD)) {
            val score = labels.scores[LabelCategories.MEME] ?: 0f
            return FlagDecision(ScanCategory.Screenshot, score, DetectionScoring.reviewIdentity(labels))
        }
        if (signals.screenshotPath) {
            return FlagDecision(ScanCategory.Screenshot, 1f, SCREENSHOT_PATH_RULE)
        }
        return FlagDecision(ScanCategory.Unflagged, 0f, NONE)
    }
}

/**
 * True when a path segment is the Screenshots folder MediaStore reports.
 * The file name `Screenshot_1.png` inside `DCIM/Camera` is not a match.
 */
internal fun isScreenshotFolder(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return path.split('/', '\\').any { segment ->
        segment.equals("Screenshots", ignoreCase = true)
    }
}
