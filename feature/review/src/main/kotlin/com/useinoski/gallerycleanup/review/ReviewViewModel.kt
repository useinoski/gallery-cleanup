package com.useinoski.gallerycleanup.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.model.MediaKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Keep and undo. [RoomScanResultStore][com.useinoski.gallerycleanup.data.RoomScanResultStore]
 * implements both. This screen does not trash and does not delete.
 *
 * [markKept] and [markPending] each run as one Room transaction: the store
 * wraps the chunked updates in `withTransaction`. A `@Transaction` DAO method
 * is the same promise when the work is a single DAO call. The store uses
 * `withTransaction` because a large selection is several SQL statements that
 * have to commit together.
 */
internal interface ReviewDecisions {
    suspend fun markKept(keys: Collection<MediaKey>)
    suspend fun markPending(keys: Collection<MediaKey>)
}

/**
 * The keys just marked kept. [count] is what the snackbar prints. Undo sends
 * [keys] back through [ReviewDecisions.markPending].
 */
internal data class KeepNotice(val keys: List<MediaKey>) {
    val count: Int get() = keys.size
}

/**
 * Holds the grid for one category, plus the selection and the keep action.
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
 * Selection is the set of [ReviewItem.stableKey] strings (`Image:5`). That
 * string is a [MediaKey] without making the key [android.os.Parcelable].
 * [SavedStateHandle] is the ViewModel's saved bundle, the same bundle a Java
 * Activity writes in `onSaveInstanceState`. Rotation keeps this ViewModel, so
 * the [StateFlow] is enough for a turn of the phone. Process death builds a
 * new ViewModel and the handle is filled from that bundle. A
 * `rememberSaveable` in the composable writes the same bundle; the handle is
 * used here so selection can be tested without a screen.
 *
 * [keepSelected] writes [ReviewDecisions.markKept] and does not delete.
 */
internal class ReviewViewModel(
    private val category: ScanCategory,
    pending: Flow<List<ScanResult>>,
    private val decisions: ReviewDecisions,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    started: SharingStarted = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
) : ViewModel() {

    val uiState: StateFlow<ReviewUiState> = pending
        .map { rows -> rows.toReviewUiState(category) }
        .stateIn(
            scope = viewModelScope,
            started = started,
            initialValue = ReviewUiState.Loading,
        )

    private val _selection = MutableStateFlow(readSelection())

    /** Stored keys, including any the latest grid emission has not dropped yet. */
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _keepNotice = MutableStateFlow<KeepNotice?>(null)
    val keepNotice: StateFlow<KeepNotice?> = _keepNotice.asStateFlow()

    private var keepInFlight = false

    /** Long-press. Adds [item] and enters selection. Does not toggle it off. */
    fun startSelection(item: ReviewItem) {
        write(visibleNow() + item.stableKey)
    }

    /** Tap while selection is active. Adds [item], or removes it. */
    fun toggleSelection(item: ReviewItem) {
        val current = visibleNow()
        val key = item.stableKey
        write(if (key in current) current - key else current + key)
    }

    /** Every pending cell in this category. Other categories are not in [uiState]. */
    fun selectAll() {
        val items = (uiState.value as? ReviewUiState.Items)?.items ?: return
        write(items.map { item -> item.stableKey }.toSet())
    }

    fun clearSelection() {
        write(emptySet())
    }

    /**
     * Back and the system back gesture.
     *
     * Returns true when a selection was cleared. The caller leaves the screen
     * only when this returns false, so the first back exits selection and the
     * next back returns home.
     */
    fun consumeBack(): Boolean {
        if (visibleNow().isEmpty()) return false
        clearSelection()
        return true
    }

    /**
     * Marks the visible selection kept, in one [ReviewDecisions.markKept] call.
     * The grid drops the rows when [uiState] emits the pending list without them.
     */
    fun keepSelected() {
        if (keepInFlight) return
        val keys = selectedMediaKeys()
        if (keys.isEmpty()) return
        keepInFlight = true
        viewModelScope.launch {
            try {
                decisions.markKept(keys)
                write(emptySet())
                _keepNotice.value = KeepNotice(keys)
            } finally {
                keepInFlight = false
            }
        }
    }

    /** Snackbar action. Puts the last kept keys back to pending. */
    fun undoKeep() {
        val keys = _keepNotice.value?.keys ?: return
        _keepNotice.value = null
        viewModelScope.launch {
            decisions.markPending(keys)
        }
    }

    /** The snackbar was swiped away. The kept rows stay kept. */
    fun dismissKeepNotice() {
        _keepNotice.value = null
    }

    private fun selectedMediaKeys(): List<MediaKey> {
        val selected = visibleNow()
        val items = (uiState.value as? ReviewUiState.Items)?.items.orEmpty()
        return items.filter { item -> item.stableKey in selected }.map { item ->
            MediaKey(type = item.mediaType, id = item.id)
        }
    }

    private fun visibleNow(): Set<String> = visibleSelection(uiState.value, _selection.value)

    private fun write(keys: Set<String>) {
        _selection.value = keys
        // ArrayList<String> is one of the types a Bundle can store. A Kotlin
        // Set is not. The copy is so a later edit of the set cannot change
        // the value already sitting in the handle.
        savedState[SELECTED_KEYS] = ArrayList(keys)
    }

    private fun readSelection(): Set<String> {
        return savedState.get<ArrayList<String>>(SELECTED_KEYS)?.toSet() ?: emptySet()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val SELECTED_KEYS = "review.selectedKeys"
    }
}

/**
 * Keys the grid should draw as selected.
 *
 * While [ReviewUiState.Loading], the saved set is shown as-is: the list has
 * not arrived, and intersecting with an empty grid would wipe a restored
 * selection. After that, keys that are not on screen are hidden. The stored
 * set is what survives rotation; this function is the set the top bar counts.
 */
internal fun visibleSelection(state: ReviewUiState, stored: Set<String>): Set<String> {
    if (state is ReviewUiState.Loading) return stored
    val visible = (state as? ReviewUiState.Items)?.items?.map { item -> item.stableKey }?.toSet().orEmpty()
    return stored.intersect(visible)
}

internal fun reviewViewModelFactory(
    category: ScanCategory,
    pending: Flow<List<ScanResult>>,
    decisions: ReviewDecisions,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReviewViewModel(
                category = category,
                pending = pending,
                decisions = decisions,
                // createSavedStateHandle reads the Activity's SavedStateRegistry
                // out of [extras]. The compose viewModel() call puts that
                // registry there. A Java factory would take the same registry
                // in its constructor.
                savedState = extras.createSavedStateHandle(),
            ) as T
        }
        error("Unknown ViewModel: $modelClass")
    }
}
