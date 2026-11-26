package com.useinoski.gallerycleanup.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.roomScanResultStore

/**
 * Pending items in [category], newest scan first.
 *
 * This is the only public entry to the review screen. The grid, the
 * ViewModel, and the thumbnail loader stay inside this module. Nothing
 * here deletes a file or marks a row kept or trashed.
 *
 * [category] is [ScanCategory] until MediaCategory exists. One function in
 * this module ([ScanCategory.toReviewCategory]) turns it into a title and
 * the blur decision. That function and this parameter are the switch.
 *
 * [onBack] is the caller's job. This screen does not know what sits under it.
 */
@Composable
fun ReviewRoute(
    category: ScanCategory,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val reviewCategory = remember(category) { category.toReviewCategory() }
    // observePending is cold. remember keeps one flow for this category so
    // recomposition does not start a second query. The ViewModel collects it.
    val pending = remember(appContext, category) {
        roomScanResultStore(appContext).observePending(category)
    }
    val viewModel: ReviewViewModel = viewModel(
        key = "review-${category.name}",
        factory = reviewViewModelFactory(category, pending),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val loader = remember(appContext) {
        ContentResolverThumbnailLoader(appContext.contentResolver)
    }
    ReviewScreen(
        category = reviewCategory,
        state = state,
        onBack = onBack,
        thumbnails = loader,
    )
}
