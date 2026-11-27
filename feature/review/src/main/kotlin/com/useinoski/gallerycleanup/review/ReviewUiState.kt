package com.useinoski.gallerycleanup.review

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.ReviewStatus
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.model.MediaType

/**
 * What the review screen draws.
 *
 * A sealed interface is a closed set, the same idea as a Java abstract class
 * that only a few known types extend. `when (state)` is exhaustive: adding a
 * case without updating the screen is a compile error. There is no `else`
 * that would quietly ignore a new state.
 *
 * [Loading], [Empty], and [Items] are the three cases. A `data object` is one
 * singleton case, like an enum constant that carries no fields. [Items] is a
 * `data class`, so two lists with the same rows compare equal.
 */
internal sealed interface ReviewUiState {
    /** Before the first database emission. Not the same as an empty category. */
    data object Loading : ReviewUiState

    /** The query ran. Nothing in this category is still pending. */
    data object Empty : ReviewUiState

    /** Newest scan first, which is the order [com.useinoski.gallerycleanup.data.RoomScanResultStore.observePending] emits. */
    data class Items(val items: List<ReviewItem>) : ReviewUiState
}

/**
 * One cell in the grid. The file bytes are not here; [contentUri] is loaded
 * from MediaStore when the cell is on screen.
 */
internal data class ReviewItem(
    val id: Long,
    val mediaType: MediaType,
    val contentUri: String,
    val sensitive: Boolean,
)

/** Image 5 and video 5 are different files. The id alone is not a key. */
internal val ReviewItem.stableKey: String
    get() = "${mediaType.name}:$id"

internal val ReviewItem.testTag: String
    get() = "review-item-${mediaType.name}-$id"

/** The check drawn on top of a selected cell. Not the photo. */
internal val ReviewItem.checkTag: String
    get() = "review-check-${mediaType.name}-$id"

/**
 * Turns one emission into the grid.
 *
 * [com.useinoski.gallerycleanup.data.RoomScanResultStore.observePending]
 * already drops kept, trashed, failed, and removed rows. Status, outcome,
 * and category are checked again so a row that does not belong on this
 * screen is not drawn if it shows up in the list. Removed is not a field
 * on [ScanResult]: that file is simply absent, and the next emission
 * without it is what takes the cell off the grid. Order is kept. The query
 * sorts newest scan first; this function does not sort again.
 */
internal fun List<ScanResult>.toReviewUiState(category: ScanCategory): ReviewUiState {
    val reviewCategory = category.toReviewCategory()
    val items = mapNotNull { result -> result.toReviewItemOrNull(reviewCategory) }
    return if (items.isEmpty()) ReviewUiState.Empty else ReviewUiState.Items(items)
}

private fun ScanResult.toReviewItemOrNull(category: ReviewCategory): ReviewItem? {
    if (this.category != category.source) return null
    if (reviewStatus != ReviewStatus.Pending) return null
    if (analysisOutcome != AnalysisOutcome.Scored) return null
    return ReviewItem(
        id = mediaStoreId,
        mediaType = mediaType,
        contentUri = contentUri,
        sensitive = category.sensitive,
    )
}
