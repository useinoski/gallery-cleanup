package com.useinoski.gallerycleanup.review

import androidx.lifecycle.SavedStateHandle
import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.ReviewStatus
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * The ViewModel is given the flow [RoomScanResultStore.observePending] would
 * emit. Kept, trashed, and failed rows are dropped here even if that flow
 * contained them. A removed file never arrives as a row: the query omits it,
 * and the grid follows the next emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule
    val timeout: Timeout = Timeout(15, TimeUnit.SECONDS)

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun keptTrashedFailedAndOtherCategoriesAreExcluded() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.Sensitive),
                row(id = 2, category = ScanCategory.Sensitive, status = ReviewStatus.Kept),
                row(id = 3, category = ScanCategory.Sensitive, status = ReviewStatus.Trashed),
                row(id = 4, category = ScanCategory.Sensitive, outcome = AnalysisOutcome.Failed),
                row(id = 5, category = ScanCategory.Screenshot),
                row(id = 6, category = ScanCategory.Sensitive, type = MediaType.Video),
            ),
        )
        val items = items(viewModel(ScanCategory.Sensitive, upstream))
        assertEquals(
            listOf(1L to MediaType.Image, 6L to MediaType.Video),
            items.map { item -> item.id to item.mediaType },
        )
        assertTrue(items.all { item -> item.sensitive })
    }

    @Test
    fun anEmptyPendingListIsTheEmptyStateNotAStuckLoadingState() = runTest {
        val upstream = MutableSharedFlow<List<ScanResult>>()
        val viewModel = viewModel(ScanCategory.People, upstream)
        assertEquals(ReviewUiState.Loading, viewModel.uiState.value)
        upstream.emit(emptyList())
        assertEquals(ReviewUiState.Empty, viewModel.uiState.value)
    }

    @Test
    fun aLaterEmissionDropsRowsTheQueryNoLongerReturns() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 2, category = ScanCategory.Screenshot, scannedAt = 30),
                row(id = 1, category = ScanCategory.Screenshot, scannedAt = 10),
            ),
        )
        val viewModel = viewModel(ScanCategory.Screenshot, upstream)
        assertEquals(listOf(2L, 1L), ids(viewModel))
        // Kept, trashed, or removed: observePending emits the list without that row.
        upstream.value = listOf(row(id = 1, category = ScanCategory.Screenshot, scannedAt = 10))
        assertEquals(listOf(1L), ids(viewModel))
        upstream.value = emptyList()
        assertEquals(ReviewUiState.Empty, viewModel.uiState.value)
    }

    @Test
    fun aScreenshotIsNotMarkedSensitive() = runTest {
        val upstream = MutableStateFlow(listOf(row(id = 8, category = ScanCategory.Screenshot)))
        val item = items(viewModel(ScanCategory.Screenshot, upstream)).single()
        assertFalse(item.sensitive)
        assertEquals("content://media/external/images/media/8", item.contentUri)
    }

    @Test
    fun longPressSelectsAndTapToggles() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.Screenshot),
                row(id = 2, category = ScanCategory.Screenshot),
            ),
        )
        val viewModel = viewModel(ScanCategory.Screenshot, upstream)
        val first = item(viewModel, 1)
        val second = item(viewModel, 2)
        assertFalse(viewModel.consumeBack())

        viewModel.startSelection(first)
        viewModel.startSelection(first)
        assertEquals(setOf("Image:1"), viewModel.selection.value)

        viewModel.toggleSelection(second)
        assertEquals(setOf("Image:1", "Image:2"), viewModel.selection.value)
        viewModel.toggleSelection(first)
        assertEquals(setOf("Image:2"), viewModel.selection.value)
        viewModel.toggleSelection(second)
        assertTrue(viewModel.selection.value.isEmpty())
    }

    @Test
    fun selectAllTakesTheCurrentCategoryAndClearEmptiesIt() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.People),
                row(id = 2, category = ScanCategory.People, type = MediaType.Video),
                row(id = 2, category = ScanCategory.People),
                row(id = 3, category = ScanCategory.People, status = ReviewStatus.Kept),
                row(id = 4, category = ScanCategory.Screenshot),
            ),
        )
        val viewModel = viewModel(ScanCategory.People, upstream)
        viewModel.selectAll()
        assertEquals(setOf("Image:1", "Video:2", "Image:2"), viewModel.selection.value)

        viewModel.clearSelection()
        assertTrue(viewModel.selection.value.isEmpty())
        assertFalse(viewModel.consumeBack())
    }

    @Test
    fun backClearsSelectionBeforeItLeaves() = runTest {
        val upstream = MutableStateFlow(listOf(row(id = 1, category = ScanCategory.Screenshot)))
        val viewModel = viewModel(ScanCategory.Screenshot, upstream)
        viewModel.startSelection(item(viewModel, 1))
        assertTrue(viewModel.consumeBack())
        assertTrue(viewModel.selection.value.isEmpty())
        assertFalse(viewModel.consumeBack())
    }

    @Test
    fun selectionSurvivesANewViewModelWithTheSameSavedState() = runTest {
        val handle = SavedStateHandle()
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.Screenshot),
                row(id = 2, category = ScanCategory.Screenshot),
            ),
        )
        val first = viewModel(ScanCategory.Screenshot, upstream, savedState = handle)
        first.startSelection(item(first, 1))
        first.toggleSelection(item(first, 2))

        val second = viewModel(ScanCategory.Screenshot, upstream, savedState = handle)
        assertEquals(setOf("Image:1", "Image:2"), second.selection.value)
        assertEquals(setOf("Image:1", "Image:2"), visibleSelection(second.uiState.value, second.selection.value))

        second.clearSelection()
        val third = viewModel(ScanCategory.Screenshot, upstream, savedState = handle)
        assertTrue(third.selection.value.isEmpty())
    }

    @Test
    fun keepWritesKeptDropsTheRowsAndUndoRestoresThem() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.Screenshot),
                row(id = 2, category = ScanCategory.Screenshot),
                row(id = 3, category = ScanCategory.Screenshot),
            ),
        )
        val decisions = RecordingDecisions(upstream)
        val viewModel = viewModel(ScanCategory.Screenshot, upstream, decisions)
        viewModel.startSelection(item(viewModel, 1))
        viewModel.toggleSelection(item(viewModel, 3))
        viewModel.keepSelected()

        assertEquals(1, decisions.kept.size)
        assertEquals(
            listOf(MediaKey(MediaType.Image, 1), MediaKey(MediaType.Image, 3)),
            decisions.kept.single(),
        )
        assertEquals(listOf(2L), ids(viewModel))
        assertTrue(viewModel.selection.value.isEmpty())
        assertEquals(2, (viewModel.keepNotice.value as ReviewNotice.Kept).count)
        assertEquals(ReviewStatus.Kept, upstream.value.first { it.mediaStoreId == 1L }.reviewStatus)
        assertEquals(ReviewStatus.Pending, upstream.value.first { it.mediaStoreId == 2L }.reviewStatus)

        viewModel.undoKeep()
        assertEquals(
            listOf(MediaKey(MediaType.Image, 1), MediaKey(MediaType.Image, 3)),
            decisions.pending.single(),
        )
        assertNull(viewModel.keepNotice.value)
        assertEquals(listOf(1L, 2L, 3L), ids(viewModel))
        assertEquals(ReviewStatus.Pending, upstream.value.first { it.mediaStoreId == 1L }.reviewStatus)
    }

    @Test
    fun keepWorksForASingleSelectedItem() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 8, category = ScanCategory.Sensitive),
                row(id = 9, category = ScanCategory.Sensitive),
            ),
        )
        val decisions = RecordingDecisions(upstream)
        val viewModel = viewModel(ScanCategory.Sensitive, upstream, decisions)
        viewModel.startSelection(item(viewModel, 9))
        viewModel.keepSelected()

        assertEquals(listOf(MediaKey(MediaType.Image, 9)), decisions.kept.single())
        assertEquals(listOf(8L), ids(viewModel))
        assertEquals(1, (viewModel.keepNotice.value as ReviewNotice.Kept).count)
        viewModel.keepSelected()
        assertEquals(1, decisions.kept.size)
    }

    @Test
    fun keepWithNothingSelectedDoesNotWrite() = runTest {
        val upstream = MutableStateFlow(listOf(row(id = 1, category = ScanCategory.Screenshot)))
        val decisions = RecordingDecisions(upstream)
        val viewModel = viewModel(ScanCategory.Screenshot, upstream, decisions)
        viewModel.keepSelected()
        assertTrue(decisions.kept.isEmpty())
        assertNull(viewModel.keepNotice.value)
        assertEquals(listOf(1L), ids(viewModel))
    }

    @Test
    fun aFailedKeepLeavesTheSelectionAndDoesNotShowKept() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 1, category = ScanCategory.Screenshot),
                row(id = 2, category = ScanCategory.Screenshot),
            ),
        )
        val decisions = ThrowingDecisions(upstream, keepError = IllegalStateException("disk full"))
        val viewModel = viewModel(ScanCategory.Screenshot, upstream, decisions)
        viewModel.startSelection(item(viewModel, 1))
        viewModel.toggleSelection(item(viewModel, 2))
        viewModel.keepSelected()

        assertEquals(setOf("Image:1", "Image:2"), viewModel.selection.value)
        assertEquals(ReviewNotice.KeepFailed, viewModel.keepNotice.value)
        assertTrue(viewModel.keepNotice.value !is ReviewNotice.Kept)
        assertEquals(listOf(1L, 2L), ids(viewModel))
        assertTrue(upstream.value.all { row -> row.reviewStatus == ReviewStatus.Pending })
    }

    @Test
    fun aFailedUndoLeavesTheRowsKept() = runTest {
        val upstream = MutableStateFlow(
            listOf(
                row(id = 4, category = ScanCategory.People),
                row(id = 5, category = ScanCategory.People),
            ),
        )
        val decisions = ThrowingDecisions(upstream, undoError = IllegalStateException("disk full"))
        val viewModel = viewModel(ScanCategory.People, upstream, decisions)
        viewModel.startSelection(item(viewModel, 4))
        viewModel.keepSelected()
        assertEquals(listOf(5L), ids(viewModel))
        assertTrue(viewModel.keepNotice.value is ReviewNotice.Kept)

        viewModel.undoKeep()

        assertEquals(ReviewNotice.UndoFailed, viewModel.keepNotice.value)
        assertEquals(listOf(5L), ids(viewModel))
        assertEquals(ReviewStatus.Kept, upstream.value.first { it.mediaStoreId == 4L }.reviewStatus)
        assertTrue(viewModel.selection.value.isEmpty())
    }

    @Test
    fun aCancelledKeepIsNotReportedAsADatabaseFailure() = runTest {
        val upstream = MutableStateFlow(listOf(row(id = 1, category = ScanCategory.Screenshot)))
        val decisions = ThrowingDecisions(upstream, keepError = kotlinx.coroutines.CancellationException("stopped"))
        val viewModel = viewModel(ScanCategory.Screenshot, upstream, decisions)
        viewModel.startSelection(item(viewModel, 1))
        viewModel.keepSelected()

        assertEquals(setOf("Image:1"), viewModel.selection.value)
        assertNull(viewModel.keepNotice.value)
        assertEquals(ReviewStatus.Pending, upstream.value.single().reviewStatus)
    }

    @Test
    fun aCancelledUndoIsNotReportedAsADatabaseFailure() = runTest {
        val upstream = MutableStateFlow(listOf(row(id = 6, category = ScanCategory.Screenshot)))
        val decisions = ThrowingDecisions(
            upstream,
            undoError = kotlinx.coroutines.CancellationException("stopped"),
        )
        val viewModel = viewModel(ScanCategory.Screenshot, upstream, decisions)
        viewModel.startSelection(item(viewModel, 6))
        viewModel.keepSelected()
        val kept = viewModel.keepNotice.value
        viewModel.undoKeep()

        assertEquals(kept, viewModel.keepNotice.value)
        assertEquals(ReviewStatus.Kept, upstream.value.single().reviewStatus)
        assertTrue(viewModel.keepNotice.value is ReviewNotice.Kept)
    }

    private fun viewModel(
        category: ScanCategory,
        pending: Flow<List<ScanResult>>,
        decisions: ReviewDecisions = NoDecisions,
        savedState: SavedStateHandle = SavedStateHandle(),
    ): ReviewViewModel = ReviewViewModel(
        category = category,
        pending = pending,
        decisions = decisions,
        savedState = savedState,
        started = SharingStarted.Eagerly,
    )

    private fun item(viewModel: ReviewViewModel, id: Long): ReviewItem {
        return items(viewModel).first { item -> item.id == id }
    }

    private fun items(viewModel: ReviewViewModel): List<ReviewItem> {
        return (viewModel.uiState.value as ReviewUiState.Items).items
    }

    private fun ids(viewModel: ReviewViewModel): List<Long> = items(viewModel).map { item -> item.id }
}

class ReviewCategoryTest {
    @Test
    fun onlyNudityStartsBlurred() {
        assertTrue(ScanCategory.Sensitive.toReviewCategory().sensitive)
        ScanCategory.entries
            .filter { category -> category != ScanCategory.Sensitive }
            .forEach { category ->
                assertFalse(category.name, category.toReviewCategory().sensitive)
            }
    }
}

private object NoDecisions : ReviewDecisions {
    override suspend fun markKept(keys: Collection<MediaKey>) = Unit
    override suspend fun markPending(keys: Collection<MediaKey>) = Unit
}

/**
 * Same list updates as [RecordingDecisions], except [keepError] or [undoError]
 * is thrown instead of writing. A null error uses the normal status update.
 */
private class ThrowingDecisions(
    private val rows: MutableStateFlow<List<ScanResult>>,
    private val keepError: Exception? = null,
    private val undoError: Exception? = null,
) : ReviewDecisions {
    override suspend fun markKept(keys: Collection<MediaKey>) {
        keepError?.let { error -> throw error }
        setStatus(keys, ReviewStatus.Kept)
    }

    override suspend fun markPending(keys: Collection<MediaKey>) {
        undoError?.let { error -> throw error }
        setStatus(keys, ReviewStatus.Pending)
    }

    private fun setStatus(keys: Collection<MediaKey>, status: ReviewStatus) {
        val selected = keys.toSet()
        rows.value = rows.value.map { row ->
            if (row.key in selected) row.copy(reviewStatus = status) else row
        }
    }
}

/**
 * Applies keep and undo to the same list the ViewModel is watching, which is
 * what [com.useinoski.gallerycleanup.data.RoomScanResultStore.observePending]
 * does after the transaction: a kept row is no longer in the pending list.
 */
private class RecordingDecisions(
    private val rows: MutableStateFlow<List<ScanResult>>,
) : ReviewDecisions {
    val kept = mutableListOf<List<MediaKey>>()
    val pending = mutableListOf<List<MediaKey>>()

    override suspend fun markKept(keys: Collection<MediaKey>) {
        kept += keys.toList()
        setStatus(keys, ReviewStatus.Kept)
    }

    override suspend fun markPending(keys: Collection<MediaKey>) {
        pending += keys.toList()
        setStatus(keys, ReviewStatus.Pending)
    }

    private fun setStatus(keys: Collection<MediaKey>, status: ReviewStatus) {
        val selected = keys.toSet()
        rows.value = rows.value.map { row ->
            if (row.key in selected) row.copy(reviewStatus = status) else row
        }
    }
}

private fun row(
    id: Long,
    category: ScanCategory,
    status: ReviewStatus = ReviewStatus.Pending,
    outcome: AnalysisOutcome = AnalysisOutcome.Scored,
    type: MediaType = MediaType.Image,
    scannedAt: Long = id,
): ScanResult = ScanResult(
    mediaStoreId = id,
    contentUri = "content://media/external/images/media/$id",
    mediaType = type,
    category = category,
    confidence = 0.9f,
    modelVersion = "test",
    scannedAtEpochMillis = scannedAt,
    reviewStatus = status,
    analysisOutcome = outcome,
)
