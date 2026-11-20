package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.DetectionScoring
import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.detection.label.LabelCategories
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagPolicyTest {
    @Test
    fun nudityBeatsAFace() {
        val decision = FlagPolicy.decide(
            ImageSignals(nsfw = nsfw(0.91f), faces = faces(2f), screenshotPath = true),
        )
        assertEquals(ScanCategory.Sensitive, decision.category)
        assertEquals(0.91f, decision.confidence, 0.0001f)
        assertEquals(DetectionScoring.reviewIdentity(nsfw(0.91f)), decision.modelVersion)
    }

    @Test
    fun aTieWithTheSensitiveThresholdIsNudity() {
        val decision = FlagPolicy.decide(ImageSignals(nsfw = nsfw(FlagPolicy.SENSITIVE_NSFW)))
        assertEquals(ScanCategory.Sensitive, decision.category)
    }

    @Test
    fun suggestiveIsTheLowerNsfwBandAndBeatsPeople() {
        val decision = FlagPolicy.decide(ImageSignals(nsfw = nsfw(0.5f), faces = faces(1f)))
        assertEquals(ScanCategory.Immodest, decision.category)
        assertEquals(0.5f, decision.confidence, 0.0001f)
    }

    @Test
    fun alcoholBeatsPeopleAndMoneyAloneDoesNot() {
        val alcohol = FlagPolicy.decide(
            ImageSignals(labels = labels(alcohol = FlagPolicy.LABEL_THRESHOLD), faces = faces(3f)),
        )
        assertEquals(ScanCategory.AlcoholGambling, alcohol.category)
        assertEquals(FlagPolicy.LABEL_THRESHOLD, alcohol.confidence, 0.0001f)

        val receipt = FlagPolicy.decide(ImageSignals(labels = labels(money = 0.99f)))
        assertEquals(ScanCategory.Unflagged, receipt.category)
        assertEquals(FlagPolicy.NONE, receipt.modelVersion)
    }

    @Test
    fun aFaceGoesToPeopleAheadOfAScreenshot() {
        val decision = FlagPolicy.decide(
            ImageSignals(
                faces = faces(2f),
                labels = labels(meme = 0.95f),
                screenshotPath = true,
            ),
        )
        assertEquals(ScanCategory.People, decision.category)
        assertEquals(2f, decision.confidence, 0.0001f)
        assertEquals("mlkit-face-count|face-v1", decision.modelVersion)
    }

    @Test
    fun memeLabelThenScreenshotFolderThenNothing() {
        val meme = FlagPolicy.decide(ImageSignals(labels = labels(meme = 0.8f)))
        assertEquals(ScanCategory.Screenshot, meme.category)
        assertEquals(DetectionScoring.reviewIdentity(labels(meme = 0.8f)), meme.modelVersion)

        val folder = FlagPolicy.decide(ImageSignals(screenshotPath = true, labels = labels(meme = 0.1f)))
        assertEquals(ScanCategory.Screenshot, folder.category)
        assertEquals(1f, folder.confidence, 0.0001f)
        assertEquals(FlagPolicy.SCREENSHOT_PATH_RULE, folder.modelVersion)

        val clear = FlagPolicy.decide(ImageSignals(nsfw = nsfw(0.1f), faces = faces(0f)))
        assertEquals(ScanCategory.Unflagged, clear.category)
        assertEquals(0f, clear.confidence, 0.0001f)
        assertEquals(FlagPolicy.NONE, clear.modelVersion)
    }

    @Test
    fun aMissingDetectorDoesNotBlockTheOthers() {
        val decision = FlagPolicy.decide(ImageSignals(nsfw = null, faces = faces(1f)))
        assertEquals(ScanCategory.People, decision.category)
    }

    @Test
    fun screenshotFolderIsAPathSegmentNotAFileName() {
        assertTrue(isScreenshotFolder("Pictures/Screenshots/Screenshot_1.png"))
        assertTrue(isScreenshotFolder("DCIM/screenshots/a.jpg"))
        assertFalse(isScreenshotFolder("DCIM/Camera/Screenshot_1.png"))
        assertFalse(isScreenshotFolder("DCIM/Camera/IMG.jpg"))
        assertFalse(isScreenshotFolder(null))
        assertFalse(isScreenshotFolder(""))
    }

    @Test
    fun downscaleKeepsAspectAndCapsTheLongSide() {
        assertEquals(640 to 480, BitmapDecode.targetSize(3200, 2400, 640))
        assertEquals(480 to 640, BitmapDecode.targetSize(1200, 1600, 640))
        assertEquals(640 to 640, BitmapDecode.targetSize(640, 640, 640))
        assertEquals(100 to 50, BitmapDecode.targetSize(100, 50, 640))
    }

    private fun nsfw(score: Float) = DetectionResult(
        scores = mapOf(
            MarqoNsfwDetector.LABEL_NSFW to score,
            MarqoNsfwDetector.LABEL_SFW to (1f - score),
        ),
        detectorId = "marqo-nsfw-384",
        modelVersion = "marqo-v1",
    )

    private fun faces(count: Float) = DetectionResult(
        scores = mapOf(FaceScanVersion.FACE_COUNT to count),
        detectorId = "mlkit-face-count",
        modelVersion = "face-v1",
    )

    private fun labels(
        alcohol: Float = 0f,
        gambling: Float = 0f,
        money: Float = 0f,
        meme: Float = 0f,
    ) = DetectionResult(
        scores = mapOf(
            LabelCategories.ALCOHOL to alcohol,
            LabelCategories.GAMBLING to gambling,
            LabelCategories.MONEY to money,
            LabelCategories.MEME to meme,
        ),
        detectorId = "mlkit-image-labels-v1",
        modelVersion = "labels-v1",
    )
}
