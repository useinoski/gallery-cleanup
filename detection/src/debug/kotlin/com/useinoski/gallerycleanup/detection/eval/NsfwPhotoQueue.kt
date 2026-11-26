package com.useinoski.gallerycleanup.detection.eval

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos this app is already allowed to read.
 *
 * The projection is the id column only. Display names and relative paths are
 * not loaded, so they cannot end up in a log or a label file. The content
 * URI is rebuilt from the id when a bitmap is needed. Callers must not log
 * the URI or an exception from [decode]: ImageDecoder messages can include it.
 *
 * This query lives in the debug source set on purpose. The shipping scan
 * reads MediaStore through `:core:media`. The labeler must not add a table
 * or a query to that module, and it must not exist in a release build.
 */
internal class NsfwPhotoQueue(
    private val resolver: ContentResolver,
) {
    fun imageIds(): List<Long> {
        val query = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns._ID} ASC",
            )
        }
        resolver.query(collectionUri(), arrayOf(MediaStore.MediaColumns._ID), query, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val ids = ArrayList<Long>(cursor.count.coerceAtLeast(0))
                while (cursor.moveToNext()) {
                    ids += cursor.getLong(idColumn)
                }
                return ids
            }
        return emptyList()
    }

    fun decode(mediaId: Long, maxLongSidePx: Int = MAX_LONG_SIDE_PX): Bitmap {
        val uri = ContentUris.withAppendedId(collectionUri(), mediaId)
        val source = ImageDecoder.createSource(resolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val headerWidth = info.size.width
            val headerHeight = info.size.height
            if (headerWidth > 0 && headerHeight > 0) {
                val (width, height) = labelerTargetSize(headerWidth, headerHeight, maxLongSidePx)
                decoder.setTargetSize(width, height)
            }
        }
        val (width, height) = labelerTargetSize(decoded.width, decoded.height, maxLongSidePx)
        if (width == decoded.width && height == decoded.height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
        return scaled
    }

    private fun collectionUri(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    companion object {
        const val MAX_LONG_SIDE_PX = 1280
    }
}

/**
 * Long side capped at [maxLongSide]. ImageDecoder applies EXIF orientation
 * before this runs, which is what the detector requires: an upright bitmap.
 */
internal fun labelerTargetSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "bitmap is empty" }
    require(maxLongSide > 0) { "maxLongSide must be positive" }
    val longSide = max(width, height)
    if (longSide <= maxLongSide) return width to height
    val scale = maxLongSide.toDouble() / longSide.toDouble()
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/**
 * A downscale followed by an upscale. Enough that a thumbnail is not
 * recognizable until the screen swaps in the sharp bitmap. The source is
 * left for that swap; this returns a new bitmap.
 */
internal fun blurForLabel(source: Bitmap): Bitmap {
    val longSide = max(source.width, source.height).coerceAtLeast(1)
    val scale = TINY_LONG_SIDE.toFloat() / longSide.toFloat()
    val width = max(1, (source.width * scale).roundToInt()).coerceAtMost(source.width)
    val height = max(1, (source.height * scale).roundToInt()).coerceAtMost(source.height)
    val tiny = Bitmap.createScaledBitmap(source, width, height, true)
    val blurred = Bitmap.createScaledBitmap(tiny, source.width, source.height, true)
    if (tiny !== source && tiny !== blurred && !tiny.isRecycled) tiny.recycle()
    return blurred
}

private const val TINY_LONG_SIDE = 16
