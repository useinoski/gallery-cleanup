package com.useinoski.gallerycleanup.scan

import androidx.work.WorkInfo
import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.media.IdPage
import com.useinoski.gallerycleanup.media.IdPageRequest
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.MediaPage
import com.useinoski.gallerycleanup.media.MediaPageRequest
import com.useinoski.gallerycleanup.media.MediaRepository
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.media.ScanCountRequest
import com.useinoski.gallerycleanup.media.ScanPage
import com.useinoski.gallerycleanup.media.ScanPageRequest
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.TypeProgress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B1: a system stop is not a user cancel, and a resumed pass continues from
 * the checkpoint. B2: Cancel stops the walk and the rescan, and failed rows
 * left by the upgrade are not classified until a scan runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanSessionTest {
    @Test
    fun buttonFollowsWorkManagerWhenTheStoredPhaseIsStale() {
        assertTrue(scanAppearsRunning(ScanPhase.Running, workActive = false))
        assertTrue(scanAppearsRunning(ScanPhase.Cancelled, workActive = true))
        assertTrue(scanAppearsRunning(ScanPhase.Failed, workActive = true))
        assertTrue(scanAppearsRunning(ScanPhase.Completed, workActive = true))
        assertFalse(scanAppearsRunning(ScanPhase.Cancelled, workActive = false))
        assertFalse(scanAppearsRunning(ScanPhase.Failed, workActive = false))
        assertFalse(scanAppearsRunning(ScanPhase.Completed, workActive = false))
        assertFalse(scanAppearsRunning(ScanPhase.Idle, workActive = false))
    }

    @Test
    fun onlyAnAppCancelIsAUserStop() {
        val systemStops = intArrayOf(
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
            WorkInfo.STOP_REASON_UNKNOWN,
            WorkInfo.STOP_REASON_PREEMPT,
            WorkInfo.STOP_REASON_TIMEOUT,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY,
            WorkInfo.STOP_REASON_USER,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED,
        )
        for (reason in systemStops) {
            assertFalse(isUserScanCancel(reason, userCancelRequested = false))
            assertFalse(isUserScanCancel(reason, userCancelRequested = true))
        }
        assertTrue(isUserScanCancel(WorkInfo.STOP_REASON_CANCELLED_BY_APP, userCancelRequested = false))
        assertTrue(isUserScanCancel(WorkInfo.STOP_REASON_CANCELLED_BY_APP, userCancelRequested = true))
        assertFalse(isUserScanCancel(WorkInfo.STOP_REASON_NOT_STOPPED, userCancelRequested = false))
        assertTrue(isUserScanCancel(WorkInfo.STOP_REASON_NOT_STOPPED, userCancelRequested = true))
    }

    @Test
    fun theCancelFlagIsWhatDecidesWhileStopReasonIsUnset() {
        UserScanCancel.clear()
        assertFalse(isUserScanCancel(WorkInfo.STOP_REASON_NOT_STOPPED, UserScanCancel.isRequested()))
        UserScanCancel.mark()
        assertTrue(isUserScanCancel(WorkInfo.STOP_REASON_NOT_STOPPED, UserScanCancel.isRequested()))
        UserScanCancel.clear()
        assertFalse(UserScanCancel.isRequested())
    }

    @Test
    fun aRunningRowWithNoWorkIsCancelledAndKeepsTheCheckpoint() = runTest {
        val store = InMemoryScanStore()
        store.saveCheckpoint(
            ScanCheckpoint(
                type = MediaType.Image,
                dateModified = 30,
                id = 2,
                scanned = 4,
                generationModifiedAfter = null,
                generationModifiedAtMost = 9,
                mediaStoreVersion = "v1",
            ),
        )
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 4, total = 10)),
                mediaStoreVersion = "v1",
                completedVolumeGeneration = null,
                detail = null,
            ),
        )

        abandonRunIfNoWork(store, workActive = false)

        val run = store.readRun()
        assertEquals(ScanPhase.Cancelled, run.phase)
        assertEquals(4, run.byType.single().scanned)
        assertEquals(4, store.readCheckpoint()!!.scanned)
    }

    @Test
    fun activeWorkIsNotClearedOnLaunch() = runTest {
        val store = InMemoryScanStore()
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 4, total = 10)),
                mediaStoreVersion = "v1",
                completedVolumeGeneration = null,
                detail = null,
            ),
        )
        abandonRunIfNoWork(store, workActive = true)
        assertEquals(ScanPhase.Running, store.readRun().phase)
    }

    @Test
    fun aSystemStopKeepsThePhaseRunningAndTheNextPassResumesPastZero() = runTest {
        val library = ScanMemoryLibrary(
            listOf(
                scannedRow(id = 1, modified = 40, generation = 1),
                scannedRow(id = 2, modified = 30, generation = 1),
                scannedRow(id = 3, modified = 20, generation = 1),
                scannedRow(id = 4, modified = 10, generation = 1),
            ),
        )
        val store = InMemoryScanStore()
        var batches = 0
        var interrupt = true
        val detectors = ArrayList<FakeDetector>()
        val scan = LibraryScan {
            val detector = FakeDetector()
            detectors += detector
            LibraryScan.Pass(
                scanner = testScanner(
                    library = library,
                    store = store,
                    observer = ScanBatchObserver {
                        batches += 1
                        if (interrupt && batches >= 2) {
                            throw CancellationException("quota")
                        }
                    },
                    batchSize = 2,
                    generation = 4,
                ),
                detectors = detector,
                runs = store,
            )
        }

        try {
            // Quota is a system stop even if the Cancel flag is also set.
            scan.run {
                isUserScanCancel(WorkInfo.STOP_REASON_QUOTA, userCancelRequested = true)
            }
        } catch (_: CancellationException) {
            // The worker rethrows so WorkManager can reschedule the same work.
        }

        assertEquals(ScanPhase.Running, store.readRun().phase)
        assertEquals(2, store.readRun().byType.single().scanned)
        assertEquals(2, store.readCheckpoint()!!.scanned)
        assertEquals(1, detectors.size)
        assertEquals(1, detectors.single().closeCount)

        interrupt = false
        val requestsBeforeResume = library.scanRequests.size
        scan.run()

        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertEquals(4, store.readRun().byType.single().scanned)
        assertNull(store.readCheckpoint())
        assertNotNull(library.scanRequests[requestsBeforeResume].after)
        assertEquals(2, detectors.size)
        assertTrue(detectors.all { detector -> detector.closeCount == 1 })
    }

    @Test
    fun aUserStopStaysCancelled() = runTest {
        val store = InMemoryScanStore()
        val library = ScanMemoryLibrary(listOf(scannedRow(id = 1, modified = 10, generation = 1)))
        val scan = LibraryScan {
            LibraryScan.Pass(
                scanner = testScanner(
                    library = library,
                    store = store,
                    observer = ScanBatchObserver { throw CancellationException("cancel") },
                    batchSize = 10,
                    generation = 4,
                ),
                runs = store,
            )
        }

        try {
            scan.run {
                isUserScanCancel(WorkInfo.STOP_REASON_CANCELLED_BY_APP, userCancelRequested = false)
            }
        } catch (_: CancellationException) {
            // The worker rethrows so WorkManager can finish the cancel.
        }

        assertEquals(ScanPhase.Cancelled, store.readRun().phase)
    }

    @Test
    fun cancelWhileTheMainScanIsRunningStopsItAndSkipsTheRescan() = runTest {
        val store = InMemoryScanStore()
        val entered = CompletableDeferred<Unit>()
        val detector = FakeDetector()
        var rescan = false
        val scan = LibraryScan {
            LibraryScan.Pass(
                scanner = testScanner(HangingLibrary(entered), store),
                detectors = detector,
                afterPass = { rescan = true },
                runs = store,
            )
        }

        val job = launch { scan.run() }
        entered.await()
        job.cancel()
        job.join()

        assertFalse(rescan)
        assertEquals(1, detector.closeCount)
        assertEquals(ScanPhase.Cancelled, store.readRun().phase)
    }

    @Test
    fun cancelDuringTheRescanStopsWithinOneBatchAndSavesCancelled() = runTest {
        val store = InMemoryScanStore()
        val first = testItem(1)
        val second = testItem(2)
        val library = ScanMemoryLibrary(
            listOf(
                scannedRow(id = first.id, modified = 20, generation = 1),
                scannedRow(id = second.id, modified = 10, generation = 1),
            ),
        )
        val analysis = FakeAnalysisRecords()
        analysis.plant(first, "mlkit-face-count|face-v0", outcome = AnalysisOutcome.Failed)
        analysis.plant(second, "mlkit-face-count|face-v0", outcome = AnalysisOutcome.Failed)
        val entered = CompletableDeferred<Unit>()
        var batches = 0
        val detector = FakeDetector()
        val rescan = PendingRescan(
            records = analysis,
            runs = store,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> store.storedItem(key) } },
            observer = ScanBatchObserver {
                batches += 1
                if (batches == 1) {
                    entered.complete(Unit)
                    suspendCancellableCoroutine<Unit> { }
                }
            },
            batchSize = 1,
        )
        val scan = LibraryScan {
            LibraryScan.Pass(
                scanner = testScanner(library, store, generation = 4),
                detectors = detector,
                afterPass = { rescan.afterPass() },
                runs = store,
            )
        }

        val job = launch { scan.run() }
        entered.await()
        job.cancel()
        job.join()

        assertEquals(1, batches)
        assertEquals(ScanPhase.Cancelled, store.readRun().phase)
        assertEquals(1, detector.closeCount)
    }

    @Test
    fun failedRowsFromTheUpgradeAreNotClassifiedUntilAScanRuns() = runTest {
        val store = InMemoryScanStore()
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Completed,
                byType = emptyList(),
                mediaStoreVersion = "v1",
                completedVolumeGeneration = 4,
                detail = null,
            ),
        )
        val broken = testItem(4)
        store.upsertBatch(listOf(broken))
        val analysis = FakeAnalysisRecords()
        analysis.plant(broken, "none", outcome = AnalysisOutcome.Failed)
        var batches = 0
        val rescan = PendingRescan(
            records = analysis,
            runs = store,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> store.storedItem(key) } },
            observer = ScanBatchObserver { batches += 1 },
            batchSize = 1,
        )

        assertEquals(0, batches)

        rescan.afterPass()

        assertEquals(1, batches)
        assertEquals(ScanPhase.Completed, store.readRun().phase)
    }

    @Test
    fun twoOverlappingPassesOpenOneDetectorSetAtATime() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        var opens = 0
        fun scan(): LibraryScan {
            val store = InMemoryScanStore()
            return LibraryScan {
                opens += 1
                LibraryScan.Pass(
                    scanner = testScanner(OverlapLibrary(inFlight, maxInFlight), store),
                )
            }
        }

        val first = scan()
        val second = scan()
        coroutineScope {
            launch { first.run() }
            launch { second.run() }
        }

        assertEquals(1, maxInFlight.get())
        assertEquals(2, opens)
    }
}

private class HangingLibrary(
    private val entered: CompletableDeferred<Unit>,
) : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = MediaCounts(1, null, null, null)

    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        entered.complete(Unit)
        suspendCancellableCoroutine<Unit> { }
        error("cancelled")
    }

    override suspend fun countScan(request: ScanCountRequest): Int = 1

    override suspend fun pageIds(request: IdPageRequest): IdPage = IdPage(emptyList(), false)
}

private class OverlapLibrary(
    private val inFlight: AtomicInteger,
    private val maxInFlight: AtomicInteger,
) : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = MediaCounts(1, null, null, null)

    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { previous -> maxOf(previous, now) }
        delay(50)
        inFlight.decrementAndGet()
        return ScanPage(rows = emptyList(), hasMore = false)
    }

    override suspend fun countScan(request: ScanCountRequest): Int = 1

    override suspend fun pageIds(request: IdPageRequest): IdPage = IdPage(emptyList(), false)
}
