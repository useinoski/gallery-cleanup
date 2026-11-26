package com.useinoski.gallerycleanup.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the grid for one category.
 *
 * [pending] is [com.useinoski.gallerycleanup.data.RoomScanResultStore.observePending].
 * That flow is cold: nothing is queried until a collector starts. A Java
 * listener would be registered in `onStart` and forgotten on rotation.
 * A [StateFlow] always has a current value, so a new collector (the screen
 * after rotation) receives the latest list immediately.
 *
 * [stateIn] turns the Room [Flow] into that [StateFlow]. `map` runs on each
 * emission, not once: every new list from the database becomes a new
 * [ReviewUiState]. [SharingStarted.WhileSubscribed] starts the query when
 * the screen collects [uiState], and stops it [STOP_TIMEOUT_MILLIS] after
 * the last collector leaves. [ReviewUiState.Loading] is the value until the
 * first list arrives. [viewModelScope] cancels this when the ViewModel is
 * cleared, so there is no listener to unregister in `onDestroy`.
 *
 * This type does not mark rows kept or trashed, and it does not delete.
 */
internal class ReviewViewModel(
    category: ScanCategory,
    pending: Flow<List<ScanResult>>,
    started: SharingStarted = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
) : ViewModel() {

    val uiState: StateFlow<ReviewUiState> = pending
        .map { rows -> rows.toReviewUiState(category) }
        .stateIn(
            scope = viewModelScope,
            started = started,
            initialValue = ReviewUiState.Loading,
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

internal fun reviewViewModelFactory(
    category: ScanCategory,
    pending: Flow<List<ScanResult>>,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReviewViewModel(category, pending) as T
        }
        error("Unknown ViewModel: $modelClass")
    }
}
