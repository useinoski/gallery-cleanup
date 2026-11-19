package com.useinoski.gallerycleanup.detection.nsfw

import android.content.Context
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Loads a `.tflite` asset into a direct [ByteBuffer], which is what the
 * LiteRT interpreter accepts.
 *
 * `openFd` memory-maps the file, but only when the APK stored it uncompressed.
 * A compressed asset throws, and we then read the bytes into a direct buffer.
 * Either buffer stays alive for as long as the interpreter does.
 */
internal fun loadModelBuffer(context: Context, assetName: String): ByteBuffer {
    try {
        context.assets.openFd(assetName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    } catch (compressedOrMissing: IOException) {
        context.assets.open(assetName).use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) throw compressedOrMissing
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes)
            buffer.position(0)
            return buffer
        }
    }
}
