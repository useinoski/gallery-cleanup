package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.CategoryRule
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItemMetadata
import com.useinoski.gallerycleanup.model.MediaType

/**
 * Folder names that mean "this directory is screenshots".
 *
 * Comparison is the whole segment, ignoring case. `Screenshots` is the
 * English name and the name many German Samsung phones still use.
 * `Bildschirmfotos` is the German name (Pixel and other AOSP phones).
 * Add another locale here. Do not add it at a call site.
 *
 * `screenshot_ideas` is not in this list, so that folder does not match.
 * A segment has to equal one of these names. `ScreenshotsBackup` does not
 * match either.
 */
val screenshotFolderNames: List<String> = listOf(
    "Screenshots",
    "Bildschirmfotos",
)

/**
 * Samsung names a screenshot `Screenshot_20250101-101530.jpg`, including
 * when the file sits in `DCIM/Camera`. The match is this prefix, ignoring
 * case. `screenshot_ideas.jpg` therefore matches the file rule. A camera
 * file `IMG_...jpg` or `20250101_...jpg` does not.
 */
internal const val SCREENSHOT_FILE_PREFIX: String = "Screenshot"

/**
 * Images whose folder is a screenshot directory, or whose file name starts
 * with [SCREENSHOT_FILE_PREFIX].
 *
 * Videos and audio in a Screenshots folder are not this category. A camera
 * photo of a screen (`DCIM/Camera/IMG_...jpg`) is not, either. The rule
 * returns [MediaCategory.Screenshots] or an empty set. It does not write
 * MediaStore and it does not remove the file.
 */
object ScreenshotRule : CategoryRule {
    override fun categorize(item: MediaItemMetadata): Set<MediaCategory> {
        if (item.type != MediaType.Image) return emptySet()
        val matched = when {
            item.relativePath.pathSegments().any { segment -> segment.isScreenshotFolder() } -> true
            item.displayName.isScreenshotFileName() -> true
            else -> false
        }
        return if (matched) setOf(MediaCategory.Screenshots) else emptySet()
    }
}

private fun String.isScreenshotFolder(): Boolean =
    screenshotFolderNames.any { name -> equals(name, ignoreCase = true) }

private fun String?.isScreenshotFileName(): Boolean {
    val name = this?.trim().orEmpty()
    return name.startsWith(SCREENSHOT_FILE_PREFIX, ignoreCase = true)
}
