package com.useinoski.gallerycleanup.detection

import android.graphics.Bitmap
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MarqoNsfwDetector.classify] has to reject the main thread before it hops
 * onto `marqo-nsfw-interpreter`. A check inside that hop never sees the caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarqoNsfwMainThreadTest {
    @Test
    fun classifyOnTheMainThreadFailsBeforeTheInterpreterStarts() = runBlocking {
        val detector = MarqoNsfwDetector(RuntimeEnvironment.getApplication())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            try {
                detector.classify(bitmap)
                fail("main-thread classify should fail")
            } catch (rejected: IllegalStateException) {
                assertTrue(rejected.message!!.contains("main thread"))
            }
            assertEquals("not-loaded", detector.backendName)
        } finally {
            detector.close()
            bitmap.recycle()
        }
    }
}
