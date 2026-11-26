package com.useinoski.gallerycleanup.detection.nsfw

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.MediaDetector
import com.useinoski.gallerycleanup.detection.image.requireUsableBitmap
import com.useinoski.gallerycleanup.detection.image.scaleCenterSquare

/**
 * Marqo `nsfw-image-detection-384`, int8 LiteRT conversion, on the phone.
 *
 * [classify] returns the softmax of the two logits, keyed [LABEL_NSFW] and
 * [LABEL_SFW]. It does not apply a cutoff. Pass the NSFW score and whatever
 * threshold you are tuning to [com.useinoski.gallerycleanup.detection.DetectionScoring].
 *
 * The interpreter is created on the first call and then reused. GPU is tried
 * first, then NNAPI, then a CPU interpreter with XNNPACK. [backendName] reports
 * which one actually ran. That name is not part of [modelVersion]: two phones
 * may pick different delegates, and a review is about the model, not the chip.
 *
 * Creation, [NsfwInterpreter.run], and [close] all run on one thread owned by
 * this detector. The LiteRT GPU delegate is not safe to create on one thread
 * and use or close on another. A shared dispatcher such as `Dispatchers.Default`
 * can hop threads between calls.
 */
class MarqoNsfwDetector(
    context: Context,
    cpuThreads: Int = defaultCpuThreads(),
    allowGpu: Boolean = true,
    allowNnapi: Boolean = true,
) : MediaDetector, AutoCloseable {
    private val appContext = context.applicationContext
    private val session = InterpreterSingleThread {
        NsfwInterpreter(
            modelBuffer = loadModelBuffer(appContext, NsfwVersions.ASSET_NAME),
            cpuThreads = cpuThreads,
            allowGpu = allowGpu,
            allowNnapi = allowNnapi,
        )
    }

    override val id: String = NsfwVersions.ID
    override val modelVersion: String = NsfwVersions.MODEL_VERSION
    override val inputSizePx: Int = NsfwMath.INPUT_SIZE

    val backendName: String
        get() = session.current?.backendName ?: "not-loaded"

    override suspend fun classify(input: Bitmap): DetectionResult {
        // Before the hop. Inside session.use this thread is marqo-nsfw-interpreter,
        // so the check would no longer see a main-thread caller.
        checkOffMainThread()
        val logits = session.use { runtime ->
            val nchw = preprocess(input)
            runtime.run(nchw)
        }
        return DetectionResult(
            scores = NsfwMath.scores(logits),
            detectorId = id,
            modelVersion = modelVersion,
        )
    }

    override fun close() {
        session.close()
    }

    private fun preprocess(input: Bitmap): FloatArray {
        requireUsableBitmap(input)
        val scaled = scaleCenterSquare(input, NsfwMath.INPUT_SIZE)
        try {
            val pixels = IntArray(NsfwMath.INPUT_SIZE * NsfwMath.INPUT_SIZE)
            scaled.bitmap.getPixels(
                pixels,
                0,
                NsfwMath.INPUT_SIZE,
                0,
                0,
                NsfwMath.INPUT_SIZE,
                NsfwMath.INPUT_SIZE,
            )
            val nchw = FloatArray(NsfwMath.inputFloats)
            NsfwMath.writeNchw(pixels, nchw)
            return nchw
        } finally {
            scaled.close()
        }
    }

    companion object {
        const val LABEL_NSFW: String = "NSFW"
        const val LABEL_SFW: String = "SFW"
    }
}

internal fun checkOffMainThread() {
    val main = Looper.getMainLooper() ?: return
    check(Thread.currentThread() !== main.thread) {
        "Image detectors must run off the main thread."
    }
}
