package com.useinoski.gallerycleanup.detection.nsfw

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Pure math for the Marqo NSFW model. No Android types, so JVM tests can run it.
 *
 * The graph was checked with the LiteRT interpreter:
 * input `serving_default_args_0` is float32 NCHW `[1, 3, 384, 384]`,
 * output `output_0` is float32 `[1, 2]` logits (not probabilities).
 * Weights are int8. The input and the output stay float32.
 *
 * Label order is `label_names` in the Hugging Face config.json:
 * index 0 is NSFW, index 1 is SFW. The model card applies softmax outside
 * the network (`output.softmax`), and a sample run produced a negative logit,
 * so this code applies softmax itself.
 *
 * Normalization is the config's `mean` and `std`, both 0.5 on RGB:
 * `(pixel / 255 - 0.5) / 0.5`, which maps 0 to -1 and 255 to 1.
 */
internal object NsfwMath {
    const val INPUT_SIZE = 384
    const val SIGNATURE = "serving_default"
    const val INPUT_NAME = "args_0"
    const val OUTPUT_NAME = "output_0"
    const val LABEL_NSFW = "NSFW"
    const val LABEL_SFW = "SFW"

    val labels: List<String> = listOf(LABEL_NSFW, LABEL_SFW)
    val inputFloats: Int = 3 * INPUT_SIZE * INPUT_SIZE

    private const val MEAN = 0.5f
    private const val STD = 0.5f

    fun normalize(channel: Int): Float {
        require(channel in 0..255) { "channel $channel is outside 0..255" }
        return (channel / 255f - MEAN) / STD
    }

    /** [pixels] are Android ARGB ints, row-major, already 384x384. [dest] is NCHW RGB. */
    fun writeNchw(pixels: IntArray, dest: FloatArray) {
        val plane = INPUT_SIZE * INPUT_SIZE
        require(pixels.size == plane) { "expected $plane pixels, got ${pixels.size}" }
        require(dest.size == 3 * plane) { "expected ${3 * plane} floats, got ${dest.size}" }
        for (index in pixels.indices) {
            val pixel = pixels[index]
            dest[index] = normalize((pixel shr 16) and 0xFF)
            dest[plane + index] = normalize((pixel shr 8) and 0xFF)
            dest[2 * plane + index] = normalize(pixel and 0xFF)
        }
    }

    fun packNchw(nchw: FloatArray, dest: ByteBuffer) {
        require(nchw.size == inputFloats) { "expected $inputFloats floats, got ${nchw.size}" }
        require(dest.capacity() == nchw.size * Float.SIZE_BYTES) {
            "buffer capacity ${dest.capacity()} does not match the input tensor"
        }
        dest.order(ByteOrder.nativeOrder())
        dest.position(0)
        dest.limit(dest.capacity())
        dest.asFloatBuffer().put(nchw)
        dest.position(0)
        dest.limit(dest.capacity())
    }

    fun softmax(logits: FloatArray): FloatArray {
        require(logits.isNotEmpty()) { "logits are empty" }
        var maxLogit = Float.NEGATIVE_INFINITY
        for (logit in logits) {
            require(logit.isFinite()) { "non-finite logit" }
            if (logit > maxLogit) maxLogit = logit
        }
        val probabilities = FloatArray(logits.size)
        var sum = 0.0
        for (index in logits.indices) {
            val raised = exp((logits[index] - maxLogit).toDouble())
            probabilities[index] = raised.toFloat()
            sum += raised
        }
        require(sum > 0.0) { "softmax sum was 0" }
        for (index in probabilities.indices) {
            probabilities[index] = (probabilities[index] / sum).toFloat()
        }
        return probabilities
    }

    fun scores(logits: FloatArray): Map<String, Float> {
        require(logits.size == labels.size) { "expected ${labels.size} logits, got ${logits.size}" }
        val probabilities = softmax(logits)
        return labels.indices.associate { index -> labels[index] to probabilities[index] }
    }
}

internal object NsfwVersions {
    const val ID = "marqo-nsfw-384"
    /**
     * Bump the `preproc` suffix if the crop, the normalization, the label order,
     * or the softmax location changes. Do not put the decision threshold here.
     */
    const val MODEL_VERSION = "marqo-nsfw-image-detection-384-int8-float-io-preproc1"
    const val ASSET_NAME = "marqo_vit_tiny_384_int8.tflite"
    const val SHA256 = "f66b9353965fe55371b6f2205726052f073b388559d1e744b90db13c8e252a7f"
    const val BYTE_SIZE = 6_457_696L
}

internal fun defaultCpuThreads(): Int =
    Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
