package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.media.SCAN_ACCESS_REVOKED_DETAIL
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rows the generation cursor does not walk are still classified once.
 * Clean photos, current video and audio rules, removed files, and missing
 * rows stay out of that pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingRescanTest {
    @Test
    fun aSkippedWalkStillRetriesFailedAndOutdatedRowsOnly() = runTest {
        val library = ScanMemoryLibrary(listOf(scannedRow(id = 1, modified = 10, generation = 4)))
        val scanStore = InMemoryScanStore()
        val analysis = FakeAnalysisRecords()
        val imageCalls = mutableListOf<Long>()
        val observer = DetectionBatchObserver(analysis, parallelism = 1) { item ->
            imageCalls += item.id
            if (item.id == 2L) peopleSignals() else ImageSignals()
        }
        val rescan = PendingRescan(
            records = analysis,
            runs = scanStore,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> scanStore.storedItem(key) } },
            observer = observer,
        )
        val scanner = testScanner(library, scanStore, observer, generation = 4)
        val scan = LibraryScan {
            LibraryScan.Pass(scanner = scanner, afterPass = { rescan.afterPass() })
        }

        scan.run()
        assertEquals(listOf(1L), imageCalls)
        assertEquals(1, analysis.rescanCalls)
        assertEquals(ScanPhase.Completed, scanStore.readRun().phase)

        val outdated = testItem(2)
        val clean = testItem(3)
        val failed = testItem(4)
        val removed = testItem(5)
        val currentVideo = testItem(6, MediaType.Video)
        val oldVideo = testItem(7, MediaType.Video)
        val currentAudio = testItem(8, MediaType.Audio)
        val missing = testItem(9)
        scanStore.upsertBatch(listOf(outdated, clean, failed, removed, currentVideo, oldVideo, currentAudio))
        scanStore.markRemoved(listOf(MediaKey(MediaType.Image, removed.id)))
        analysis.removed += MediaKey(MediaType.Image, removed.id)
        analysis.plant(outdated, "mlkit-face-count|face-v1", category = ScanCategory.People)
        analysis.plant(clean, FlagPolicy.NONE)
        analysis.plant(failed, FlagPolicy.NONE, outcome = AnalysisOutcome.Failed)
        analysis.plant(removed, "mlkit-face-count|face-v1", category = ScanCategory.People)
        analysis.plant(currentVideo, FlagPolicy.VIDEO_RULE, category = ScanCategory.Video)
        analysis.plant(oldVideo, "video-manual-review|v0", category = ScanCategory.Video)
        analysis.plant(currentAudio, FlagPolicy.AUDIO_RULE, category = ScanCategory.Audio)
        analysis.plant(missing, FlagPolicy.NONE, outcome = AnalysisOutcome.Failed)

        imageCalls.clear()
        analysis.upserted.clear()
        library.scanRequests.clear()
        val rescansBefore = analysis.rescanCalls
        scan.run()

        assertTrue("generation match must skip the MediaStore walk", library.scanRequests.isEmpty())
        assertEquals("one rescan query, not a loop", rescansBefore + 1, analysis.rescanCalls)
        assertEquals("clean, current, removed, and missing files are not decoded", setOf(2L, 4L), imageCalls.toSet())
        assertEquals(ScanPhase.Completed, scanStore.readRun().phase)

        val written = analysis.upserted.associateBy { result -> result.mediaStoreId }
        assertEquals(setOf(2L, 4L, 7L), written.keys)
        assertEquals(ScanCategory.People, written.getValue(2).category)
        assertEquals("mlkit-face-count|face-v2", written.getValue(2).modelVersion)
        assertEquals(FlagPolicy.NONE, written.getValue(4).modelVersion)
        assertEquals(ScanCategory.Video, written.getValue(7).category)
        assertEquals(FlagPolicy.VIDEO_RULE, written.getValue(7).modelVersion)
        assertEquals(AnalysisOutcome.Scored, analysis.rows.getValue(MediaKey(MediaType.Image, 3)).outcome)
        assertEquals(FlagPolicy.NONE, analysis.rows.getValue(MediaKey(MediaType.Image, 3)).version)
        assertEquals(FlagPolicy.VIDEO_RULE, analysis.rows.getValue(MediaKey(MediaType.Video, 6)).version)
        assertEquals(FlagPolicy.AUDIO_RULE, analysis.rows.getValue(MediaKey(MediaType.Audio, 8)).version)
        assertTrue(analysis.upserted.none { result -> result.mediaStoreId == removed.id || result.mediaStoreId == missing.id })
        assertNull(scanStore.storedItem(MediaKey(MediaType.Image, removed.id)))
        assertNull(scanStore.storedItem(MediaKey(MediaType.Image, missing.id)))
    }

    @Test
    fun aPermanentlyBrokenFileIsClassifiedOncePerPass() = runTest {
        val scanStore = completedRun()
        val analysis = FakeAnalysisRecords()
        val broken = testItem(4)
        scanStore.upsertBatch(listOf(broken))
        analysis.plant(broken, FlagPolicy.NONE, outcome = AnalysisOutcome.Failed)
        var calls = 0
        val observer = DetectionBatchObserver(analysis, parallelism = 1) {
            calls += 1
            error("still broken")
        }
        val rescan = PendingRescan(
            records = analysis,
            runs = scanStore,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> scanStore.storedItem(key) } },
            observer = observer,
        )

        rescan.afterPass()

        assertEquals(1, calls)
        assertEquals(1, analysis.rescanCalls)
        assertEquals(1, analysis.failureBatches.size)
        assertEquals(AnalysisOutcome.Failed, analysis.rows.getValue(MediaKey(MediaType.Image, broken.id)).outcome)
        assertTrue(analysis.upserted.isEmpty())
        assertEquals(ScanPhase.Completed, scanStore.readRun().phase)
    }

    @Test
    fun securityExceptionDuringRescanRevokesAccessAndWritesNoRows() = runTest {
        val scanStore = completedRun()
        val analysis = FakeAnalysisRecords()
        val first = testItem(7)
        val second = testItem(8)
        scanStore.upsertBatch(listOf(first, second))
        analysis.plant(first, "mlkit-face-count|face-v1", category = ScanCategory.People)
        analysis.plant(second, FlagPolicy.NONE, outcome = AnalysisOutcome.Failed)
        val observer = DetectionBatchObserver(analysis, parallelism = 2) { item ->
            if (item.id == second.id) throw SecurityException("grant revoked")
            peopleSignals()
        }
        PendingRescan(
            records = analysis,
            runs = scanStore,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> scanStore.storedItem(key) } },
            observer = observer,
        ).afterPass()

        val run = scanStore.readRun()
        assertEquals(ScanPhase.AccessRevoked, run.phase)
        assertEquals(SCAN_ACCESS_REVOKED_DETAIL, run.detail)
        assertTrue(analysis.upserted.isEmpty())
        assertTrue(analysis.failureBatches.isEmpty())
        assertEquals("mlkit-face-count|face-v1", analysis.rows.getValue(MediaKey(MediaType.Image, first.id)).version)
        assertEquals(AnalysisOutcome.Failed, analysis.rows.getValue(MediaKey(MediaType.Image, second.id)).outcome)
    }

    private suspend fun completedRun(): InMemoryScanStore {
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
        return store
    }
}
