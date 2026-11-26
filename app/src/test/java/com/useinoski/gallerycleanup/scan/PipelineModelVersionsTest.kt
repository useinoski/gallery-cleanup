package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.MODEL_VERSION_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineModelVersionsTest {
    @Test
    fun theRescanSetKeepsCleanRowsAndEveryCurrentRule() {
        val versions = pipelineModelVersions(
            listOf(
                VersionedDetector("mlkit-face-count", "face-v2"),
                VersionedDetector("marqo-nsfw-384", "marqo-v1"),
                VersionedDetector("mlkit-image-labels-v1", "labels-v1"),
            ),
        )

        assertEquals(MODEL_VERSION_NONE, FlagPolicy.NONE)
        assertTrue(versions.contains("mlkit-face-count|face-v2"))
        assertTrue(versions.contains("marqo-nsfw-384|marqo-v1"))
        assertTrue(versions.contains("mlkit-image-labels-v1|labels-v1"))
        assertTrue(versions.contains(FlagPolicy.VIDEO_RULE))
        assertTrue(versions.contains(FlagPolicy.AUDIO_RULE))
        assertTrue(versions.contains(FlagPolicy.SCREENSHOT_PATH_RULE))
        assertTrue(versions.contains(FlagPolicy.NONE))
    }

    @Test
    fun aBlankDetectorIdentityIsRejected() {
        val error = runCatching {
            pipelineModelVersions(listOf(VersionedDetector(" ", "face-v2")))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
