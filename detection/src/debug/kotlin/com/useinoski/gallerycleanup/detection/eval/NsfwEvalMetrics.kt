package com.useinoski.gallerycleanup.detection.eval

import java.util.Locale

/**
 * Pure counts for the on-device NSFW labeler. No Android types, so JVM tests
 * can run it, and the clipboard text cannot grow a photo id by accident:
 * [summary] only reads [NsfwEvalReport].
 *
 * A Java reader can treat this `object` as a final class of static methods.
 * [LabeledScore] is a data class: a final class with public final fields, an
 * equals/hashCode based on those fields, and a copy method. There is no
 * threshold baked into the model. Each row applies `score >= threshold`,
 * the same comparison [com.useinoski.gallerycleanup.detection.DetectionScoring]
 * uses.
 *
 * The gate is decided on the tuning half only. A photo's half is a stable
 * hash of its MediaStore id, so relabeling it does not move it. The chosen
 * threshold is the one with the highest recall among rows that have precision
 * at least [MIN_PRECISION] and a false-positive rate at most
 * [MAX_FALSE_POSITIVE_RATE]. Ties break toward higher precision, then a lower
 * false-positive rate, then the higher threshold. If none qualify, [choice]
 * is null and the summary says so.
 */
internal object NsfwEvalMetrics {
    const val MIN_PRECISION = 0.90
    const val MAX_FALSE_POSITIVE_RATE = 0.02

    /** 0.05, 0.10, ... 0.95. Built from integers so the step does not drift. */
    val thresholds: List<Double> = (1..19).map { it / 20.0 }

    fun half(mediaId: Long): EvalHalf {
        return if ((splitMix64(mediaId) and 1L) == 0L) EvalHalf.TUNING else EvalHalf.HELD_OUT
    }

    fun countsOf(labels: Iterable<NudityLabel>): LabelCounts {
        var explicit = 0
        var notExplicit = 0
        var unsure = 0
        var immodest = 0
        for (label in labels) {
            when (label) {
                NudityLabel.EXPLICIT -> explicit++
                NudityLabel.NOT_EXPLICIT -> notExplicit++
                NudityLabel.UNSURE -> unsure++
                NudityLabel.IMMODEST -> immodest++
            }
        }
        return LabelCounts(explicit, notExplicit, unsure, immodest)
    }

    fun report(items: List<LabeledScore>): NsfwEvalReport {
        items.forEach { item ->
            val score = item.nsfwScore
            if (score != null) require(score.isFinite()) { "score must be finite" }
        }
        val tuningItems = items.filter { half(it.mediaId) == EvalHalf.TUNING }
        val heldOutItems = items.filter { half(it.mediaId) == EvalHalf.HELD_OUT }
        val tuning = halfReport(EvalHalf.TUNING, tuningItems)
        val heldOut = halfReport(EvalHalf.HELD_OUT, heldOutItems)
        val chosen = selectThreshold(tuning.rows)
        val choice = chosen?.let { row ->
            ChosenThreshold(
                tuning = row,
                heldOut = heldOut.rows.first { it.threshold == row.threshold },
                tuningImmodest = tuning.immodestRows.first { it.threshold == row.threshold },
                heldOutImmodest = heldOut.immodestRows.first { it.threshold == row.threshold },
            )
        }
        return NsfwEvalReport(
            labelCounts = countsOf(items.map { it.label }),
            notScored = items.count { it.nsfwScore == null },
            tuning = tuning,
            heldOut = heldOut,
            choice = choice,
        )
    }

    /**
     * Best qualifying row, or null when none meet the precision and
     * false-positive caps. Null rates do not qualify: a half with no
     * explicit photos has no recall, and a half with no not-explicit photos
     * has no false-positive rate.
     */
    fun selectThreshold(rows: List<GateRow>): GateRow? {
        val qualifying = rows.filter { qualifies(it) }
        if (qualifying.isEmpty()) return null
        return qualifying.reduce { best, row -> if (beats(row, best)) row else best }
    }

    fun summary(report: NsfwEvalReport): String = buildString {
        appendLine("NSFW detector aggregates only.")
        appendLine("No photo ids, names, paths, or per-photo scores.")
        appendLine()
        appendLine("Label counts")
        appendLine("  all: ${formatCounts(report.labelCounts)}")
        appendLine("  tuning: ${formatCounts(report.tuning.labelCounts)}")
        appendLine("  held-out: ${formatCounts(report.heldOut.labelCounts)}")
        appendLine("  not scored: ${report.notScored}")
        appendLine()
        appendLine("Chosen threshold")
        appendLine(
            "  rule: tuning half only; precision >= ${formatRate(MIN_PRECISION)} and " +
                "false-positive rate <= ${formatRate(MAX_FALSE_POSITIVE_RATE)}; highest recall",
        )
        val choice = report.choice
        if (choice == null) {
            appendLine("  threshold: none")
            appendLine("  held-out at chosen threshold: none")
            appendLine("  immodest at chosen threshold: none")
        } else {
            appendLine("  threshold: ${formatThreshold(choice.tuning.threshold)}")
            appendLine("  tuning: ${formatGateRow(choice.tuning)}")
            appendLine("  held-out: ${formatGateRow(choice.heldOut)}")
            appendLine("  immodest tuning: ${formatImmodestRow(choice.tuningImmodest)}")
            appendLine("  immodest held-out: ${formatImmodestRow(choice.heldOutImmodest)}")
        }
        appendLine()
        appendHalf(report.tuning)
        appendLine()
        appendHalf(report.heldOut)
        appendLine()
        appendLine("Immodest share")
        appendLine("Immodest items are excluded from precision, recall, and the false-positive rate.")
        appendLine("Share is the fraction of scored immodest items at or above the threshold.")
        appendLine("  tuning")
        report.tuning.immodestRows.forEach { row -> appendLine("  ${formatImmodestRow(row)}") }
        appendLine("  held-out")
        report.heldOut.immodestRows.forEach { row -> appendLine("  ${formatImmodestRow(row)}") }
    }

    private fun StringBuilder.appendHalf(half: HalfReport) {
        val name = if (half.half == EvalHalf.TUNING) "Tuning half" else "Held-out half"
        appendLine(name)
        appendLine("  ${formatCounts(half.labelCounts)}")
        half.rows.forEach { row -> appendLine("  ${formatGateRow(row)}") }
    }
}

internal enum class EvalHalf {
    TUNING,
    HELD_OUT,
}

/** One labeled photo. A null score means the detector did not produce a finite number. */
internal data class LabeledScore(
    val mediaId: Long,
    val label: NudityLabel,
    val nsfwScore: Double?,
)

internal data class LabelCounts(
    val explicit: Int,
    val notExplicit: Int,
    val unsure: Int,
    val immodest: Int,
) {
    val total: Int get() = explicit + notExplicit + unsure + immodest
}

/**
 * Explicit photos are the positives (tp / fn). Not-explicit photos are the
 * negatives (fp / tn). Unsure and immodest never increment these four.
 */
internal data class ConfusionCounts(
    val truePositive: Int,
    val falsePositive: Int,
    val trueNegative: Int,
    val falseNegative: Int,
) {
    val precision: Double?
        get() {
            val predictedPositive = truePositive + falsePositive
            if (predictedPositive == 0) return null
            return truePositive.toDouble() / predictedPositive
        }

    val recall: Double?
        get() {
            val positives = truePositive + falseNegative
            if (positives == 0) return null
            return truePositive.toDouble() / positives
        }

    /** False positives divided by not-explicit items. Null when that set is empty. */
    val falsePositiveRate: Double?
        get() {
            val negatives = falsePositive + trueNegative
            if (negatives == 0) return null
            return falsePositive.toDouble() / negatives
        }
}

internal data class GateRow(
    val threshold: Double,
    val counts: LabelCounts,
    val confusion: ConfusionCounts,
)

internal data class ImmodestRow(
    val threshold: Double,
    val labeled: Int,
    val scored: Int,
    val atOrAbove: Int,
) {
    val share: Double?
        get() = if (scored == 0) null else atOrAbove.toDouble() / scored
}

internal data class HalfReport(
    val half: EvalHalf,
    val labelCounts: LabelCounts,
    val rows: List<GateRow>,
    val immodestRows: List<ImmodestRow>,
)

internal data class ChosenThreshold(
    val tuning: GateRow,
    val heldOut: GateRow,
    val tuningImmodest: ImmodestRow,
    val heldOutImmodest: ImmodestRow,
)

internal data class NsfwEvalReport(
    val labelCounts: LabelCounts,
    val notScored: Int,
    val tuning: HalfReport,
    val heldOut: HalfReport,
    val choice: ChosenThreshold?,
)

internal fun formatThreshold(threshold: Double): String =
    String.format(Locale.US, "%.2f", threshold)

internal fun formatRate(rate: Double?): String =
    if (rate == null) "n/a" else String.format(Locale.US, "%.3f", rate)

internal fun formatCounts(counts: LabelCounts): String =
    "explicit ${counts.explicit}, not explicit ${counts.notExplicit}, " +
        "unsure ${counts.unsure}, immodest ${counts.immodest}"

internal fun formatGateRow(row: GateRow): String {
    val confusion = row.confusion
    return String.format(
        Locale.US,
        "%s  precision %s  recall %s  fpr %s  tp %d  fp %d  tn %d  fn %d  %s",
        formatThreshold(row.threshold),
        formatRate(confusion.precision),
        formatRate(confusion.recall),
        formatRate(confusion.falsePositiveRate),
        confusion.truePositive,
        confusion.falsePositive,
        confusion.trueNegative,
        confusion.falseNegative,
        formatCounts(row.counts),
    )
}

internal fun formatImmodestRow(row: ImmodestRow): String =
    String.format(
        Locale.US,
        "%s  immodest %d  scored %d  at or above %d/%d  share %s",
        formatThreshold(row.threshold),
        row.labeled,
        row.scored,
        row.atOrAbove,
        row.scored,
        formatRate(row.share),
    )

private fun halfReport(half: EvalHalf, items: List<LabeledScore>): HalfReport {
    val counts = NsfwEvalMetrics.countsOf(items.map { it.label })
    return HalfReport(
        half = half,
        labelCounts = counts,
        rows = NsfwEvalMetrics.thresholds.map { threshold ->
            GateRow(threshold, counts, confusion(items, threshold))
        },
        immodestRows = NsfwEvalMetrics.thresholds.map { threshold ->
            immodestRow(items, threshold)
        },
    )
}

private fun confusion(items: List<LabeledScore>, threshold: Double): ConfusionCounts {
    var truePositive = 0
    var falsePositive = 0
    var trueNegative = 0
    var falseNegative = 0
    for (item in items) {
        val score = item.nsfwScore ?: continue
        val predictedPositive = score >= threshold
        when (item.label) {
            NudityLabel.EXPLICIT -> if (predictedPositive) truePositive++ else falseNegative++
            NudityLabel.NOT_EXPLICIT -> if (predictedPositive) falsePositive++ else trueNegative++
            NudityLabel.UNSURE, NudityLabel.IMMODEST -> Unit
        }
    }
    return ConfusionCounts(truePositive, falsePositive, trueNegative, falseNegative)
}

private fun immodestRow(items: List<LabeledScore>, threshold: Double): ImmodestRow {
    val immodest = items.filter { it.label == NudityLabel.IMMODEST }
    val scored = immodest.mapNotNull { it.nsfwScore }
    return ImmodestRow(
        threshold = threshold,
        labeled = immodest.size,
        scored = scored.size,
        atOrAbove = scored.count { it >= threshold },
    )
}

private fun qualifies(row: GateRow): Boolean {
    val precision = row.confusion.precision ?: return false
    val recall = row.confusion.recall ?: return false
    val falsePositiveRate = row.confusion.falsePositiveRate ?: return false
    return precision >= NsfwEvalMetrics.MIN_PRECISION &&
        falsePositiveRate <= NsfwEvalMetrics.MAX_FALSE_POSITIVE_RATE
}

/** True when [candidate] should replace [current]. Both rows already qualify. */
private fun beats(candidate: GateRow, current: GateRow): Boolean {
    val candidateConfusion = candidate.confusion
    val currentConfusion = current.confusion
    val recall = compareValues(candidateConfusion.recall, currentConfusion.recall)
    if (recall != 0) return recall > 0
    val precision = compareValues(candidateConfusion.precision, currentConfusion.precision)
    if (precision != 0) return precision > 0
    val falsePositiveRate = compareValues(
        currentConfusion.falsePositiveRate,
        candidateConfusion.falsePositiveRate,
    )
    if (falsePositiveRate != 0) return falsePositiveRate > 0
    return candidate.threshold > current.threshold
}

/**
 * SplitMix64 finalizer. The bit pattern is stable across phones and process
 * runs, unlike [Any.hashCode]. The low bit is the half.
 */
internal fun splitMix64(value: Long): Long {
    var mixed = value + 0x9E3779B97F4A7C15uL.toLong()
    mixed = (mixed xor (mixed ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
    mixed = (mixed xor (mixed ushr 27)) * 0x94D049BB133111EBuL.toLong()
    return mixed xor (mixed ushr 31)
}
