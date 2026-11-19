package com.useinoski.gallerycleanup.detection.image

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * A bitmap this module allocated, or the caller's bitmap when no copy was needed.
 * [close] recycles only bitmaps this module created. The caller's bitmap is never recycled.
 */
internal class OwnedBitmap(
    val bitmap: Bitmap,
    private val recycleOnClose: Boolean,
) : AutoCloseable {
    override fun close() {
        if (recycleOnClose && !bitmap.isRecycled) bitmap.recycle()
    }
}

internal fun requireUsableBitmap(bitmap: Bitmap) {
    require(!bitmap.isRecycled) { "bitmap is recycled" }
    require(bitmap.width > 0 && bitmap.height > 0) { "bitmap is empty" }
}

/**
 * Center-square crop, then scale to [size] x [size].
 *
 * Android's filtered draw is bilinear. The Marqo config asks for bicubic.
 * The difference is small next to a threshold we have not tuned yet, and a
 * hand-written bicubic kernel is easy to get wrong. Large photos are reduced
 * in two filtered steps so a single 12 megapixel sample does not alias as badly.
 */
internal fun scaleCenterSquare(src: Bitmap, size: Int): OwnedBitmap {
    require(size > 0)
    requireUsableBitmap(src)
    val software = copyToArgb8888IfNeeded(src)
    if (software.bitmap.width == size && software.bitmap.height == size) {
        return software
    }
    return try {
        OwnedBitmap(downsampleCenterSquare(software.bitmap, size), recycleOnClose = true)
    } finally {
        software.close()
    }
}

/**
 * Keeps aspect ratio. Used by face count and image labels, which are not square models.
 * Platform [Bitmap] calls are intentional. This module does not depend on core-ktx.
 */
@SuppressLint("UseKtx")
internal fun capLongSide(src: Bitmap, maxLongSide: Int): OwnedBitmap {
    requireUsableBitmap(src)
    val target = sizeCappedToLongSide(src.width, src.height, maxLongSide)
    val software = copyToArgb8888IfNeeded(src)
    if (software.bitmap.width == target.width && software.bitmap.height == target.height) {
        return software
    }
    return try {
        OwnedBitmap(
            downsample(software.bitmap, target.width, target.height),
            recycleOnClose = true,
        )
    } finally {
        software.close()
    }
}

private fun copyToArgb8888IfNeeded(src: Bitmap): OwnedBitmap {
    if (src.config == Bitmap.Config.ARGB_8888) {
        return OwnedBitmap(src, recycleOnClose = false)
    }
    val copy = src.copy(Bitmap.Config.ARGB_8888, false)
        ?: throw IllegalArgumentException("Could not copy bitmap to ARGB_8888")
    return OwnedBitmap(copy, recycleOnClose = true)
}

@SuppressLint("UseKtx")
private fun downsampleCenterSquare(src: Bitmap, size: Int): Bitmap {
    val side = minOf(src.width, src.height)
    if (side > size * 4) {
        val mid = drawCenterSquare(src, size * 2)
        try {
            return drawCenterSquare(mid, size)
        } finally {
            mid.recycle()
        }
    }
    return drawCenterSquare(src, size)
}

@SuppressLint("UseKtx")
private fun drawCenterSquare(src: Bitmap, size: Int): Bitmap {
    val crop = centerSquareRect(src.width, src.height)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    canvas.drawBitmap(
        src,
        Rect(crop.left, crop.top, crop.left + crop.side, crop.top + crop.side),
        Rect(0, 0, size, size),
        paint,
    )
    return out
}

@SuppressLint("UseKtx")
private fun downsample(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    var current = src
    var owned = false
    try {
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            val next = Bitmap.createScaledBitmap(
                current,
                current.width / 2,
                current.height / 2,
                true,
            )
            if (owned) current.recycle()
            current = next
            owned = true
        }
        if (current.width == targetWidth && current.height == targetHeight) {
            check(owned) { "caller should have skipped a no-op scale" }
            return current
        }
        val scaled = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
        if (owned && scaled !== current) current.recycle()
        return scaled
    } catch (error: Throwable) {
        if (owned && !current.isRecycled) current.recycle()
        throw error
    }
}
