package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.detection.nsfw.NsfwVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionScoringTest {
    @Test
    fun equalToTheThresholdIsAFlag() {
        assertTrue(DetectionScoring.exceeds(0.5f, 0.5f))
        assertFalse(DetectionScoring.exceeds(0.49f, 0.5f))
    }

    @Test
    fun aMissingCategoryScoresAsZero() {
        val result = DetectionResult(
            scores = emptyMap(),
            detectorId = "mlkit-image-labels-v1",
            modelVersion = "v",
        )
        assertFalse(DetectionScoring.categoryExceeds(result, "alcohol", 0.5f))
        assertTrue(DetectionScoring.categoryExceeds(result, "alcohol", 0f))
    }

    @Test
    fun faceCountUsesTheSameComparison() {
        val result = DetectionResult(
            scores = mapOf(FaceScanVersion.FACE_COUNT to 1f),
            detectorId = FaceScanVersion.ID,
            modelVersion = FaceScanVersion.of(1280, 0.15f),
        )
        assertTrue(DetectionScoring.categoryExceeds(result, FaceScanVersion.FACE_COUNT, 1f))
        assertFalse(DetectionScoring.categoryExceeds(result, FaceScanVersion.FACE_COUNT, 2f))
    }

    @Test
    fun reviewIdentityIgnoresTheThresholdAndFollowsTheModel() {
        val first = sample(NsfwVersions.MODEL_VERSION)
        val sameScoresNewModel = sample(NsfwVersions.MODEL_VERSION + "-preproc2")
        assertEquals(
            "${NsfwVersions.ID}|${NsfwVersions.MODEL_VERSION}",
            DetectionScoring.reviewIdentity(first),
        )
        assertFalse(
            DetectionScoring.reviewIdentity(first) == DetectionScoring.reviewIdentity(sameScoresNewModel),
        )
        // The identity function has no threshold argument. Re-checking the same
        // result with another cutoff does not create a new identity.
        assertFalse(DetectionScoring.categoryExceeds(first, "NSFW", 0.9f))
        assertTrue(DetectionScoring.categoryExceeds(first, "NSFW", 0.2f))
        assertEquals(DetectionScoring.reviewIdentity(first), DetectionScoring.reviewIdentity(first))
    }

    @Test
    fun versionTokensDoNotDependOnLocale() {
        assertEquals("0.15", 0.15f.toVersionToken())
        assertEquals("0", 0f.toVersionToken())
        assertEquals("1", 1f.toVersionToken())
        assertEquals("0.5", 0.5f.toVersionToken())
    }

    @Test
    fun nonFiniteNumbersAreRejected() {
        assertIllegal { DetectionScoring.exceeds(Float.NaN, 0.5f) }
        assertIllegal { DetectionScoring.exceeds(0.5f, Float.POSITIVE_INFINITY) }
        assertIllegal { Float.NaN.toVersionToken() }
    }

    private fun sample(version: String) = DetectionResult(
        scores = mapOf("NSFW" to 0.2f, "SFW" to 0.8f),
        detectorId = NsfwVersions.ID,
        modelVersion = version,
    )

    private fun assertIllegal(body: () -> Unit) {
        try {
            body()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
