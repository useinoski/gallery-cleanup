package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.media.MediaPermissions
import com.useinoski.gallerycleanup.media.ReadableLibrary
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.media.SCAN_ACCESS_REVOKED_DETAIL
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Losing media access mid-batch stops the page. "Selected photos" is a
 * smaller library, not a revoked grant.
 */
class AccessRevokedScanTest {
    @Test
    fun securityExceptionStopsTheOpenBatchAndTheNextScanResumesThoseItems() = runTest {
        val library = ScanMemoryLibrary(
            (1L..4L).map { id -> scannedRow(id = id, modified = 50 - id, generation = 4) },
        )
        val scanStore = InMemoryScanStore()
        val analysis = FakeAnalysisRecords()
        val blocked = mutableSetOf(3L)
        val seen = mutableListOf<Long>()
        val observer = DetectionBatchObserver(analysis, parallelism = 1) { item ->
            seen += item.id
            if (item.id in blocked) throw SecurityException("media access was removed")
            ImageSignals()
        }
        val rescan = PendingRescan(
            records = analysis,
            runs = scanStore,
            currentModelVersions = TEST_MODEL_VERSIONS,
            resolvePresent = { keys -> keys.mapNotNull { key -> scanStore.storedItem(key) } },
            observer = observer,
        )
        val scan = LibraryScan(
            scanner = testScanner(library, scanStore, observer, batchSize = 2, generation = 4),
            afterPass = { rescan.afterPass() },
        )

        scan.run()

        assertEquals(ScanPhase.AccessRevoked, scanStore.readRun().phase)
        assertEquals(SCAN_ACCESS_REVOKED_DETAIL, scanStore.readRun().detail)
        assertEquals(2, scanStore.readCheckpoint()!!.scanned)
        assertEquals(AnalysisOutcome.Scored, analysis.rows.getValue(MediaKey(MediaType.Image, 1)).outcome)
        assertEquals(AnalysisOutcome.Scored, analysis.rows.getValue(MediaKey(MediaType.Image, 2)).outcome)
        assertTrue(analysis.rows.keys.none { key -> key.id == 3L || key.id == 4L })
        assertTrue(analysis.failureBatches.isEmpty())
        assertTrue(analysis.upserted.none { result -> result.mediaStoreId == 3L || result.mediaStoreId == 4L })

        blocked.clear()
        seen.clear()
        analysis.upserted.clear()
        scan.run()

        assertEquals(ScanPhase.Completed, scanStore.readRun().phase)
        assertNull(scanStore.readRun().detail)
        assertNull(scanStore.readCheckpoint())
        assertEquals(listOf(3L, 4L), seen)
        assertEquals(setOf(1L, 2L, 3L, 4L), analysis.rows.keys.map { key -> key.id }.toSet())
        assertTrue(analysis.rows.values.all { row -> row.outcome == AnalysisOutcome.Scored })
        assertTrue(analysis.failureBatches.isEmpty())
        assertEquals(FlagPolicy.NONE, analysis.rows.getValue(MediaKey(MediaType.Image, 3)).version)
        assertEquals(FlagPolicy.NONE, analysis.rows.getValue(MediaKey(MediaType.Image, 4)).version)
    }

    @Test
    fun selectedPhotosOnlyCompletesAsASmallerLibrary() = runTest {
        val access = ReadableLibrary(
            sdkInt = 34,
            granted = { setOf(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED) },
            audioEnabled = { false },
        )
        val library = ScanMemoryLibrary(
            listOf(
                scannedRow(id = 1, modified = 20, generation = 4),
                scannedRow(id = 2, modified = 10, generation = 4, type = MediaType.Video),
            ),
        )
        val store = InMemoryScanStore()
        val seen = mutableListOf<Long>()
        testScanner(
            library = library,
            store = store,
            observer = { batch -> seen += batch.map { item -> item.id } },
            readable = { access.current() },
            generation = 4,
        ).scan()

        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertNull(store.readRun().detail)
        assertEquals(listOf(1L, 2L), seen)
        assertEquals("v1", store.readRun().mediaStoreVersion)
    }

    @Test
    fun switchingToSelectedPhotosMidScanDoesNotRevoke() = runTest {
        val granted = mutableSetOf(
            MediaPermissions.READ_MEDIA_IMAGES,
            MediaPermissions.READ_MEDIA_VIDEO,
        )
        val access = ReadableLibrary(
            sdkInt = 34,
            granted = { granted.toSet() },
            audioEnabled = { false },
        )
        val library = ScanMemoryLibrary(
            (1L..4L).map { id -> scannedRow(id = id, modified = 40 - id, generation = 4) },
        )
        val store = InMemoryScanStore()
        var batches = 0
        testScanner(
            library = library,
            store = store,
            observer = {
                batches += 1
                if (batches == 1) {
                    granted.clear()
                    granted += MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED
                }
            },
            readable = { access.current() },
            batchSize = 2,
            generation = 4,
        ).scan()

        assertTrue(batches >= 2)
        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertNull(store.readRun().detail)
        assertEquals(setOf(1L, 2L, 3L, 4L), store.presentKeys(MediaType.Image).map { key -> key.id }.toSet())
        assertTrue(access.current() == ReadableMedia(images = true, videos = true, audio = false))
    }
}
