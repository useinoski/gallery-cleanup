package com.useinoski.gallerycleanup.detection.eval

import android.graphics.Bitmap
import android.widget.Button
import android.widget.TextView
import com.useinoski.gallerycleanup.detection.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsfwLabelActivityTest {
    @Test
    fun theScreenShowsAllFourLabelsAndDoesNotAskForAccess() {
        val controller = Robolectric.buildActivity(NsfwLabelActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(
            activity.getString(R.string.nsfw_labeler_need_access),
            activity.findViewById<TextView>(R.id.nsfw_labeler_status).text.toString(),
        )
        assertEquals(
            "Explicit",
            activity.findViewById<Button>(R.id.nsfw_labeler_explicit).text.toString(),
        )
        assertEquals(
            "Not explicit",
            activity.findViewById<Button>(R.id.nsfw_labeler_not_explicit).text.toString(),
        )
        assertEquals(
            "Unsure",
            activity.findViewById<Button>(R.id.nsfw_labeler_unsure).text.toString(),
        )
        assertEquals(
            "Immodest (not explicit)",
            activity.findViewById<Button>(R.id.nsfw_labeler_immodest).text.toString(),
        )
        assertFalse(activity.findViewById<Button>(R.id.nsfw_labeler_explicit).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.nsfw_labeler_copy).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.nsfw_labeler_run).isEnabled)
        controller.destroy()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NsfwPhotoQueueTest {
    @Test
    fun targetSizeCapsTheLongSide() {
        assertEquals(10 to 20, labelerTargetSize(10, 20, 1280))
        assertEquals(640 to 1280, labelerTargetSize(1280, 2560, 1280))
        assertEquals(1280 to 640, labelerTargetSize(2560, 1280, 1280))
    }

    @Test
    fun emptyAndNonPositiveSizesAreRejected() {
        assertIllegal { labelerTargetSize(0, 10, 10) }
        assertIllegal { labelerTargetSize(10, -1, 10) }
        assertIllegal { labelerTargetSize(10, 10, 0) }
    }

    @Test
    fun blurKeepsTheFrameAndLeavesTheSharpBitmap() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val blurred = blurForLabel(source)
        try {
            assertEquals(64, blurred.width)
            assertEquals(32, blurred.height)
            assertFalse(source.isRecycled)
            assertTrue(blurred !== source)
        } finally {
            source.recycle()
            if (!blurred.isRecycled) blurred.recycle()
        }
    }

    private fun assertIllegal(body: () -> Unit) {
        try {
            body()
        } catch (expected: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
