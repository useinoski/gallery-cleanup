package com.useinoski.gallerycleanup.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InMemoryScanStoreTest {
    @Test
    fun upsertInsertsAndReplacesByTypeAndId() = runTest {
        val store = InMemoryScanStore()
        store.upsertBatch(listOf(item(MediaType.Image, 1, size = 10), item(MediaType.Audio, 1, size = 3)))
        store.upsertBatch(
            listOf(
                item(MediaType.Image, 1, size = 11),
                item(MediaType.Image, 1, size = 12),
            ),
        )
        assertEquals(12, store.storedItem(MediaKey(MediaType.Image, 1))!!.size)
        assertEquals(3, store.storedItem(MediaKey(MediaType.Audio, 1))!!.size)
        assertEquals(listOf(MediaKey(MediaType.Image, 1)), store.presentKeys(MediaType.Image))
        assertEquals(listOf(MediaKey(MediaType.Audio, 1)), store.presentKeys(MediaType.Audio))
    }

    @Test
    fun emptyWritesAreNoOps() = runTest {
        val store = InMemoryScanStore()
        store.upsertBatch(emptyList())
        store.markRemoved(emptyList())
        store.clearCheckpoint()
        assertEquals(ScanRun.IDLE, store.readRun())
        assertNull(store.readCheckpoint())
        assertTrue(store.presentKeys(MediaType.Video).isEmpty())
    }

    @Test
    fun markRemovedHidesTheRowUntilItIsUpsertedAgain() = runTest {
        val store = InMemoryScanStore()
        val image = item(MediaType.Image, 4)
        store.upsertBatch(listOf(image, item(MediaType.Image, 5)))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 4), MediaKey(MediaType.Video, 4)))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 4)))
        assertNull(store.storedItem(MediaKey(MediaType.Image, 4)))
        assertEquals(listOf(MediaKey(MediaType.Image, 5)), store.presentKeys(MediaType.Image))

        store.upsertBatch(listOf(image.copy(size = 9)))
        assertEquals(9, store.storedItem(MediaKey(MediaType.Image, 4))!!.size)
        assertEquals(
            setOf(MediaKey(MediaType.Image, 4), MediaKey(MediaType.Image, 5)),
            store.presentKeys(MediaType.Image).toSet(),
        )
    }

    @Test
    fun checkpointIsASingleSlot() = runTest {
        val store = InMemoryScanStore()
        assertNull(store.readCheckpoint())
        store.saveCheckpoint(checkpoint(MediaType.Image, id = 3, scanned = 3))
        store.saveCheckpoint(checkpoint(MediaType.Video, id = 8, scanned = 1))
        val saved = store.readCheckpoint()
        assertEquals(MediaType.Video, saved!!.type)
        assertEquals(8, saved.id)
        assertEquals(1, saved.scanned)
        store.clearCheckpoint()
        store.clearCheckpoint()
        assertNull(store.readCheckpoint())
    }

    @Test
    fun saveRunReplacesThePreviousRunAndCopiesTheProgressList() = runTest {
        val store = InMemoryScanStore()
        assertEquals(ScanRun.IDLE, store.readRun())
        val progress = mutableListOf(TypeProgress(MediaType.Image, scanned = 2, total = 10))
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = progress,
                mediaStoreVersion = null,
                completedVolumeGeneration = null,
                detail = null,
            ),
        )
        progress[0] = TypeProgress(MediaType.Image, scanned = 9, total = 10)
        assertEquals(2, store.readRun().byType.single().scanned)

        store.saveRun(
            ScanRun(
                phase = ScanPhase.Completed,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 10, total = 10)),
                mediaStoreVersion = "v1",
                completedVolumeGeneration = 4,
                detail = null,
            ),
        )
        val completed = store.readRun()
        assertEquals(ScanPhase.Completed, completed.phase)
        assertEquals("v1", completed.mediaStoreVersion)
        assertEquals(4L, completed.completedVolumeGeneration)
    }

    @Test
    fun progressFollowsTheRunAndIgnoresItemAndCheckpointWrites() = runTest {
        val store = InMemoryScanStore()
        val phases = mutableListOf<ScanPhase>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observeProgress().collect { progress -> phases += progress.phase }
        }
        assertEquals(listOf(ScanPhase.Idle), phases)

        store.upsertBatch(listOf(item(MediaType.Image, 1)))
        store.saveCheckpoint(checkpoint(MediaType.Image, id = 1, scanned = 1))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 1)))
        assertEquals(listOf(ScanPhase.Idle), phases)

        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 1, total = 4)),
                mediaStoreVersion = null,
                completedVolumeGeneration = null,
                detail = "reading photos",
            ),
        )
        val latest = store.observeProgress().first()
        assertEquals(ScanPhase.Running, latest.phase)
        assertEquals(1, latest.scanned)
        assertEquals(4, latest.total)
        assertEquals("reading photos", latest.detail)
        assertEquals(listOf(ScanPhase.Idle, ScanPhase.Running), phases)

        store.saveRun(store.readRun())
        assertEquals(listOf(ScanPhase.Idle, ScanPhase.Running), phases)
        collector.cancel()
    }

    @Test
    fun rejectedCheckpointsAndRunsDoNotNeedAStore() {
        assertRejected {
            checkpoint(MediaType.Image, id = -1, scanned = 0)
        }
        assertRejected {
            ScanCheckpoint(
                type = MediaType.Image,
                dateModified = 1,
                id = 1,
                scanned = 0,
                generationModifiedAfter = 5,
                generationModifiedAtMost = 4,
                mediaStoreVersion = "v",
            )
        }
        assertRejected {
            ScanCheckpoint(
                type = MediaType.Image,
                dateModified = 1,
                id = 1,
                scanned = 0,
                generationModifiedAfter = null,
                generationModifiedAtMost = 4,
                mediaStoreVersion = "",
            )
        }
        assertRejected {
            ScanRun(
                phase = ScanPhase.Completed,
                byType = listOf(
                    TypeProgress(MediaType.Image, 1, 1),
                    TypeProgress(MediaType.Image, 2, 2),
                ),
                mediaStoreVersion = "v",
                completedVolumeGeneration = 1,
                detail = null,
            )
        }
        assertRejected {
            ScanRun(
                phase = ScanPhase.Completed,
                byType = emptyList(),
                mediaStoreVersion = null,
                completedVolumeGeneration = 1,
                detail = null,
            )
        }
        assertRejected {
            TypeProgress(MediaType.Audio, scanned = -1, total = 0)
        }
    }

    private fun assertRejected(build: () -> Unit) {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            return
        }
        error("expected IllegalArgumentException")
    }

    private fun item(type: MediaType, id: Long, size: Long = 1) = MediaItem(
        id = id,
        uri = "content://media/external/${type.name.lowercase()}/media/$id",
        type = type,
        dateAdded = 1_700_000_000,
        size = size,
        path = "DCIM/$id",
        mimeType = null,
    )

    private fun checkpoint(type: MediaType, id: Long, scanned: Int) = ScanCheckpoint(
        type = type,
        dateModified = 1_700_000_100,
        id = id,
        scanned = scanned,
        generationModifiedAfter = null,
        generationModifiedAtMost = 4,
        mediaStoreVersion = "v1",
    )
}
