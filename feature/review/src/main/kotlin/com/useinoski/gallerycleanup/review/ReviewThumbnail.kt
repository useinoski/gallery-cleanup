package com.useinoski.gallerycleanup.review

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads one thumbnail. A `fun interface` is a one-method type, the same job
 * as a Java SAM interface: a test can pass a lambda instead of a class.
 *
 * The production implementation reads MediaStore through [ContentResolver].
 * It does not download anything.
 */
internal fun interface ThumbnailLoader {
    suspend fun load(contentUri: String, sizePx: Int): Bitmap?
}

internal class ContentResolverThumbnailLoader(
    private val resolver: ContentResolver,
) : ThumbnailLoader {
    override suspend fun load(contentUri: String, sizePx: Int): Bitmap? {
        val uri = Uri.parse(contentUri)
        val edge = sizePx.coerceAtLeast(1)
        return withContext(Dispatchers.IO) {
            try {
                // API 29. minSdk is 30, so this is the supported thumbnail
                // call. It reads the local MediaStore row for [uri].
                resolver.loadThumbnail(uri, Size(edge, edge), null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * How a cell is drawn. Tests read this from semantics; the cell's `when`
 * is the only thing that sets it, and the only thing that picks the bitmap.
 */
internal enum class ThumbnailPrivacy {
    /** The thumbnail, sharp. Non-sensitive items, and a sensitive item the user tapped. */
    Clear,

    /** [Modifier.blur]. API 31 and newer. */
    Blurred,

    /** A heavily downscaled bitmap. [Modifier.blur] does nothing useful on API 30. */
    Obscured,

    /** No pixels yet, or the load failed. An opaque box and a hidden icon. Never the photo. */
    Hidden,
}

internal val ThumbnailPrivacyKey = SemanticsPropertyKey<ThumbnailPrivacy>("ThumbnailPrivacy")

internal var SemanticsPropertyReceiver.thumbnailPrivacy by ThumbnailPrivacyKey

internal fun thumbnailPrivacy(
    sensitive: Boolean,
    revealed: Boolean,
    hasBitmap: Boolean,
    sdkInt: Int,
): ThumbnailPrivacy = when {
    !sensitive || revealed -> ThumbnailPrivacy.Clear
    !hasBitmap -> ThumbnailPrivacy.Hidden
    sdkInt >= Build.VERSION_CODES.S -> ThumbnailPrivacy.Blurred
    else -> ThumbnailPrivacy.Obscured
}

/**
 * Makes [source] unrecognizable on API 30, where [Modifier.blur] is a no-op.
 *
 * The bitmap is split into [cells] by [cells]. Each block is painted with
 * the average color of the pixels it covers, which is a heavy downscale:
 * edges and faces do not survive. A hardware bitmap cannot be read; it is
 * copied first. [source] is not recycled. The caller may still be drawing it.
 */
internal fun pixelate(source: Bitmap, cells: Int = PIXEL_CELLS): Bitmap {
    val edge = cells.coerceAtLeast(1)
    val software = when (source.config) {
        Bitmap.Config.HARDWARE, null -> source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        else -> source
    }
    try {
        return blockAverage(software, edge)
    } finally {
        if (software !== source && !software.isRecycled) software.recycle()
    }
}

private fun blockAverage(source: Bitmap, edge: Int): Bitmap {
    val width = source.width.coerceAtLeast(1)
    val height = source.height.coerceAtLeast(1)
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val outW = width.coerceAtLeast(edge)
    val outH = height.coerceAtLeast(edge)
    val out = IntArray(outW * outH)
    for (cy in 0 until edge) {
        val y0 = cy * height / edge
        val y1 = ((cy + 1) * height / edge).coerceAtLeast(y0 + 1).coerceAtMost(height)
        val oy0 = cy * outH / edge
        val oy1 = ((cy + 1) * outH / edge).coerceAtLeast(oy0 + 1).coerceAtMost(outH)
        for (cx in 0 until edge) {
            val x0 = cx * width / edge
            val x1 = ((cx + 1) * width / edge).coerceAtLeast(x0 + 1).coerceAtMost(width)
            var a = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            for (y in y0 until y1) {
                val row = y * width
                for (x in x0 until x1) {
                    val color = pixels[row + x]
                    a += (color ushr 24) and 0xFF
                    r += (color ushr 16) and 0xFF
                    g += (color ushr 8) and 0xFF
                    b += color and 0xFF
                    count += 1
                }
            }
            val averaged = (
                (a / count) shl 24 or
                    ((r / count) shl 16) or
                    ((g / count) shl 8) or
                    (b / count)
                ).toInt()
            val ox0 = cx * outW / edge
            val ox1 = ((cx + 1) * outW / edge).coerceAtLeast(ox0 + 1).coerceAtMost(outW)
            for (y in oy0 until oy1) {
                val row = y * outW
                for (x in ox0 until ox1) {
                    out[row + x] = averaged
                }
            }
        }
    }
    val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, outW, 0, 0, outW, outH)
    return result
}

private const val PIXEL_CELLS = 8

@Composable
internal fun ReviewThumbnail(
    item: ReviewItem,
    revealed: Boolean,
    loader: ThumbnailLoader,
    sdkInt: Int,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(item.contentUri) { mutableStateOf<Bitmap?>(null) }
    val sizePx = 256
    LaunchedEffect(item.contentUri) {
        bitmap = loader.load(item.contentUri, sizePx)
    }
    val privacy = thumbnailPrivacy(
        sensitive = item.sensitive,
        revealed = revealed,
        hasBitmap = bitmap != null,
        sdkInt = sdkInt,
    )
    val description = when (privacy) {
        ThumbnailPrivacy.Clear -> stringResource(R.string.review_photo)
        ThumbnailPrivacy.Blurred,
        ThumbnailPrivacy.Obscured,
        ThumbnailPrivacy.Hidden,
        -> stringResource(R.string.review_hidden_photo)
    }
    val click = if (item.sensitive) {
        Modifier.clickable(
            onClickLabel = stringResource(
                if (revealed) R.string.review_hide else R.string.review_show,
            ),
            onClick = onToggleReveal,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(item.testTag)
            .semantics {
                contentDescription = description
                thumbnailPrivacy = privacy
            }
            .then(click),
        contentAlignment = Alignment.Center,
    ) {
        when (privacy) {
            ThumbnailPrivacy.Clear -> bitmap?.let { sharp ->
                ThumbnailImage(sharp, Modifier.fillMaxSize())
            }
            ThumbnailPrivacy.Blurred -> bitmap?.let { sharp ->
                ThumbnailImage(sharp, Modifier.fillMaxSize().privacyBlur())
            }
            ThumbnailPrivacy.Obscured -> bitmap?.let { sharp ->
                val blocked = remember(sharp) { pixelate(sharp) }
                ThumbnailImage(blocked, Modifier.fillMaxSize())
            }
            ThumbnailPrivacy.Hidden -> Icon(
                painter = painterResource(R.drawable.ic_review_hidden),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun ThumbnailImage(bitmap: Bitmap, modifier: Modifier) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * [Modifier.blur] is a no-op below API 31, and calling the platform blur
 * from an older SDK is a lint error. The cell only uses this modifier from
 * the API 31 branch; the check keeps that call legal if the branch is wrong.
 */
private fun Modifier.privacyBlur(): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return blur(radius = 28.dp, edgeTreatment = BlurredEdgeTreatment.Rectangle)
}
