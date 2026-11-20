package com.useinoski.gallerycleanup.ui.home

import com.useinoski.gallerycleanup.data.CategoryCounts
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.IdPage
import com.useinoski.gallerycleanup.media.IdPageRequest
import com.useinoski.gallerycleanup.media.MediaPage
import com.useinoski.gallerycleanup.media.MediaPageRequest
import com.useinoski.gallerycleanup.media.MediaRepository
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.media.ScanCountRequest
import com.useinoski.gallerycleanup.media.ScanPage
import com.useinoski.gallerycleanup.media.ScanPageRequest
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.PermissionState
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.model.TypeProgress
import com.useinoski.gallerycleanup.scan.ScanControls
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.useinoski.gallerycleanup.prefs.AudioScanStore
import com.useinoski.gallerycleanup.prefs.OnboardingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun completingOnboardingDoesNotReadTheLibrary() {
        val repository = FakeMediaRepository()
        val viewModel = HomeViewModel(repository, MemoryOnboarding(complete = false), MemoryAudio(false), FakeScans())
        assertTrue(viewModel.state.value.showOnboarding)
        viewModel.completeOnboarding()
        assertFalse(viewModel.state.value.showOnboarding)
        assertEquals(0, repository.calls)
    }

    @Test
    fun fullAccessCountsImagesAndVideosButNotAudioUntilOptIn() {
        val repository = FakeMediaRepository(MediaCounts(images = 12, videos = 3, music = 8, otherAudio = 1))
        val audio = MemoryAudio(enabled = false)
        val viewModel = HomeViewModel(repository, MemoryOnboarding(complete = true), audio, FakeScans())
        viewModel.refresh(fullAccess())
        assertEquals(12, viewModel.state.value.counts.images)
        assertEquals(3, viewModel.state.value.counts.videos)
        assertNull(viewModel.state.value.counts.music)
        assertFalse(repository.lastReadable!!.audio)
        assertFalse(viewModel.state.value.loading)

        viewModel.setAudioScanningEnabled(true)
        viewModel.refresh(fullAccess())
        assertEquals(8, viewModel.state.value.counts.music)
        assertEquals(1, viewModel.state.value.counts.otherAudio)
        assertTrue(repository.lastReadable!!.audio)
    }

    @Test
    fun partialImagesAreCountedAndDeniedVideoIsLeftNull() {
        val repository = FakeMediaRepository(MediaCounts(images = 2, videos = 9, music = 0, otherAudio = 0))
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(false), FakeScans())
        viewModel.refresh(
            LibraryAccess(
                images = PermissionState.Partial,
                videos = PermissionState.Denied,
                audio = PermissionState.Denied,
            ),
        )
        assertTrue(repository.lastReadable!!.images)
        assertFalse(repository.lastReadable!!.videos)
        assertEquals(2, viewModel.state.value.counts.images)
        assertNull(viewModel.state.value.counts.videos)
    }

    @Test
    fun noGrantSkipsTheRepository() {
        val repository = FakeMediaRepository()
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(true), FakeScans())
        viewModel.refresh(
            LibraryAccess(
                images = PermissionState.PermanentlyDenied,
                videos = PermissionState.PermanentlyDenied,
                audio = PermissionState.PermanentlyDenied,
            ),
        )
        assertEquals(0, repository.calls)
        assertNull(viewModel.state.value.counts.images)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun repositoryFailureIsReportedWithoutClearingTheGrant() {
        val repository = FakeMediaRepository(error = IllegalStateException("disk"))
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(false), FakeScans())
        val access = fullAccess()
        viewModel.refresh(access)
        assertTrue(viewModel.state.value.failed)
        assertEquals(access, viewModel.state.value.access)
        assertFalse(viewModel.state.value.loading)
    }

    @Test
    fun scanProgressArrivesOnTheHomeStateAndTheButtonsDelegate() {
        val scans = FakeScans()
        val viewModel = HomeViewModel(FakeMediaRepository(), MemoryOnboarding(true), MemoryAudio(false), scans)
        assertEquals(ScanPhase.Idle, viewModel.state.value.scan.phase)
        scans.publish(
            ScanProgress(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 4, total = 10)),
                detail = null,
            ),
        )
        assertEquals(4, viewModel.state.value.scan.scanned)
        assertEquals(10, viewModel.state.value.scan.total)
        viewModel.startScan()
        viewModel.cancelScan()
        assertEquals(1, scans.starts)
        assertEquals(1, scans.cancels)
    }

    @Test
    fun flaggedCountsArriveOnTheHomeState() {
        val counts = MutableStateFlow(emptyCategoryCounts())
        val viewModel = HomeViewModel(
            FakeMediaRepository(),
            MemoryOnboarding(true),
            MemoryAudio(false),
            FakeScans(),
            counts,
        )
        assertEquals(0, viewModel.state.value.flaggedCounts[ScanCategory.People])
        counts.value = CategoryCounts(
            pending = ScanCategory.entries.associateWith { category ->
                if (category == ScanCategory.People) 4 else if (category == ScanCategory.Sensitive) 1 else 0
            },
        )
        assertEquals(4, viewModel.state.value.flaggedCounts[ScanCategory.People])
        assertEquals(1, viewModel.state.value.flaggedCounts[ScanCategory.Sensitive])
        assertEquals(5, viewModel.state.value.flaggedCounts.flagged)
    }

    private fun fullAccess() = LibraryAccess(
        images = PermissionState.Full,
        videos = PermissionState.Full,
        audio = PermissionState.Full,
    )
}

private class FakeMediaRepository(
    private val result: MediaCounts = MediaCounts(0, 0, 0, 0),
    private val error: Throwable? = null,
) : MediaRepository {
    var calls: Int = 0
    var lastReadable: ReadableMedia? = null

    override suspend fun count(readable: ReadableMedia): MediaCounts {
        calls += 1
        lastReadable = readable
        error?.let { throw it }
        // The real repository leaves a collection null when it was not asked to read it.
        return MediaCounts(
            images = if (readable.images) result.images else null,
            videos = if (readable.videos) result.videos else null,
            music = if (readable.audio) result.music else null,
            otherAudio = if (readable.audio) result.otherAudio else null,
        )
    }

    override suspend fun page(request: MediaPageRequest): MediaPage {
        error("pages are not used by the home screen")
    }

    override suspend fun scanPage(request: ScanPageRequest): ScanPage = error("not used")

    override suspend fun countScan(request: ScanCountRequest): Int = error("not used")

    override suspend fun pageIds(request: IdPageRequest): IdPage = error("not used")
}

private class FakeScans : ScanControls {
    private val progressState = MutableStateFlow(ScanProgress.from(com.useinoski.gallerycleanup.model.ScanRun.IDLE))
    override val progress: Flow<ScanProgress> = progressState.asStateFlow()
    var starts: Int = 0
    var cancels: Int = 0

    override fun start() {
        starts += 1
    }

    override fun cancel() {
        cancels += 1
    }

    fun publish(progress: ScanProgress) {
        progressState.value = progress
    }
}

private class MemoryOnboarding(private var complete: Boolean) : OnboardingStore {
    override fun isComplete(): Boolean = complete
    override fun setComplete() {
        complete = true
    }
}

private class MemoryAudio(private var enabled: Boolean) : AudioScanStore {
    override fun isEnabled(): Boolean = enabled
    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }
}
