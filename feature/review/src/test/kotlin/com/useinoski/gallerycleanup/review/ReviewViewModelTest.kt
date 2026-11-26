package com.useinoski.gallerycleanup.review

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.ReviewStatus
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
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

    private fun viewModel(
        category: ScanCategory,
        pending: Flow<List<ScanResult>>,
    ): ReviewViewModel = ReviewViewModel(
        category = category,
        pending = pending,
        started = SharingStarted.Eagerly,
    )

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
