package com.useinoski.gallerycleanup.review

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The grid. [thumbnails] and [sdkInt] have defaults at the route; tests pass
 * a fake loader and an SDK level so API 30 and API 31 can both be drawn here.
 *
 * Tap-to-reveal lives in this composable, not in the ViewModel. It is
 * `remember`, not `rememberSaveable`, so it is dropped when the user leaves
 * the screen. A revealed sensitive photo is hidden again on the next visit.
 * Rotation also drops it, which keeps a revealed photo from staying on screen.
 * Selection is the opposite: the ViewModel stores it in a [androidx.lifecycle.SavedStateHandle],
 * which is the ViewModel form of `rememberSaveable`.
 *
 * A long-press starts selection. While [selectedKeys] is not empty, a tap
 * toggles the cell instead of revealing it. A selected sensitive cell is
 * drawn blurred even if it was revealed a moment ago.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(
    category: ReviewCategory,
    state: ReviewUiState,
    onBack: () -> Unit,
    thumbnails: ThumbnailLoader,
    sdkInt: Int = Build.VERSION.SDK_INT,
    selectedKeys: Set<String> = emptySet(),
    onStartSelection: (ReviewItem) -> Unit = {},
    onToggleSelection: (ReviewItem) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onKeep: () -> Unit = {},
    keepNotice: KeepNotice? = null,
    onUndoKeep: () -> Unit = {},
    onDismissKeepNotice: () -> Unit = {},
) {
    // remember(category) resets the set when the user opens a different
    // category without this composable leaving the tree. Leaving the tree
    // drops it either way. The set is not saved.
    var revealed by remember(category.source) { mutableStateOf(emptySet<String>()) }
    val selecting = selectedKeys.isNotEmpty()
    val name = stringResource(category.titleRes)
    val title = if (selecting) {
        stringResource(R.string.review_selected, selectedKeys.size)
    } else {
        when (state) {
            ReviewUiState.Loading -> name
            ReviewUiState.Empty -> stringResource(R.string.review_title_count, name, 0)
            is ReviewUiState.Items -> stringResource(R.string.review_title_count, name, state.items.size)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    KeepSnackbar(keepNotice, snackbarHostState, onUndoKeep, onDismissKeepNotice)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(text = title, modifier = Modifier.semantics { heading() })
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.review_back))
                        }
                    },
                )
                if (selecting) {
                    SelectionActions(
                        onSelectAll = onSelectAll,
                        onClearSelection = onClearSelection,
                        onKeep = onKeep,
                    )
                }
            }
        },
    ) { padding ->
        when (state) {
            ReviewUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.review_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            ReviewUiState.Empty -> Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.review_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ReviewUiState.Items -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.items, key = { item -> item.stableKey }) { item ->
                    val selected = item.stableKey in selectedKeys
                    ReviewThumbnail(
                        item = item,
                        revealed = item.stableKey in revealed && !selected,
                        selected = selected,
                        selectionMode = selecting,
                        loader = thumbnails,
                        sdkInt = sdkInt,
                        onToggleReveal = {
                            revealed = if (item.stableKey in revealed) {
                                revealed - item.stableKey
                            } else {
                                revealed + item.stableKey
                            }
                        },
                        onToggleSelection = { onToggleSelection(item) },
                        onStartSelection = { onStartSelection(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActions(
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onKeep: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onSelectAll) {
            Text(stringResource(R.string.review_select_all))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClearSelection) {
                Text(stringResource(R.string.review_clear))
            }
            Button(onClick = onKeep) {
                Text(stringResource(R.string.review_keep))
            }
        }
    }
}

/**
 * Shows "N kept" until the user taps Undo or swipes the bar away.
 *
 * [SnackbarDuration.Indefinite] has no timer. A short bar is easy to miss
 * after a bulk keep, and Undo would be gone with it. A new [notice] replaces
 * the previous one because [LaunchedEffect] restarts when the key changes.
 * Restarting cancels [SnackbarHostState.showSnackbar]; that cancellation is
 * not an Undo and not a dismiss.
 */
@Composable
private fun KeepSnackbar(
    notice: KeepNotice?,
    hostState: SnackbarHostState,
    onUndoKeep: () -> Unit,
    onDismissKeepNotice: () -> Unit,
) {
    val message = notice?.let { stringResource(R.string.review_kept, it.count) }
    val undo = stringResource(R.string.review_undo)
    LaunchedEffect(notice) {
        val text = message ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = text,
            actionLabel = undo,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoKeep() else onDismissKeepNotice()
    }
}
