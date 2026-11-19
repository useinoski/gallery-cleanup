package com.useinoski.gallerycleanup.detection.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rectangle passed to the Marqo model.
 *
 * The Hugging Face config sets `crop_pct` to 1.0 and `crop_mode` to `center`.
 * timm's eval transform then resizes the short side to 384 and center-crops
 * a square. With a crop percentage of 1 that is the same as taking the largest
 * centered square in the original photo and scaling that square to 384.
 * A landscape photo loses the left and right edges. It is not stretched.
 */
internal data class PixelRect(val left: Int, val top: Int, val side: Int)

internal fun centerSquareRect(width: Int, height: Int): PixelRect {
    require(width > 0 && height > 0) { "bitmap is empty" }
    val side = min(width, height)
    return PixelRect(
        left = (width - side) / 2,
        top = (height - side) / 2,
        side = side,
    )
}

internal data class ScaledSize(val width: Int, val height: Int)

/**
 * Shrinks so the long side is at most [maxLongSide], keeping the aspect ratio.
 * Returns the original size when the photo is already small enough.
 */
internal fun sizeCappedToLongSide(width: Int, height: Int, maxLongSide: Int): ScaledSize {
    require(width > 0 && height > 0) { "bitmap is empty" }
    require(maxLongSide > 0) { "maxLongSide must be positive" }
    val longSide = max(width, height)
    if (longSide <= maxLongSide) return ScaledSize(width, height)
    val scale = maxLongSide.toDouble() / longSide.toDouble()
    var scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    var scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    if (scaledWidth > maxLongSide) scaledWidth = maxLongSide
    if (scaledHeight > maxLongSide) scaledHeight = maxLongSide
    return ScaledSize(scaledWidth, scaledHeight)
}
