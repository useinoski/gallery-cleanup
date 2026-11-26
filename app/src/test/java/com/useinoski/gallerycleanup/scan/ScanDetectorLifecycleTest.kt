package com.useinoski.gallerycleanup.scan

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.ScanPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The scan owns the detectors. [close] runs when the pass ends, fails, or
 * is cancelled, and not before the rescan pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanDetectorLifecycleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun closeRunsAfterAFinishedScanIncludingTheRescanPass() = runTest {
        val detector = FakeDetector()
        val store = InMemoryScanStore()
        var rescanSawOpenDetector = false
        val scan = LibraryScan(
            scanner = testScanner(ScanMemoryLibrary(listOf(scannedRow(1, generation = 4))), store, generation = 4),
            detectors = detector,
            afterPass = { rescanSawOpenDetector = detector.closeCount == 0 },
        )

        assertEquals(Result.success(), worker(scan).doWork())

        assertTrue(rescanSawOpenDetector)
        assertEquals(1, detector.closeCount)
        assertEquals(ScanPhase.Completed, store.readRun().phase)
    }

    @Test
    fun closeRunsWhenTheScanFails() = runTest {
        val detector = FakeDetector()
        val store = InMemoryScanStore()
        var afterPassRan = false
        val scan = LibraryScan(
            scanner = testScanner(ThrowingScanLibrary(IllegalStateException("detector failed")), store),
            detectors = detector,
            afterPass = { afterPassRan = true },
        )

        assertEquals(Result.failure(), worker(scan).doWork())

        assertTrue(!afterPassRan)
        assertEquals(1, detector.closeCount)
        assertEquals(ScanPhase.Failed, store.readRun().phase)
    }

    @Test
    fun closeRunsWhenTheScanIsCancelled() = runTest {
        val detector = FakeDetector()
        val store = InMemoryScanStore()
        val scan = LibraryScan(
            scanner = testScanner(ThrowingScanLibrary(CancellationException("stop")), store),
            detectors = detector,
            afterPass = { error("rescan must not run after cancel") },
        )
        val error = async {
            try {
                worker(scan).doWork()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
        }.await()

        assertTrue(error is CancellationException)
        assertEquals(1, detector.closeCount)
        assertEquals(ScanPhase.Cancelled, store.readRun().phase)
    }

    private fun worker(scan: LibraryScan) = TestListenableWorkerBuilder<LibraryScanWorker>(context)
        .setWorkerFactory(ScanFactory(scan))
        .build()

    private class ScanFactory(
        private val scan: LibraryScan,
    ) : androidx.work.WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ) = LibraryScanWorker(appContext, workerParameters, scan)
    }
}
