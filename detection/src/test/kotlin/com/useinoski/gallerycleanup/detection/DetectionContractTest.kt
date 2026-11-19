package com.useinoski.gallerycleanup.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DetectionContractTest {
    @Test
    fun resultCarriesTheDetectorThatProducedIt() {
        val result = DetectionResult(
            scores = mapOf("document" to 0.25f),
            detectorId = "none",
            modelVersion = "0",
        )
        assertEquals("none", result.detectorId)
        assertEquals(0.25f, result.scores.getValue("document"))
    }

    @Test
    fun moduleDoesNotDependOnPlayServicesOrMlKitYet() {
        val buildFile = File("build.gradle.kts").readText()
        assertFalse(buildFile.contains("play-services"))
        assertFalse(buildFile.contains("mlkit"))
    }
}
