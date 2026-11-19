package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.nsfw.NsfwMath
import com.useinoski.gallerycleanup.detection.nsfw.NsfwVersions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NsfwMathTest {
    @Test
    fun normalizationMatchesTheHuggingFaceConfig() {
        assertEquals(-1f, NsfwMath.normalize(0), 0f)
        assertEquals(1f, NsfwMath.normalize(255), 0f)
        assertEquals((128f / 255f - 0.5f) / 0.5f, NsfwMath.normalize(128), 1e-6f)
    }

    @Test
    fun pixelsAreWrittenNchwRgb() {
        val pixels = IntArray(NsfwMath.INPUT_SIZE * NsfwMath.INPUT_SIZE)
        pixels[0] = 0xFF112233.toInt()
        val last = pixels.lastIndex
        pixels[last] = 0xFFAABBCC.toInt()
        val dest = FloatArray(NsfwMath.inputFloats)
        NsfwMath.writeNchw(pixels, dest)
        val plane = NsfwMath.INPUT_SIZE * NsfwMath.INPUT_SIZE
        assertEquals(NsfwMath.normalize(0x11), dest[0], 0f)
        assertEquals(NsfwMath.normalize(0x22), dest[plane], 0f)
        assertEquals(NsfwMath.normalize(0x33), dest[2 * plane], 0f)
        assertEquals(NsfwMath.normalize(0xAA), dest[last], 0f)
        assertEquals(NsfwMath.normalize(0xBB), dest[plane + last], 0f)
        assertEquals(NsfwMath.normalize(0xCC), dest[2 * plane + last], 0f)
    }

    @Test
    fun packRoundTripsTheFloatBuffer() {
        val nchw = FloatArray(NsfwMath.inputFloats)
        nchw[0] = -1f
        nchw[1] = 0.25f
        nchw[nchw.lastIndex] = 1f
        val buffer = ByteBuffer.allocate(nchw.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        NsfwMath.packNchw(nchw, buffer)
        val read = FloatArray(nchw.size)
        buffer.asFloatBuffer().get(read)
        assertEquals(-1f, read[0], 0f)
        assertEquals(0.25f, read[1], 0f)
        assertEquals(1f, read[read.lastIndex], 0f)
    }

    @Test
    fun softmaxIsStableAndLabelZeroIsNsfw() {
        val even = NsfwMath.scores(floatArrayOf(0f, 0f))
        assertEquals(listOf("NSFW", "SFW"), even.keys.toList())
        assertEquals(0.5f, even.getValue("NSFW"), 1e-6f)
        assertEquals(0.5f, even.getValue("SFW"), 1e-6f)

        val huge = NsfwMath.softmax(floatArrayOf(1000f, 1000f))
        assertEquals(0.5f, huge[0], 1e-5f)
        assertEquals(1f, huge.sum(), 1e-5f)

        val skewed = NsfwMath.scores(floatArrayOf(0f, 1f))
        assertEquals(0.26894143f, skewed.getValue("NSFW"), 1e-5f)
        assertEquals(0.7310586f, skewed.getValue("SFW"), 1e-5f)
        assertEquals(1f, skewed.values.sum(), 1e-5f)
    }

    @Test
    fun versionNamesTheConversionAndNotAThreshold() {
        assertEquals("marqo-nsfw-384", NsfwVersions.ID)
        assertTrue(NsfwVersions.MODEL_VERSION.contains("int8-float-io"))
        assertTrue(NsfwVersions.MODEL_VERSION.contains("preproc1"))
        assertTrue(!NsfwVersions.MODEL_VERSION.contains("threshold"))
        assertEquals("marqo_vit_tiny_384_int8.tflite", NsfwVersions.ASSET_NAME)
    }
}
