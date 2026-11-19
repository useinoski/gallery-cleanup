package app.hearth.ui.home

import app.hearth.media.MediaCounts
import app.hearth.media.MediaPage
import app.hearth.media.MediaPageRequest
import app.hearth.media.MediaRepository
import app.hearth.media.ReadableMedia
import app.hearth.model.LibraryAccess
import app.hearth.model.PermissionState
import app.hearth.prefs.AudioScanStore
import app.hearth.prefs.OnboardingStore
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
        val viewModel = HomeViewModel(repository, MemoryOnboarding(complete = false), MemoryAudio(false))
        assertTrue(viewModel.state.value.showOnboarding)
        viewModel.completeOnboarding()
        assertFalse(viewModel.state.value.showOnboarding)
        assertEquals(0, repository.calls)
    }

    @Test
    fun fullAccessCountsImagesAndVideosButNotAudioUntilOptIn() {
        val repository = FakeMediaRepository(MediaCounts(images = 12, videos = 3, music = 8, otherAudio = 1))
        val audio = MemoryAudio(enabled = false)
        val viewModel = HomeViewModel(repository, MemoryOnboarding(complete = true), audio)
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
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(false))
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
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(true))
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
        val viewModel = HomeViewModel(repository, MemoryOnboarding(true), MemoryAudio(false))
        val access = fullAccess()
        viewModel.refresh(access)
        assertTrue(viewModel.state.value.failed)
        assertEquals(access, viewModel.state.value.access)
        assertFalse(viewModel.state.value.loading)
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
