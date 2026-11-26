package com.useinoski.gallerycleanup.detection.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NsfwEvalMetricsTest {
    @Test
    fun thresholdsRunFromFiveHundredthsToNinetyFive() {
        assertEquals((1..19).map { it / 20.0 }, NsfwEvalMetrics.thresholds)
        assertEquals("0.05", formatThreshold(NsfwEvalMetrics.thresholds.first()))
        assertEquals("0.95", formatThreshold(NsfwEvalMetrics.thresholds.last()))
    }

    @Test
    fun theSameMediaIdAlwaysLandsInTheSameHalf() {
        val first = (1L..500L).map { NsfwEvalMetrics.half(it) }
        val second = (1L..500L).map { NsfwEvalMetrics.half(it) }
        assertEquals(first, second)
        val negative = NsfwEvalMetrics.half(-3L)
        assertEquals(negative, NsfwEvalMetrics.half(-3L))
    }

    @Test
    fun knownIdsStayInTheLockedHalf() {
        // SplitMix64 low bit. A hash change moves the held-out set, so these stay locked.
        assertEquals(EvalHalf.HELD_OUT, NsfwEvalMetrics.half(1L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(2L))
        assertEquals(EvalHalf.HELD_OUT, NsfwEvalMetrics.half(3L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(4L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(5L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(6L))
        assertEquals(EvalHalf.HELD_OUT, NsfwEvalMetrics.half(7L))
        assertEquals(EvalHalf.HELD_OUT, NsfwEvalMetrics.half(42L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(100L))
        assertEquals(EvalHalf.TUNING, NsfwEvalMetrics.half(1000L))
        val tuning = (1L..1000L).count { NsfwEvalMetrics.half(it) == EvalHalf.TUNING }
        assertEquals(505, tuning)
        val evenTuning = (2L..1000L step 2).count { NsfwEvalMetrics.half(it) == EvalHalf.TUNING }
        assertTrue(evenTuning in 150..350)
    }

    @Test
    fun relabelingDoesNotChangeTheHalf() {
        val explicit = NsfwEvalMetrics.report(listOf(LabeledScore(42L, NudityLabel.EXPLICIT, 0.2)))
        val notExplicit = NsfwEvalMetrics.report(listOf(LabeledScore(42L, NudityLabel.NOT_EXPLICIT, 0.2)))
        assertEquals(EvalHalf.HELD_OUT, halfContaining(explicit))
        assertEquals(halfContaining(explicit), halfContaining(notExplicit))
        assertEquals(NsfwEvalMetrics.half(42L), halfContaining(explicit))
    }

    @Test
    fun scoreEqualToTheThresholdIsAPositivePrediction() {
        val items = IdSource().run {
            photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.50, 1) +
                photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.50, 1)
        }
        val report = NsfwEvalMetrics.report(items)
        val at = rowAt(report.tuning.rows, 0.50)
        val above = rowAt(report.tuning.rows, 0.55)
        assertEquals(1, at.confusion.truePositive)
        assertEquals(1, at.confusion.falsePositive)
        assertEquals(0, at.confusion.falseNegative)
        assertEquals(0, at.confusion.trueNegative)
        assertEquals(0, above.confusion.truePositive)
        assertEquals(1, above.confusion.falseNegative)
        assertEquals(0, above.confusion.falsePositive)
        assertEquals(1, above.confusion.trueNegative)
    }

    @Test
    fun unsureItemsAreExcludedFromTheConfusion() {
        val source = IdSource()
        val base = source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.99, 2) +
            source.photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.01, 4)
        val withUnsure = base + source.photos(EvalHalf.TUNING, NudityLabel.UNSURE, 0.99, 5)
        assertSameConfusion(NsfwEvalMetrics.report(base), NsfwEvalMetrics.report(withUnsure))
        assertEquals(5, NsfwEvalMetrics.report(withUnsure).tuning.labelCounts.unsure)
    }

    @Test
    fun immodestItemsDoNotChangePrecisionRecallOrFalsePositiveRate() {
        val source = IdSource()
        val base = tuningGateSet(source) + heldOutGateSet(source)
        val withImmodest = base +
            source.photos(EvalHalf.TUNING, NudityLabel.IMMODEST, 0.0, 1) +
            source.photos(EvalHalf.TUNING, NudityLabel.IMMODEST, 0.50, 1) +
            source.photos(EvalHalf.TUNING, NudityLabel.IMMODEST, 1.0, 1) +
            source.photos(EvalHalf.TUNING, NudityLabel.IMMODEST, null, 1) +
            source.photos(EvalHalf.TUNING, NudityLabel.UNSURE, 0.99, 3) +
            source.photos(EvalHalf.HELD_OUT, NudityLabel.IMMODEST, 0.99, 2) +
            source.photos(EvalHalf.HELD_OUT, NudityLabel.UNSURE, 0.0, 1)
        val before = NsfwEvalMetrics.report(base)
        val after = NsfwEvalMetrics.report(withImmodest)
        assertSameConfusion(before, after)
        assertEquals(before.choice?.tuning?.threshold, after.choice?.tuning?.threshold)
        assertEquals(4, after.tuning.labelCounts.immodest)
        val share = immodestAt(after.tuning.immodestRows, 0.50)
        assertEquals(4, share.labeled)
        assertEquals(3, share.scored)
        assertEquals(2, share.atOrAbove)
        assertEquals(2.0 / 3.0, share.share!!, 1e-12)
        val stricter = immodestAt(after.tuning.immodestRows, 0.55)
        assertEquals(1, stricter.atOrAbove)
        assertEquals(2, immodestAt(after.heldOut.immodestRows, 0.05).atOrAbove)
    }

    @Test
    fun anUnscoredExplicitItemIsNotAFalseNegative() {
        val source = IdSource()
        val scored = source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.99, 2) +
            source.photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.01, 4)
        val withGap = scored + source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, null, 1)
        val before = NsfwEvalMetrics.report(scored)
        val after = NsfwEvalMetrics.report(withGap)
        assertSameConfusion(before, after)
        assertEquals(3, after.tuning.labelCounts.explicit)
        assertEquals(1, after.notScored)
        assertEquals(2, rowAt(after.tuning.rows, 0.05).confusion.truePositive)
    }

    @Test
    fun noPositivesLeavesRecallUndefinedAndQualifiesNothing() {
        val items = IdSource().photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.01, 10)
        val report = NsfwEvalMetrics.report(items)
        assertNull(report.choice)
        val row = rowAt(report.tuning.rows, 0.50)
        assertNull(row.confusion.precision)
        assertNull(row.confusion.recall)
        assertEquals(0.0, row.confusion.falsePositiveRate!!, 0.0)
        assertEquals(0, row.confusion.truePositive)
        assertEquals(10, row.confusion.trueNegative)
    }

    @Test
    fun allUnsureQualifiesNothing() {
        val items = IdSource().photos(EvalHalf.TUNING, NudityLabel.UNSURE, 0.99, 3) +
            IdSource().photos(EvalHalf.HELD_OUT, NudityLabel.UNSURE, 0.01, 2)
        val report = NsfwEvalMetrics.report(items)
        assertNull(report.choice)
        assertEquals(5, report.labelCounts.unsure)
        assertEquals(0, report.labelCounts.explicit)
        report.tuning.rows.forEach { row ->
            assertEquals(ConfusionCounts(0, 0, 0, 0), row.confusion)
            assertNull(row.confusion.precision)
            assertNull(row.confusion.recall)
            assertNull(row.confusion.falsePositiveRate)
        }
        val summary = NsfwEvalMetrics.summary(report)
        assertTrue(summary.contains("threshold: none"))
        assertTrue(summary.contains("unsure 3"))
        assertTrue(summary.contains("immodest 0"))
    }

    @Test
    fun noNotExplicitItemsLeaveTheFalsePositiveRateUndefined() {
        val items = IdSource().photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.99, 5)
        val report = NsfwEvalMetrics.report(items)
        assertNull(report.choice)
        val row = rowAt(report.tuning.rows, 0.50)
        assertEquals(1.0, row.confusion.precision!!, 0.0)
        assertEquals(1.0, row.confusion.recall!!, 0.0)
        assertNull(row.confusion.falsePositiveRate)
    }

    @Test
    fun aFlatTiePicksTheHighestThreshold() {
        val source = IdSource()
        val items = source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.99, 10) +
            source.photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.01, 100)
        val report = NsfwEvalMetrics.report(items)
        assertEquals("0.95", formatThreshold(report.choice!!.tuning.threshold))
        assertEquals(1.0, report.choice.tuning.confusion.recall!!, 0.0)
        assertEquals(0.0, report.choice.tuning.confusion.falsePositiveRate!!, 0.0)
    }

    @Test
    fun higherRecallBeatsACleanerLowerRecall() {
        val lowRecall = gate(0.80, truePositive = 10, falsePositive = 0, trueNegative = 100, falseNegative = 10)
        val highRecall = gate(0.20, truePositive = 20, falsePositive = 1, trueNegative = 50, falseNegative = 0)
        assertEquals(0.20, NsfwEvalMetrics.selectThreshold(listOf(lowRecall, highRecall))!!.threshold, 0.0)
    }

    @Test
    fun higherPrecisionBreaksARecallTie() {
        val looser = gate(0.20, truePositive = 10, falsePositive = 1, trueNegative = 100, falseNegative = 0)
        val tighter = gate(0.80, truePositive = 10, falsePositive = 0, trueNegative = 100, falseNegative = 0)
        assertEquals(0.80, NsfwEvalMetrics.selectThreshold(listOf(looser, tighter))!!.threshold, 0.0)
    }

    @Test
    fun lowerFalsePositiveRateBreaksARecallAndPrecisionTie() {
        val calmer = gate(0.20, truePositive = 10, falsePositive = 1, trueNegative = 100, falseNegative = 0)
        val noisier = gate(0.80, truePositive = 10, falsePositive = 1, trueNegative = 49, falseNegative = 0)
        val chosen = NsfwEvalMetrics.selectThreshold(listOf(noisier, calmer))!!
        assertEquals(0.20, chosen.threshold, 0.0)
        assertTrue(chosen.confusion.falsePositiveRate!! < noisier.confusion.falsePositiveRate!!)
        assertEquals(calmer.confusion.precision!!, noisier.confusion.precision!!, 0.0)
        assertEquals(calmer.confusion.recall!!, noisier.confusion.recall!!, 0.0)
    }

    @Test
    fun higherThresholdBreaksAFullTie() {
        val low = gate(0.20, truePositive = 10, falsePositive = 0, trueNegative = 100, falseNegative = 0)
        val high = gate(0.80, truePositive = 10, falsePositive = 0, trueNegative = 100, falseNegative = 0)
        assertEquals(0.80, NsfwEvalMetrics.selectThreshold(listOf(low, high))!!.threshold, 0.0)
    }

    @Test
    fun aThresholdThatMissesEitherCapIsIgnored() {
        val tooManyFalsePositives = gate(
            0.20,
            truePositive = 10,
            falsePositive = 1,
            trueNegative = 40,
            falseNegative = 0,
        )
        val qualifies = gate(0.80, truePositive = 4, falsePositive = 0, trueNegative = 41, falseNegative = 6)
        assertTrue(tooManyFalsePositives.confusion.falsePositiveRate!! > NsfwEvalMetrics.MAX_FALSE_POSITIVE_RATE)
        assertEquals(0.80, NsfwEvalMetrics.selectThreshold(listOf(tooManyFalsePositives, qualifies))!!.threshold, 0.0)
        val undefined = gate(0.50, truePositive = 0, falsePositive = 0, trueNegative = 10, falseNegative = 0)
        assertNull(NsfwEvalMetrics.selectThreshold(listOf(undefined)))
        assertNull(NsfwEvalMetrics.selectThreshold(emptyList()))
    }

    @Test
    fun theChosenThresholdIsFixedOnTheTuningHalf() {
        val source = IdSource()
        val report = NsfwEvalMetrics.report(tuningGateSet(source) + heldOutGateSet(source))
        val choice = report.choice!!
        assertEquals("0.40", formatThreshold(choice.tuning.threshold))
        assertEquals(15, choice.tuning.confusion.truePositive)
        assertEquals(1, choice.tuning.confusion.falsePositive)
        assertEquals(200, choice.tuning.confusion.trueNegative)
        assertEquals(0, choice.tuning.confusion.falseNegative)
        assertEquals(1.0, choice.tuning.confusion.recall!!, 1e-12)
        assertEquals("0.40", formatThreshold(choice.heldOut.threshold))
        assertEquals(8, choice.heldOut.confusion.truePositive)
        assertEquals(0, choice.heldOut.confusion.falseNegative)
        assertEquals(1.0, choice.heldOut.confusion.recall!!, 0.0)
        val heldOutAtNinetyFive = rowAt(report.heldOut.rows, 0.95)
        assertEquals(0.5, heldOutAtNinetyFive.confusion.recall!!, 1e-12)
        assertFalse(formatThreshold(heldOutAtNinetyFive.threshold) == formatThreshold(choice.tuning.threshold))
    }

    @Test
    fun everyGateRowCarriesTheLabelCountsBesideTheRates() {
        val source = IdSource()
        val report = NsfwEvalMetrics.report(
            tuningGateSet(source) +
                heldOutGateSet(source) +
                source.photos(EvalHalf.TUNING, NudityLabel.UNSURE, 0.2, 7) +
                source.photos(EvalHalf.TUNING, NudityLabel.IMMODEST, 0.4, 4) +
                source.photos(EvalHalf.HELD_OUT, NudityLabel.UNSURE, null, 1),
        )
        val summary = NsfwEvalMetrics.summary(report)
        listOf(report.tuning, report.heldOut).forEach { half ->
            half.rows.forEach { row ->
                assertEquals(half.labelCounts, row.counts)
                assertTrue(summary.contains(formatGateRow(row)))
                assertTrue(formatGateRow(row).contains("precision "))
                assertTrue(formatGateRow(row).contains("recall "))
                assertTrue(formatGateRow(row).contains("fpr "))
                assertTrue(formatGateRow(row).contains(formatCounts(row.counts)))
            }
            half.immodestRows.forEach { row ->
                assertTrue(summary.contains(formatImmodestRow(row)))
            }
        }
        assertTrue(summary.contains("explicit 15, not explicit 201, unsure 7, immodest 4"))
        assertTrue(summary.contains("immodest tuning:"))
        assertTrue(summary.contains("immodest held-out:"))
        val planted = LabeledScore(424242424242L, NudityLabel.EXPLICIT, 0.123456789)
        val plantedSummary = NsfwEvalMetrics.summary(NsfwEvalMetrics.report(listOf(planted)))
        assertFalse(plantedSummary.contains("424242424242"))
        assertFalse(plantedSummary.contains("0.123456789"))
        assertFalse(plantedSummary.contains("content:"))
        assertFalse(plantedSummary.contains("://"))
    }

    @Test
    fun anEmptyInputStillHasAFullTable() {
        val report = NsfwEvalMetrics.report(emptyList())
        assertEquals(19, report.tuning.rows.size)
        assertEquals(19, report.heldOut.rows.size)
        assertEquals(19, report.tuning.immodestRows.size)
        assertNull(report.choice)
        assertEquals(0, report.labelCounts.total)
        assertEquals(0, report.notScored)
        val summary = NsfwEvalMetrics.summary(report)
        assertTrue(summary.contains("explicit 0, not explicit 0, unsure 0, immodest 0"))
        assertTrue(summary.contains("threshold: none"))
        assertTrue(summary.contains("immodest at chosen threshold: none"))
    }

    @Test
    fun theReportTypeHoldsNoPerItemScores() {
        val fieldTypes = NsfwEvalReport::class.java.declaredFields.joinToString { it.genericType.toString() }
        assertFalse(fieldTypes, fieldTypes.contains("LabeledScore"))
        assertFalse(fieldTypes, fieldTypes.contains("NudityLabel"))
        assertFalse(fieldTypes, fieldTypes.contains("mediaId"))
    }

    @Test
    fun aNonFiniteScoreIsRejected() {
        try {
            NsfwEvalMetrics.report(listOf(LabeledScore(1L, NudityLabel.EXPLICIT, Double.NaN)))
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    private fun tuningGateSet(source: IdSource): List<LabeledScore> =
        source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.99, 10) +
            source.photos(EvalHalf.TUNING, NudityLabel.EXPLICIT, 0.40, 5) +
            source.photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.80, 1) +
            source.photos(EvalHalf.TUNING, NudityLabel.NOT_EXPLICIT, 0.02, 200)

    private fun heldOutGateSet(source: IdSource): List<LabeledScore> =
        source.photos(EvalHalf.HELD_OUT, NudityLabel.EXPLICIT, 0.99, 4) +
            source.photos(EvalHalf.HELD_OUT, NudityLabel.EXPLICIT, 0.50, 4) +
            source.photos(EvalHalf.HELD_OUT, NudityLabel.NOT_EXPLICIT, 0.01, 50)

    private fun gate(
        threshold: Double,
        truePositive: Int,
        falsePositive: Int,
        trueNegative: Int,
        falseNegative: Int,
    ): GateRow = GateRow(
        threshold = threshold,
        counts = LabelCounts(explicit = truePositive + falseNegative, notExplicit = falsePositive + trueNegative, unsure = 0, immodest = 0),
        confusion = ConfusionCounts(truePositive, falsePositive, trueNegative, falseNegative),
    )
}

private class IdSource {
    private val taken = HashSet<Long>()
    private var next = 1L

    fun photos(half: EvalHalf, label: NudityLabel, score: Double?, count: Int): List<LabeledScore> {
        val ids = ArrayList<Long>(count)
        while (ids.size < count) {
            val id = next++
            if (id in taken || NsfwEvalMetrics.half(id) != half) continue
            taken += id
            ids += id
            check(next < 1_000_000L) { "ran out of ids" }
        }
        return ids.map { LabeledScore(it, label, score) }
    }
}

private fun rowAt(rows: List<GateRow>, threshold: Double): GateRow =
    rows.first { abs(it.threshold - threshold) < 1e-9 }

private fun immodestAt(rows: List<ImmodestRow>, threshold: Double): ImmodestRow =
    rows.first { abs(it.threshold - threshold) < 1e-9 }

private fun halfContaining(report: NsfwEvalReport): EvalHalf = when {
    report.tuning.labelCounts.total == 1 -> EvalHalf.TUNING
    report.heldOut.labelCounts.total == 1 -> EvalHalf.HELD_OUT
    else -> error("expected one labeled photo")
}

private fun assertSameConfusion(before: NsfwEvalReport, after: NsfwEvalReport) {
    assertEquals(before.tuning.rows.map { it.confusion }, after.tuning.rows.map { it.confusion })
    assertEquals(before.heldOut.rows.map { it.confusion }, after.heldOut.rows.map { it.confusion })
}
