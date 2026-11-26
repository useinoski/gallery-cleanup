package com.useinoski.gallerycleanup.detection.nsfw

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

/**
 * One reused LiteRT interpreter.
 *
 * Construction order is GPU, then NNAPI, then CPU. A delegate that fails to
 * load, or whose first run throws, is closed and the next one is tried. The
 * CPU interpreter turns XNNPACK on. The GPU delegate has to be created, used,
 * and closed on one thread. [MarqoNsfwDetector] does that with
 * [InterpreterSingleThread]; this class does not hop threads itself.
 *
 * GPU options allow the int8 weights. The input tensor is still float32 NCHW,
 * which many GPU drivers reject. That rejection is a fallback, not a crash.
 */
internal class NsfwInterpreter(
    private val modelBuffer: ByteBuffer,
    private val cpuThreads: Int,
    allowGpu: Boolean,
    allowNnapi: Boolean,
) : AutoCloseable {
    private val order: List<Backend> = buildList {
        if (allowGpu) add(Backend.GPU)
        if (allowNnapi) add(Backend.NNAPI)
        add(Backend.CPU)
    }
    private var nextBackend = 0
    private var open: OpenBackend? = null
    private var logged = false
    private val inputBuffer = ByteBuffer.allocateDirect(NsfwMath.inputFloats * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(2) }

    val backendName: String
        get() = open?.backend?.wireName ?: "not-loaded"

    fun run(nchw: FloatArray): FloatArray {
        NsfwMath.packNchw(nchw, inputBuffer)
        val failures = mutableListOf<String>()
        while (true) {
            val current = open ?: openNext(failures)
            try {
                output[0][0] = 0f
                output[0][1] = 0f
                current.interpreter.runSignature(
                    mapOf(NsfwMath.INPUT_NAME to inputBuffer),
                    mapOf(NsfwMath.OUTPUT_NAME to output),
                    NsfwMath.SIGNATURE,
                )
                logOnce(current.backend)
                return output[0].copyOf()
            } catch (error: Throwable) {
                if (error is Error && error !is LinkageError) throw error
                failures += "${current.backend.wireName}: ${error.javaClass.simpleName}: ${error.message}"
                current.close()
                open = null
                if (current.backend == Backend.CPU) {
                    throw IllegalStateException(
                        "NSFW model failed on every backend: ${failures.joinToString(" | ")}",
                        error,
                    )
                }
            }
        }
    }

    override fun close() {
        open?.close()
        open = null
    }

    private fun openNext(failures: MutableList<String>): OpenBackend {
        var last: Throwable? = null
        while (nextBackend < order.size) {
            val backend = order[nextBackend]
            nextBackend += 1
            try {
                val opened = openBackend(backend)
                open = opened
                return opened
            } catch (error: Throwable) {
                if (error is Error && error !is LinkageError) throw error
                last = error
                failures += "${backend.wireName} init: ${error.javaClass.simpleName}: ${error.message}"
                if (backend == Backend.CPU) break
            }
        }
        throw IllegalStateException(
            "NSFW model failed on every backend: ${failures.joinToString(" | ")}",
            last,
        )
    }

    private fun openBackend(backend: Backend): OpenBackend {
        rewindModel()
        return when (backend) {
            Backend.GPU -> openGpu()
            Backend.NNAPI -> openNnapi()
            Backend.CPU -> openCpu()
        }
    }

    private fun openGpu(): OpenBackend {
        val delegate = CompatibilityList().use { compat ->
            if (!compat.isDelegateSupportedOnThisDevice) {
                throw UnsupportedOperationException("GPU delegate is not supported on this device")
            }
            val options = compat.bestOptionsForThisDevice
            options.setQuantizedModelsAllowed(true)
            GpuDelegate(options)
        }
        return try {
            rewindModel()
            val interpreter = Interpreter(modelBuffer, Interpreter.Options().addDelegate(delegate))
            validate(interpreter)
            OpenBackend(interpreter, Backend.GPU) { delegate.close() }
        } catch (error: Throwable) {
            runCatching { delegate.close() }
            throw error
        }
    }

    private fun openNnapi(): OpenBackend {
        val delegate = NnApiDelegate()
        return try {
            rewindModel()
            val interpreter = Interpreter(modelBuffer, Interpreter.Options().addDelegate(delegate))
            validate(interpreter)
            OpenBackend(interpreter, Backend.NNAPI) { delegate.close() }
        } catch (error: Throwable) {
            delegate.close()
            throw error
        }
    }

    private fun openCpu(): OpenBackend {
        rewindModel()
        val options = Interpreter.Options()
            .setNumThreads(cpuThreads.coerceIn(1, 8))
            .setUseXNNPACK(true)
        val interpreter = Interpreter(modelBuffer, options)
        validate(interpreter)
        return OpenBackend(interpreter, Backend.CPU, delegateCloser = null)
    }

    private fun rewindModel() {
        modelBuffer.position(0)
        modelBuffer.limit(modelBuffer.capacity())
    }

    private fun validate(interpreter: Interpreter) {
        val input = interpreter.getInputTensorFromSignature(NsfwMath.INPUT_NAME, NsfwMath.SIGNATURE)
        check(input.dataType() == DataType.FLOAT32) { "NSFW input type is ${input.dataType()}" }
        check(
            input.shape().contentEquals(intArrayOf(1, 3, NsfwMath.INPUT_SIZE, NsfwMath.INPUT_SIZE)),
        ) { "NSFW input shape is ${input.shape().contentToString()}" }
        val outputTensor = interpreter.getOutputTensorFromSignature(NsfwMath.OUTPUT_NAME, NsfwMath.SIGNATURE)
        check(outputTensor.dataType() == DataType.FLOAT32) { "NSFW output type is ${outputTensor.dataType()}" }
        check(outputTensor.shape().contentEquals(intArrayOf(1, 2))) {
            "NSFW output shape is ${outputTensor.shape().contentToString()}"
        }
    }

    private fun logOnce(backend: Backend) {
        if (logged) return
        logged = true
        Log.i(TAG, "Marqo NSFW model is running on ${backend.wireName}")
    }

    private enum class Backend(val wireName: String) {
        GPU("gpu"),
        NNAPI("nnapi"),
        CPU("cpu"),
    }

    private class OpenBackend(
        val interpreter: Interpreter,
        val backend: Backend,
        private val delegateCloser: (() -> Unit)?,
    ) : AutoCloseable {
        override fun close() {
            try {
                interpreter.close()
            } finally {
                delegateCloser?.invoke()
            }
        }
    }

    private companion object {
        const val TAG = "GalleryDetection"
    }
}
