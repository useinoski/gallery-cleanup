package com.useinoski.gallerycleanup.scan

import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.useinoski.gallerycleanup.media.IdPage
import com.useinoski.gallerycleanup.media.IdPageRequest
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.MediaPage
import com.useinoski.gallerycleanup.media.MediaPageRequest
import com.useinoski.gallerycleanup.media.MediaRepository
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.media.ScanCountRequest
import com.useinoski.gallerycleanup.media.ScanPage
import com.useinoski.gallerycleanup.media.ScanPageRequest
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.TypeProgress
import com.useinoski.gallerycleanup.prefs.AudioScanStore
import com.useinoski.gallerycleanup.prefs.OnboardingStore
import com.useinoski.gallerycleanup.ui.home.HomeViewModel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Start goes through [enqueueLibraryScan]. Repeated taps while that work is
 * unfinished enqueue one worker, so one pass opens one detector set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanStartStopTest {
    private lateinit var context: Context
    private lateinit var release: CompletableDeferred<Unit>
    private val started = AtomicInteger(0)
    private val opened = AtomicInteger(0)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        release = CompletableDeferred()
        started.set(0)
        opened.set(0)
        val config = Configuration.Builder()
            .setExecutor(executor)
            .setTaskExecutor(executor)
            .setWorkerFactory(countingFactory())
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            config,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
    }

    @After
    fun tearDown() {
        if (!release.isCompleted) release.complete(Unit)
        // Drain WorkManager's main-thread callbacks while its executor is still
        // alive. Shutting the pool down here makes the next test's looper idle
        // trip over a terminated executor.
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test(timeout = 20_000)
    fun rapidStartWhileAScanIsRunningOpensOneDetectorSet() {
        runBlocking {
            val controls = WorkManagerScanControls(context)
            controls.start()
            awaitStarted(1)
            repeat(4) { controls.start() }
            val running = awaitInfos { infos -> infos.count { info -> info.state.isScanActive() } == 1 }
            assertEquals(1, opened.get())
            assertEquals(1, started.get())
            assertTrue(running.single { info -> info.state.isScanActive() }.tags.contains(LIBRARY_SCAN_TAG))
        }
    }

    @Test(timeout = 20_000)
    fun pauseDoesNotCancelTheScan() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runBlocking {
                val controls = WorkManagerScanControls(context)
                controls.start()
                awaitStarted(1)
                val viewModel = HomeViewModel(
                    repository = UnusedLibrary(),
                    onboardingStore = MemoryOnboarding(complete = true),
                    audioScanStore = MemoryAudio(enabled = false),
                    scans = controls,
                )
                viewModel.onHostPaused()
                val infos = awaitInfos { infos -> infos.any { info -> info.state.isScanActive() } }
                assertEquals(1, opened.get())
                assertEquals(1, started.get())
                assertFalse(infos.single().state.isFinished)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test(timeout = 20_000)
    fun afterCancelOneWaveOfStartsEnqueuesASingleScan() {
        runBlocking {
            val controls = WorkManagerScanControls(context)
            controls.start()
            awaitStarted(1)
            controls.cancel()
            awaitInfos { infos -> infos.isNotEmpty() && infos.all { info -> info.state.isFinished } }

            val startedBefore = started.get()
            val openedBefore = opened.get()
            repeat(5) { controls.start() }
            awaitStarted(startedBefore + 1)
            val active = awaitInfos { infos -> infos.count { info -> info.state.isScanActive() } == 1 }
            assertEquals(openedBefore + 1, opened.get())
            assertEquals(1, active.count { info -> info.state.isScanActive() })
        }
    }

    @Test(timeout = 20_000)
    fun firstLaunchAfterAStaleRunningRowDoesNotEnqueueAScan() {
        runBlocking {
            val store = ScanRuntime.store(context)
            store.saveRun(
                ScanRun(
                    phase = ScanPhase.Running,
                    byType = listOf(TypeProgress(MediaType.Image, scanned = 5, total = 20)),
                    mediaStoreVersion = "v-old",
                    completedVolumeGeneration = null,
                    detail = null,
                ),
            )
            val controls = WorkManagerScanControls(context)
            controls.settleIfIdle(workActive = false)

            val run = store.readRun()
            assertEquals(ScanPhase.Cancelled, run.phase)
            assertEquals(5, run.byType.single().scanned)
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(UNIQUE_LIBRARY_SCAN)
                .get(5, TimeUnit.SECONDS)
            assertTrue(infos.isEmpty())
            assertEquals(0, opened.get())
        }
    }

    private fun countingFactory() = object : androidx.work.WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: androidx.work.WorkerParameters,
        ): androidx.work.ListenableWorker? {
            if (workerClassName != LibraryScanWorker::class.java.name) return null
            val library = GateLibrary(started, release)
            val scan = LibraryScan {
                opened.incrementAndGet()
                LibraryScan.Pass(scanner = testScanner(library, InMemoryScanStore()))
            }
            return LibraryScanWorker(appContext, workerParameters, scan)
        }
    }

    private suspend fun awaitStarted(count: Int) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (started.get() < count) {
            if (System.nanoTime() > deadline) error("scan did not reach $count starts")
            shadowOf(Looper.getMainLooper()).idle()
            delay(20)
        }
    }

    private suspend fun awaitInfos(predicate: (List<WorkInfo>) -> Boolean): List<WorkInfo> {
        val workManager = WorkManager.getInstance(context)
        val deadline = System.nanoTime() + 8_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val infos = workManager.getWorkInfosForUniqueWork(UNIQUE_LIBRARY_SCAN)
                .get(5, TimeUnit.SECONDS)
            if (predicate(infos)) return infos
            if (System.nanoTime() > deadline) error("timed out waiting for work: $infos")
            delay(20)
        }
    }
}

private val executor = Executors.newCachedThreadPool()

private class GateLibrary(
    private val started: AtomicInteger,
    private val release: CompletableDeferred<Unit>,
) : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = MediaCounts(1, null, null, null)

    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        started.incrementAndGet()
        release.await()
        return ScanPage(rows = emptyList(), hasMore = false)
    }

    override suspend fun countScan(request: ScanCountRequest): Int = 1

    override suspend fun pageIds(request: IdPageRequest): IdPage = IdPage(emptyList(), false)
}

private class UnusedLibrary : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = error("unused")

    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage = error("unused")

    override suspend fun countScan(request: ScanCountRequest): Int = error("unused")

    override suspend fun pageIds(request: IdPageRequest): IdPage = error("unused")
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
