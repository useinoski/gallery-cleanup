package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.label.LabelCategories
import com.useinoski.gallerycleanup.detection.label.LabelHit
import com.useinoski.gallerycleanup.detection.label.LabelScanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelCategoriesTest {
    @Test
    fun alcoholIsTheMaxOfThePublishedLabels() {
        val scores = LabelCategories.scores(
            listOf(
                LabelHit("Wine", 0.2f),
                LabelHit("Bar", 0.55f),
                LabelHit("Nightclub", 0.4f),
                LabelHit("Alcohol", 0.1f),
            ),
            includeMemeHint = false,
        )
        assertEquals(0.55f, scores.getValue(LabelCategories.ALCOHOL), 0f)
        assertEquals(0f, scores.getValue(LabelCategories.GAMBLING), 0f)
        assertEquals(0f, scores.getValue(LabelCategories.MONEY), 0f)
        assertFalse(scores.containsKey(LabelCategories.MEME))
    }

    @Test
    fun moneyStaysOutOfTheGamblingScoreUntilTheCallerWeightsIt() {
        val scores = LabelCategories.scores(
            listOf(LabelHit("Casino", 0.4f), LabelHit("Money", 0.9f)),
            includeMemeHint = false,
        )
        assertEquals(0.4f, scores.getValue(LabelCategories.GAMBLING), 0f)
        assertEquals(0.9f, scores.getValue(LabelCategories.MONEY), 0f)
        assertEquals(0.4f, LabelCategories.gamblingScore(scores, moneyWeight = 0f), 0f)
        assertEquals(0.45f, LabelCategories.gamblingScore(scores, moneyWeight = 0.5f), 1e-6f)
        assertEquals(0.9f, LabelCategories.gamblingScore(scores, moneyWeight = 1f), 0f)
    }

    @Test
    fun memeHintIsOptionalAndUsesTheMax() {
        val labels = listOf(
            LabelHit("Screenshot", 0.2f),
            LabelHit("Comics", 0.7f),
            LabelHit("Poster", 0.3f),
        )
        assertFalse(LabelCategories.scores(labels, includeMemeHint = false).containsKey(LabelCategories.MEME))
        val withHint = LabelCategories.scores(labels, includeMemeHint = true)
        assertEquals(0.7f, withHint.getValue(LabelCategories.MEME), 0f)
    }

    @Test
    fun unknownAndOutOfScopeLabelsDoNotMoveAScore() {
        val scores = LabelCategories.scores(
            LabelCategories.outOfScopeLabels.map { LabelHit(it, 0.99f) } +
                LabelHit("alcohol", 0.99f) +
                LabelHit("Sky", 0.8f) +
                LabelHit("Person", 0.8f),
            includeMemeHint = true,
        )
        assertEquals(0f, scores.getValue(LabelCategories.ALCOHOL), 0f)
        assertEquals(0f, scores.getValue(LabelCategories.GAMBLING), 0f)
        assertEquals(0f, scores.getValue(LabelCategories.MONEY), 0f)
        assertEquals(0f, scores.getValue(LabelCategories.MEME), 0f)
    }

    @Test
    fun duplicateLabelsKeepTheHigherConfidence() {
        val scores = LabelCategories.scores(
            listOf(LabelHit("Casino", 0.2f), LabelHit("Casino", 0.7f)),
            includeMemeHint = false,
        )
        assertEquals(0.7f, scores.getValue(LabelCategories.GAMBLING), 0f)
    }

    @Test
    fun versionRecordsTheFloorTheLongSideAndTheMemeSwitch() {
        assertEquals(
            "mlkit-image-labeling-17.0.9-bundled-categories-v1-long640-floor0-meme0",
            LabelScanVersion.of(640, 0f, includeMemeHint = false),
        )
        assertTrue(
            LabelScanVersion.of(640, 0f, includeMemeHint = true).endsWith("meme1"),
        )
        assertEquals("mlkit-image-labels-v1", LabelScanVersion.ID)
    }

    @Test
    fun emptyInputStillReturnsTheThreeCategoryKeys() {
        val scores = LabelCategories.scores(emptyList(), includeMemeHint = false)
        assertEquals(
            listOf(LabelCategories.ALCOHOL, LabelCategories.GAMBLING, LabelCategories.MONEY),
            scores.keys.toList(),
        )
    }
}
