package com.useinoski.gallerycleanup.review

import com.useinoski.gallerycleanup.data.ScanCategory

/**
 * What the grid needs from a stored category: a title, and whether the
 * thumbnail starts hidden.
 *
 * [source] is still [ScanCategory]. Callers outside this module pass that
 * enum into [ReviewRoute]. This type is the only one the screen reads.
 */
internal data class ReviewCategory(
    val source: ScanCategory,
    val titleRes: Int,
    val sensitive: Boolean,
)

/**
 * The one place this module reads [ScanCategory].
 *
 * [ScanCategory.Sensitive] is the nudity / NSFW bucket. Its KDoc is what
 * marks those thumbnails to start blurred. [ScanCategory.Immodest] is
 * suggestive and stays sharp. A later MediaCategory (after the type lands)
 * should replace this function and the parameter of [ReviewRoute]. The grid
 * does not switch on the enum itself.
 */
internal fun ScanCategory.toReviewCategory(): ReviewCategory = when (this) {
    ScanCategory.Sensitive -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_nudity,
        sensitive = true,
    )
    ScanCategory.Immodest -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_suggestive,
        sensitive = false,
    )
    ScanCategory.AlcoholGambling -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_alcohol_gambling,
        sensitive = false,
    )
    ScanCategory.People -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_people,
        sensitive = false,
    )
    ScanCategory.Screenshot -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_screenshots,
        sensitive = false,
    )
    ScanCategory.Video -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_videos,
        sensitive = false,
    )
    ScanCategory.Audio -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_audio,
        sensitive = false,
    )
    ScanCategory.Unflagged -> ReviewCategory(
        source = this,
        titleRes = R.string.review_category_unflagged,
        sensitive = false,
    )
}
