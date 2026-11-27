package com.useinoski.gallerycleanup.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.useinoski.gallerycleanup.data.RoomScanResultStore
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.roomScanResultStore
import com.useinoski.gallerycleanup.model.MediaKey

/**
 * Pending items in [category], newest scan first.
 *
 * This is the only public entry to the review screen. The grid, the
 * ViewModel, and the thumbnail loader stay inside this module. Keep writes
 * [RoomScanResultStore.markKept]. Nothing here trashes a row or deletes a file.
 *
 * [category] stays [ScanCategory]. [ScanCategory.toReviewCategory] is the one
 * mapping to a title and the blur decision. MediaCategory is a later switch.
 *
 * [onBack] is the caller's job. The first back while something is selected
 * clears that selection. The next back calls [onBack].
 *
 * Entering this composable sets [WindowManager.LayoutParams.FLAG_SECURE] on
 * the host window and clears it on dispose, so Home stays screenshot-able.
 */
@Composable
fun ReviewRoute(
    category: ScanCategory,
    onBack: () -> Unit,
) {
    SecureReviewWindow()
    val appContext = LocalContext.current.applicationContext
    val reviewCategory = remember(category) { category.toReviewCategory() }
    val store = remember(appContext) { roomScanResultStore(appContext) }
    // observePending is cold. remember keeps one flow for this category so
    // recomposition does not start a second query. The ViewModel collects it.
    val pending = remember(store, category) { store.observePending(category) }
    val decisions = remember(store) { StoreReviewDecisions(store) }
    val viewModel: ReviewViewModel = viewModel(
        key = "review-${category.name}",
        factory = reviewViewModelFactory(category, pending, decisions),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val storedSelection by viewModel.selection.collectAsStateWithLifecycle()
    val notice by viewModel.keepNotice.collectAsStateWithLifecycle()
    val selectedKeys = visibleSelection(state, storedSelection)
    // The review screen is not its own activity. The system back gesture and
    // the on-screen Back button both call this. Selection is cleared first.
    val onLeave = {
        if (!viewModel.consumeBack()) onBack()
    }
    BackHandler(onBack = onLeave)
    val loader = remember(appContext) {
        ContentResolverThumbnailLoader(appContext.contentResolver)
    }
    ReviewScreen(
        category = reviewCategory,
        state = state,
        onBack = onLeave,
        thumbnails = loader,
        selectedKeys = selectedKeys,
        onStartSelection = viewModel::startSelection,
        onToggleSelection = viewModel::toggleSelection,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onKeep = viewModel::keepSelected,
        keepNotice = notice,
        onUndoKeep = viewModel::undoKeep,
        onDismissKeepNotice = viewModel::dismissKeepNotice,
    )
}

/**
 * Blocks screenshots and the recents thumbnail while the review grid is open.
 *
 * [DisposableEffect] is the Compose form of a `try/finally` tied to the
 * composition. The block runs when this composable enters the tree. `onDispose`
 * runs when [ReviewRoute] leaves the tree (the user goes back to Home) or
 * when [activity] changes. A Java view would do the same pair of calls in
 * `onStart` and `onStop`. Home is a different composition, so its window is
 * screenshot-able again once this effect disposes.
 *
 * [LocalActivity] is the Activity hosting the composable. When that local is
 * empty, [findHostActivity] walks [ContextWrapper] the way Java code finds
 * the Activity from a `View`'s context.
 */
@Composable
private fun SecureReviewWindow() {
    val activity = LocalActivity.current ?: LocalContext.current.findHostActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private fun Context.findHostActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** [RoomScanResultStore.markKept] and [RoomScanResultStore.markPending]. */
private class StoreReviewDecisions(
    private val store: RoomScanResultStore,
) : ReviewDecisions {
    override suspend fun markKept(keys: Collection<MediaKey>) {
        store.markKept(keys)
    }

    override suspend fun markPending(keys: Collection<MediaKey>) {
        store.markPending(keys)
    }
}
