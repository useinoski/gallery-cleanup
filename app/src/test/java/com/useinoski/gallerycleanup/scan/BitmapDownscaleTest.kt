package com.useinoski.gallerycleanup.scan

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapDownscaleTest {
    @Test
    fun aLargeBitmapIsCappedAndTheScaleIsTimed() {
        val source = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xFF336699.toInt())
        val started = System.nanoTime()
        val scaled = BitmapDecode.shrinkIfStillTooBig(source, maxLongSide = 640)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        println("downscale 2400x1800 to long side 640: $elapsedMs ms")
        try {
            assertEquals(640, scaled.width)
            assertEquals(480, scaled.height)
            assertTrue("downscale took $elapsedMs ms", elapsedMs < 2_000)
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }
}
