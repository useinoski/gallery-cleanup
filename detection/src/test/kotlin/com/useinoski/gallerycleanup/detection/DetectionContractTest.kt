package com.useinoski.gallerycleanup.detection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionContractTest {
    @Test
    fun resultCarriesTheDetectorThatProducedIt() {
        val result = DetectionResult(
            scores = mapOf("document" to 0.25f),
            detectorId = "none",
            modelVersion = "0",
        )
        assertEquals("none", result.detectorId)
        assertEquals("0", result.modelVersion)
        assertEquals(0.25f, result.scores.getValue("document"))
    }

    @Test
    fun mediaDetectorShapeIsUnchanged() {
        val methods = MediaDetector::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("getId"))
        assertTrue(methods.contains("getModelVersion"))
        assertTrue(methods.contains("getInputSizePx"))
        val classify = MediaDetector::class.java.methods.first { it.name == "classify" }
        val params = classify.parameterTypes.map { it.name }
        assertTrue(params.any { it == "android.graphics.Bitmap" })
        assertTrue(params.any { it.contains("Continuation") })
        val constructor = DetectionResult::class.java.constructors.first { it.parameterTypes.size == 3 }
        assertEquals(Map::class.java, constructor.parameterTypes[0])
        assertEquals(String::class.java, constructor.parameterTypes[1])
        assertEquals(String::class.java, constructor.parameterTypes[2])
    }

    @Test
    fun moduleUsesBundledMlKitAndNotTheDownloadArtifacts() {
        val buildFile = File("build.gradle.kts").readText()
        val catalog = File("../gradle/libs.versions.toml").readText()
        val code = buildFile.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        val catalogCode = catalog.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertTrue(buildFile.contains("libs.mlkit.face.detection"))
        assertTrue(buildFile.contains("libs.mlkit.image.labeling"))
        assertTrue(buildFile.contains("libs.litert"))
        assertTrue(buildFile.contains("libs.litert.gpu"))
        assertFalse(code.contains("play-services"))
        assertFalse(code.contains("com.google.android.gms"))
        assertTrue(catalogCode.contains("com.google.mlkit"))
        assertTrue(catalogCode.contains("name = \"face-detection\""))
        assertTrue(catalogCode.contains("name = \"image-labeling\""))
        assertFalse(catalogCode.contains("play-services"))
        assertFalse(catalogCode.contains("image-labeling-custom"))
        assertTrue(catalogCode.contains("mlkitFace = \"16.1.7\""))
        assertTrue(catalogCode.contains("mlkitImageLabeling = \"17.0.9\""))
        assertTrue(catalogCode.contains("litert = \"1.4.2\""))
        val face = File("src/main/kotlin/com/useinoski/gallerycleanup/detection/face/FaceCountDetector.kt").readText()
        assertTrue(face.contains("com.google.mlkit.vision.face.FaceDetection"))
        assertTrue(face.contains("FaceDetection.getClient"))
        assertFalse(face.contains("requestDownload"))
        assertFalse(face.contains("DownloadConditions"))
    }
}
