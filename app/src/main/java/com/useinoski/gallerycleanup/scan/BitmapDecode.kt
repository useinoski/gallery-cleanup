package com.useinoski.gallerycleanup.scan

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a content URI to a software bitmap whose long side is at most
 * [MAX_LONG_SIDE_PX].
 *
 * [ImageDecoder.setTargetSize] asks the codec to decode the smaller size,
 * so a 12 megapixel photo is not allocated whole. ImageDecoder applies EXIF
 * orientation by default. The detectors require an upright bitmap and have
 * no rotation argument of their own.
 *
 * [shrinkIfStillTooBig] is the backstop when the header size was missing or
 * the orientation swap left a side over the cap. Two images in flight at
 * 640px is a few megabytes, which is the point of the cap on an 8 GB phone.
 */
internal object BitmapDecode {
    const val MAX_LONG_SIDE_PX = 640

    fun targetSize(width: Int, height: Int, maxLongSide: Int = MAX_LONG_SIDE_PX): Pair<Int, Int> {
        require(width > 0 && height > 0) { "bitmap is empty" }
        require(maxLongSide > 0) { "maxLongSide must be positive" }
        val longSide = max(width, height)
        if (longSide <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / longSide.toDouble()
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun decodeDownscaled(source: ImageDecoder.Source, maxLongSidePx: Int = MAX_LONG_SIDE_PX): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val headerWidth = info.size.width
            val headerHeight = info.size.height
            if (headerWidth > 0 && headerHeight > 0) {
                val (width, height) = targetSize(headerWidth, headerHeight, maxLongSidePx)
                decoder.setTargetSize(width, height)
            }
        }
        return shrinkIfStillTooBig(decoded, maxLongSidePx)
    }

    fun shrinkIfStillTooBig(bitmap: Bitmap, maxLongSide: Int = MAX_LONG_SIDE_PX): Bitmap {
        val (width, height) = targetSize(bitmap.width, bitmap.height, maxLongSide)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        val scaled = bitmap.scale(width, height, filter = true)
        if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return scaled
    }
}

internal class ContentUriBitmapDecoder(
    private val resolver: ContentResolver,
    private val maxLongSidePx: Int = BitmapDecode.MAX_LONG_SIDE_PX,
) {
    fun decode(uri: String): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri.toUri())
        return BitmapDecode.decodeDownscaled(source, maxLongSidePx)
    }
}
