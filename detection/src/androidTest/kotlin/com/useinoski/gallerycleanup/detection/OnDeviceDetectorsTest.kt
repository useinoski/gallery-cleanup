package com.useinoski.gallerycleanup.detection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.useinoski.gallerycleanup.detection.face.FaceCountDetector
import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.detection.label.ImageLabelCategoryDetector
import com.useinoski.gallerycleanup.detection.label.LabelCategories
import com.useinoski.gallerycleanup.detection.label.LabelScanVersion
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import com.useinoski.gallerycleanup.detection.nsfw.NsfwMath
import com.useinoski.gallerycleanup.detection.nsfw.NsfwVersions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads the bundled model and runs all three detectors on a synthetic landscape.
 * The image is bands of sky and grass. It has no person and no explicit content.
 * The test process must not hold a network permission: a download would fail.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceDetectorsTest {
    @Test
    fun detectorsRunWithNoNetworkPermission() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            target.checkSelfPermission(Manifest.permission.INTERNET),
        )
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            target.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE),
        )

        val bitmap = instrumentation.context.assets.open("benign_landscape.png").use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        checkNotNull(bitmap) { "test image did not decode" }

        val nsfw = MarqoNsfwDetector(target)
        val faces = FaceCountDetector(target)
        val labels = ImageLabelCategoryDetector(target)
        val labelsWithMeme = ImageLabelCategoryDetector(target, includeMemeHint = true)
        try {
            val started = System.nanoTime()
            val nsfwResult = nsfw.classify(bitmap)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(NsfwVersions.ID, nsfwResult.detectorId)
            assertEquals(NsfwVersions.MODEL_VERSION, nsfwResult.modelVersion)
            assertEquals(MarqoNsfwDetector.LABEL_NSFW, NsfwMath.LABEL_NSFW)
            val nsfwScore = nsfwResult.scores.getValue(MarqoNsfwDetector.LABEL_NSFW)
            val sfwScore = nsfwResult.scores.getValue(MarqoNsfwDetector.LABEL_SFW)
            assertTrue(nsfwScore in 0f..1f)
            assertTrue(sfwScore in 0f..1f)
            assertEquals(1f, nsfwScore + sfwScore, 1e-3f)
            assertTrue("benign landscape should lean SFW, scores=$nsfwResult", sfwScore > nsfwScore)
            assertFalse(DetectionScoring.categoryExceeds(nsfwResult, MarqoNsfwDetector.LABEL_NSFW, 0.99f))
            println("nsfw backend=${nsfw.backendName} ms=$elapsedMs nsfw=$nsfwScore sfw=$sfwScore")

            val faceResult = faces.classify(bitmap)
            assertEquals(FaceScanVersion.ID, faceResult.detectorId)
            assertEquals(
                FaceScanVersion.of(
                    FaceScanVersion.DEFAULT_MAX_LONG_SIDE_PX,
                    FaceScanVersion.DEFAULT_MIN_FACE_SIZE,
                ),
                faceResult.modelVersion,
            )
            assertEquals(0f, faceResult.scores.getValue(FaceScanVersion.FACE_COUNT), 0f)
            assertFalse(DetectionScoring.categoryExceeds(faceResult, FaceScanVersion.FACE_COUNT, 1f))

            val labelResult = labels.classify(bitmap)
            assertEquals(LabelScanVersion.ID, labelResult.detectorId)
            assertEquals(
                LabelScanVersion.of(640, 0f, includeMemeHint = false),
                labelResult.modelVersion,
            )
            listOf(LabelCategories.ALCOHOL, LabelCategories.GAMBLING, LabelCategories.MONEY).forEach { key ->
                val score = labelResult.scores.getValue(key)
                assertTrue("$key=$score", score in 0f..1f)
            }
            assertFalse(labelResult.scores.containsKey(LabelCategories.MEME))

            val memeResult = labelsWithMeme.classify(bitmap)
            assertTrue(memeResult.scores.containsKey(LabelCategories.MEME))
            assertTrue(memeResult.modelVersion.endsWith("meme1"))

            val notices = ThirdPartyNotices.readAsset(target)
            assertTrue(notices.contains("Marqo nsfw-image-detection-384"))
            assertTrue(notices.contains("Apache License"))
        } finally {
            nsfw.close()
            faces.close()
            labels.close()
            labelsWithMeme.close()
            bitmap.recycle()
        }
    }
}
