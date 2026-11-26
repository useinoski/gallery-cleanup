package com.useinoski.gallerycleanup.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomScanResultStoreTest {
    @get:Rule
    val timeout: Timeout = Timeout(20, TimeUnit.SECONDS)

    private lateinit var database: GalleryDatabase
    private lateinit var dao: ScanResultDao
    private lateinit var store: RoomScanResultStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GalleryDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.scanResultDao()
        store = RoomScanResultStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertInsertsAndASecondUpsertReplacesTheScore() = runBlocking {
        store.upsert(listOf(sample(id = 4, confidence = 0.25f, category = ScanCategory.Screenshot)))
        store.upsert(
            listOf(
                sample(
                    id = 4,
                    confidence = 0.9f,
                    category = ScanCategory.Sensitive,
                    modelVersion = "nsfw-2",
                    scannedAtEpochMillis = 5_000,
                ),
            ),
        )

        val shown = store.observePending(ScanCategory.Sensitive).first()
        assertEquals(1, shown.size)
        assertEquals(0.9f, shown.single().confidence, 0.0001f)
        assertEquals("nsfw-2", shown.single().modelVersion)
        assertEquals(5_000L, shown.single().scannedAtEpochMillis)
        assertEquals(ReviewStatus.Pending, shown.single().reviewStatus)
        assertTrue(store.observePending(ScanCategory.Screenshot).first().isEmpty())
    }

    @Test
    fun theSameIdInImageAndVideoAreDifferentRows() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 5, type = MediaType.Image, category = ScanCategory.Screenshot),
                sample(id = 5, type = MediaType.Video, category = ScanCategory.Video),
            ),
        )
        assertEquals(1, store.observePending(ScanCategory.Screenshot).first().size)
        assertEquals(1, store.observePending(ScanCategory.Video).first().size)
    }

    @Test
    fun theLastDuplicateInABatchWins() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 3, confidence = 0.1f, category = ScanCategory.Immodest),
                sample(id = 3, confidence = 0.8f, category = ScanCategory.People),
            ),
        )
        val shown = store.observePending(ScanCategory.People).first()
        assertEquals(0.8f, shown.single().confidence, 0.0001f)
    }

    @Test
    fun anEmptyUpsertDoesNothing() = runBlocking {
        store.upsert(emptyList())
        assertTrue(store.keptKeys().isEmpty())
        assertEquals(0, store.observePendingCounts().first().flagged)
    }

    @Test
    fun upsertIgnoresTheIncomingStatusAndStoresPending() = runBlocking {
        store.upsert(listOf(sample(id = 8, reviewStatus = ReviewStatus.Kept)))
        assertTrue(store.keptKeys().isEmpty())
        assertEquals(ReviewStatus.Pending, store.observePending(ScanCategory.Screenshot).first().single().reviewStatus)
    }

    @Test
    fun keptItemsAreSkippedWhenTheScanWritesThemAgain() = runBlocking {
        val original = sample(
            id = 12,
            category = ScanCategory.Screenshot,
            confidence = 0.4f,
            modelVersion = "screenshot-rules-1",
            scannedAtEpochMillis = 100,
            uri = "content://media/external/images/media/12",
        )
        store.upsert(listOf(original))
        store.markKept(listOf(original.key))

        store.upsert(
            listOf(
                original.copy(
                    category = ScanCategory.Sensitive,
                    confidence = 0.99f,
                    modelVersion = "nsfw-9",
                    scannedAtEpochMillis = 999,
                    contentUri = "content://media/external/images/media/12?changed",
                    reviewStatus = ReviewStatus.Pending,
                ),
                sample(id = 13, category = ScanCategory.People),
            ),
        )

        assertEquals(setOf(original.key), store.keptKeys())
        assertTrue(store.observePending(ScanCategory.Sensitive).first().isEmpty())
        assertEquals(1, store.observePending(ScanCategory.People).first().size)
        val stored = dao.find(12, MediaType.Image)
        assertEquals(ScanCategory.Screenshot, stored!!.category)
        assertEquals(0.4f, stored.confidence, 0.0001f)
        assertEquals("screenshot-rules-1", stored.modelVersion)
        assertEquals(100L, stored.scannedAtEpochMillis)
        assertEquals("content://media/external/images/media/12", stored.contentUri)
        assertEquals(ReviewStatus.Kept, stored.reviewStatus)
    }

    @Test
    fun resetReviewedMakesKeptItemsPendingAndWritableAgain() = runBlocking {
        val kept = sample(id = 2, category = ScanCategory.Audio, type = MediaType.Audio)
        val trashed = sample(id = 3, category = ScanCategory.Video, type = MediaType.Video)
        store.upsert(listOf(kept, trashed))
        store.markKept(listOf(kept.key))
        store.markTrashed(listOf(trashed.key))

        store.resetReviewed()

        assertTrue(store.keptKeys().isEmpty())
        val pendingAudio = store.observePending(ScanCategory.Audio).first()
        assertEquals(kept.key, pendingAudio.single().key)
        assertEquals(ReviewStatus.Pending, pendingAudio.single().reviewStatus)
        assertEquals(ReviewStatus.Trashed, dao.find(3, MediaType.Video)!!.reviewStatus)
        assertTrue(store.observePending(ScanCategory.Video).first().isEmpty())

        store.upsert(
            listOf(kept.copy(confidence = 0.2f, category = ScanCategory.Immodest, scannedAtEpochMillis = 50)),
        )
        val replaced = store.observePending(ScanCategory.Immodest).first().single()
        assertEquals(0.2f, replaced.confidence, 0.0001f)
        assertEquals(ReviewStatus.Pending, replaced.reviewStatus)
    }

    @Test
    fun pendingCountsIgnoreKeptAndTrashedAndIncludeZeros() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 1, category = ScanCategory.Screenshot),
                sample(id = 2, category = ScanCategory.Screenshot, scannedAtEpochMillis = 2),
                sample(id = 3, category = ScanCategory.Sensitive),
                sample(id = 4, category = ScanCategory.Unflagged, confidence = 0f, modelVersion = "none"),
                sample(id = 5, category = ScanCategory.Audio, type = MediaType.Audio),
                sample(id = 6, category = ScanCategory.People),
            ),
        )
        store.markKept(listOf(MediaKey(MediaType.Image, 2)))
        store.markTrashed(listOf(MediaKey(MediaType.Audio, 5)))

        val counts = store.observePendingCounts().first()
        assertEquals(1, counts[ScanCategory.Screenshot])
        assertEquals(1, counts[ScanCategory.Sensitive])
        assertEquals(1, counts[ScanCategory.Unflagged])
        assertEquals(1, counts[ScanCategory.People])
        assertEquals(0, counts[ScanCategory.Audio])
        assertEquals(0, counts[ScanCategory.Immodest])
        assertEquals(0, counts[ScanCategory.Video])
        assertEquals(0, counts[ScanCategory.AlcoholGambling])
        assertEquals(3, counts.flagged)
        assertEquals(ScanCategory.entries.toSet(), counts.pending.keys)
    }

    @Test
    fun pendingListsAreNewestScanFirstAndHideOtherStatuses() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 1, scannedAtEpochMillis = 10),
                sample(id = 2, scannedAtEpochMillis = 30),
                sample(id = 3, scannedAtEpochMillis = 20),
            ),
        )
        store.markKept(listOf(MediaKey(MediaType.Image, 3)))
        val shown = store.observePending(ScanCategory.Screenshot).first()
        assertEquals(listOf(2L, 1L), shown.map { it.mediaStoreId })
    }

    @Test
    fun trashHidesTheRowAndRemoveDeletesIt() = runBlocking {
        val item = sample(id = 7, category = ScanCategory.AlcoholGambling, confidence = 0.3f)
        store.upsert(listOf(item))
        store.markTrashed(listOf(item.key))

        assertTrue(store.observePending(ScanCategory.AlcoholGambling).first().isEmpty())
        assertEquals(0, store.observePendingCounts().first()[ScanCategory.AlcoholGambling])
        assertTrue(store.keptKeys().isEmpty())
        assertEquals(ReviewStatus.Trashed, dao.find(7, MediaType.Image)!!.reviewStatus)

        store.markPending(listOf(item.key))
        assertEquals(item.key, store.observePending(ScanCategory.AlcoholGambling).first().single().key)

        store.markTrashed(listOf(item.key))
        store.remove(listOf(item.key))
        assertNull(dao.find(7, MediaType.Image))
        assertEquals(0, store.observePendingCounts().first().flagged)
    }

    @Test
    fun missingKeysAndEmptyUpdatesAreIgnored() = runBlocking {
        store.markKept(emptyList())
        store.markTrashed(listOf(MediaKey(MediaType.Audio, 99)))
        store.remove(emptyList())
        store.resetReviewed()
        assertTrue(store.keptKeys().isEmpty())
    }

    @Test
    fun batchesLargerThanOneSqlChunkRoundTrip() = runBlocking {
        val rows = (1..501).map { id ->
            sample(
                id = id.toLong(),
                category = if (id % 2 == 0) ScanCategory.Screenshot else ScanCategory.Sensitive,
            )
        }
        store.upsert(rows)
        assertEquals(501, store.observePendingCounts().first().flagged)

        store.markKept(rows.map { it.key })
        assertEquals(501, store.keptKeys().size)
        assertEquals(0, store.observePendingCounts().first().flagged)

        store.remove(rows.map { it.key })
        assertNull(dao.find(1, MediaType.Image))
        assertNull(dao.find(501, MediaType.Image))
        assertTrue(store.keptKeys().isEmpty())
    }

    @Test
    fun removedItemsLeaveCountsAndPendingListsUntilTheFileReturns() = runBlocking {
        val rows = listOf(
            sample(id = 1, category = ScanCategory.Screenshot, scannedAtEpochMillis = 10),
            sample(id = 2, category = ScanCategory.Screenshot, scannedAtEpochMillis = 30),
            sample(id = 3, category = ScanCategory.Sensitive, scannedAtEpochMillis = 20),
        )
        store.upsert(rows)
        // No media_item yet: a review row is still counted. Removed is a flag
        // on a row that exists, not "missing from the library table".
        assertEquals(2, store.observePendingCounts().first()[ScanCategory.Screenshot])
        assertEquals(3, store.observePendingCounts().first().flagged)

        store.upsertBatch(rows.map { row -> media(row.mediaStoreId) })
        assertEquals(listOf(2L, 1L), pendingIds(ScanCategory.Screenshot))
        assertEquals(1, store.observePending(ScanCategory.Sensitive).first().size)

        store.markRemoved(listOf(MediaKey(MediaType.Image, 2)))

        assertEquals(listOf(1L), pendingIds(ScanCategory.Screenshot))
        assertEquals(1, store.observePendingCounts().first()[ScanCategory.Screenshot])
        assertEquals(1, store.observePendingCounts().first()[ScanCategory.Sensitive])
        assertEquals(2, store.observePendingCounts().first().flagged)
        assertEquals(ReviewStatus.Pending, dao.find(2, MediaType.Image)!!.reviewStatus)

        store.upsertBatch(listOf(media(2)))
        assertEquals(listOf(2L, 1L), pendingIds(ScanCategory.Screenshot))
        assertEquals(2, store.observePendingCounts().first()[ScanCategory.Screenshot])
        assertEquals(3, store.observePendingCounts().first().flagged)
    }

    @Test
    fun countsAndPendingListsEmitAgainWhenRemovedChanges() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 1, category = ScanCategory.Screenshot),
                sample(id = 2, category = ScanCategory.People),
            ),
        )
        store.upsertBatch(listOf(media(1), media(2)))
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch {
            store.observePendingCounts().collect { counts -> seen += counts.flagged }
        }
        awaitUntil { seen.isNotEmpty() }
        assertEquals(2, seen.last())

        store.markRemoved(listOf(MediaKey(MediaType.Image, 1)))
        awaitUntil { seen.last() == 1 }
        assertEquals(listOf(2L), pendingIds(ScanCategory.People))
        assertTrue(store.observePending(ScanCategory.Screenshot).first().isEmpty())

        store.upsertBatch(listOf(media(1)))
        awaitUntil { seen.last() == 2 }
        job.cancel()
    }

    @Test
    fun aRemovedKeptRowIsStillNotOverwritten() = runBlocking {
        val original = sample(
            id = 12,
            category = ScanCategory.Screenshot,
            confidence = 0.4f,
            modelVersion = "screenshot-rules-1",
            scannedAtEpochMillis = 100,
            uri = "content://media/external/images/media/12",
        )
        store.upsert(listOf(original))
        store.markKept(listOf(original.key))
        store.upsertBatch(listOf(media(12)))
        store.markRemoved(listOf(original.key))

        store.upsert(
            listOf(
                original.copy(
                    category = ScanCategory.Sensitive,
                    confidence = 0.99f,
                    modelVersion = "nsfw-9",
                    scannedAtEpochMillis = 999,
                    contentUri = "content://media/external/images/media/12?changed",
                ),
            ),
        )
        store.recordFailure(
            key = original.key,
            contentUri = "content://media/external/images/media/12?failed",
            scannedAtEpochMillis = 1_000,
        )

        assertEquals(setOf(original.key), store.keptKeys())
        assertTrue(store.observePending(ScanCategory.Sensitive).first().isEmpty())
        assertEquals(0, store.observePendingCounts().first().flagged)
        val stored = dao.find(12, MediaType.Image)
        assertEquals(ScanCategory.Screenshot, stored!!.category)
        assertEquals(0.4f, stored.confidence, 0.0001f)
        assertEquals("screenshot-rules-1", stored.modelVersion)
        assertEquals(100L, stored.scannedAtEpochMillis)
        assertEquals("content://media/external/images/media/12", stored.contentUri)
        assertEquals(ReviewStatus.Kept, stored.reviewStatus)
        assertEquals(AnalysisOutcome.Scored, stored.analysisOutcome)

        store.upsertBatch(listOf(media(12)))
        assertTrue(store.observePending(ScanCategory.Screenshot).first().isEmpty())
        assertEquals(ReviewStatus.Kept, dao.find(12, MediaType.Image)!!.reviewStatus)
    }

    @Test
    fun aFailedAnalysisIsNotCountedAsCleanAndComesBackAfterAScore() = runBlocking {
        store.upsert(
            listOf(
                sample(
                    id = 1,
                    category = ScanCategory.Unflagged,
                    confidence = 0f,
                    modelVersion = MODEL_VERSION_NONE,
                ),
            ),
        )
        store.upsertBatch(listOf(media(1), media(4)))
        assertEquals(1, store.observePendingCounts().first()[ScanCategory.Unflagged])

        store.recordFailure(
            key = MediaKey(MediaType.Image, 4),
            contentUri = "content://media/external/images/media/4",
        )
        val failed = dao.find(4, MediaType.Image)
        assertEquals(AnalysisOutcome.Failed, failed!!.analysisOutcome)
        assertEquals(ScanCategory.Unflagged, failed.category)
        assertEquals(MODEL_VERSION_NONE, failed.modelVersion)
        assertEquals(ReviewStatus.Pending, failed.reviewStatus)
        assertTrue(failed.scannedAtEpochMillis > 0L)

        assertEquals(1, store.observePendingCounts().first()[ScanCategory.Unflagged])
        assertEquals(0, store.observePendingCounts().first().flagged)
        assertTrue(store.observePending(ScanCategory.Unflagged).first().none { it.mediaStoreId == 4L })
        assertEquals(
            setOf(MediaKey(MediaType.Image, 4)),
            store.keysNeedingRescan(setOf(MODEL_VERSION_NONE, "screenshot-rules-1")).toSet(),
        )

        store.recordFailure(
            key = MediaKey(MediaType.Image, 1),
            contentUri = "content://media/external/images/media/1",
            scannedAtEpochMillis = 80,
        )
        assertEquals(0, store.observePendingCounts().first()[ScanCategory.Unflagged])
        assertTrue(store.observePending(ScanCategory.Unflagged).first().isEmpty())
        assertEquals(AnalysisOutcome.Failed, dao.find(1, MediaType.Image)!!.analysisOutcome)

        store.upsert(
            listOf(
                sample(
                    id = 4,
                    category = ScanCategory.People,
                    modelVersion = "mlkit-face-count|face-v2",
                    scannedAtEpochMillis = 90,
                ),
            ),
        )
        assertEquals(AnalysisOutcome.Scored, dao.find(4, MediaType.Image)!!.analysisOutcome)
        assertEquals(1, store.observePending(ScanCategory.People).first().size)
        assertFalse(
            store.keysNeedingRescan(setOf(MODEL_VERSION_NONE, "mlkit-face-count|face-v2"))
                .contains(MediaKey(MediaType.Image, 4)),
        )
        assertTrue(
            store.keysNeedingRescan(setOf(MODEL_VERSION_NONE, "mlkit-face-count|face-v2"))
                .contains(MediaKey(MediaType.Image, 1)),
        )
    }

    @Test
    fun rescanKeysIncludeStaleVersionsAndSkipKeptAndRemovedRows() = runBlocking {
        store.upsert(
            listOf(
                sample(
                    id = 1,
                    category = ScanCategory.Unflagged,
                    confidence = 0f,
                    modelVersion = MODEL_VERSION_NONE,
                ),
                sample(id = 2, category = ScanCategory.People, modelVersion = "mlkit-face-count|face-v1"),
                sample(id = 3, category = ScanCategory.Screenshot, modelVersion = "screenshot-rules-1"),
            ),
        )
        store.recordFailures(
            listOf(
                AnalysisFailure(
                    key = MediaKey(MediaType.Image, 4),
                    contentUri = "content://media/external/images/media/4",
                    scannedAtEpochMillis = 40,
                ),
                AnalysisFailure(
                    key = MediaKey(MediaType.Image, 4),
                    contentUri = "content://media/external/images/media/4b",
                    scannedAtEpochMillis = 41,
                ),
                AnalysisFailure(
                    key = MediaKey(MediaType.Image, 6),
                    contentUri = "content://media/external/images/media/6",
                    scannedAtEpochMillis = 60,
                ),
            ),
        )
        store.upsertBatch((1L..4L).map { id -> media(id) } + media(6))
        store.markKept(listOf(MediaKey(MediaType.Image, 3)))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 6)))

        val current = setOf(MODEL_VERSION_NONE, "mlkit-face-count|face-v1", "screenshot-rules-1")
        assertEquals(setOf(MediaKey(MediaType.Image, 4)), store.keysNeedingRescan(current).toSet())
        assertEquals("content://media/external/images/media/4b", dao.find(4, MediaType.Image)!!.contentUri)

        val bumped = setOf(MODEL_VERSION_NONE, "mlkit-face-count|face-v2", "screenshot-rules-1")
        assertEquals(
            setOf(MediaKey(MediaType.Image, 2), MediaKey(MediaType.Image, 4)),
            store.keysNeedingRescan(bumped).toSet(),
        )
        assertEquals(
            store.keysNeedingRescan(setOf("mlkit-face-count|face-v2")).toSet(),
            store.keysNeedingRescan("mlkit-face-count|face-v2").toSet(),
        )
        val oneDetector = store.keysNeedingRescan("mlkit-face-count|face-v2").toSet()
        assertTrue(oneDetector.contains(MediaKey(MediaType.Image, 1)))
        assertTrue(oneDetector.contains(MediaKey(MediaType.Image, 2)))
        assertTrue(oneDetector.contains(MediaKey(MediaType.Image, 4)))
        assertFalse(oneDetector.contains(MediaKey(MediaType.Image, 3)))
        assertFalse(oneDetector.contains(MediaKey(MediaType.Image, 6)))

        store.upsertBatch(listOf(media(6)))
        assertTrue(store.keysNeedingRescan(bumped).contains(MediaKey(MediaType.Image, 6)))
    }

    @Test
    fun recordFailureDoesNotReplaceAKeptRow() = runBlocking {
        val original = sample(id = 8, category = ScanCategory.Audio, type = MediaType.Audio, confidence = 1f)
        store.upsert(listOf(original))
        store.markKept(listOf(original.key))

        store.recordFailure(
            key = original.key,
            contentUri = "content://media/external/audio/media/8?failed",
            scannedAtEpochMillis = 5_000,
        )

        val stored = dao.find(8, MediaType.Audio)
        assertEquals(ReviewStatus.Kept, stored!!.reviewStatus)
        assertEquals(AnalysisOutcome.Scored, stored.analysisOutcome)
        assertEquals(ScanCategory.Audio, stored.category)
        assertEquals(original.contentUri, stored.contentUri)
        assertEquals(original.modelVersion, stored.modelVersion)
        assertFalse(store.keysNeedingRescan(setOf("some-other-version")).contains(original.key))
    }

    @Test
    fun blankFailureAndEmptyRescanVersionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.recordFailure(MediaKey(MediaType.Image, 1), " ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.recordFailure(MediaKey(MediaType.Image, 0), "content://media/1") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.keysNeedingRescan(emptyList()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.keysNeedingRescan(" ") }
        }
        runBlocking {
            store.recordFailures(emptyList())
            assertTrue(store.keptKeys().isEmpty())
        }
    }

    private suspend fun pendingIds(category: ScanCategory): List<Long> {
        return store.observePending(category).first().map { row -> row.mediaStoreId }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) {
            if (System.nanoTime() > deadline) error("timed out waiting for pending counts")
            delay(20)
        }
    }

    private fun media(id: Long, type: MediaType = MediaType.Image) = MediaItem(
        id = id,
        uri = "content://media/external/${type.name.lowercase()}/media/$id",
        type = type,
        dateAdded = 1_700_000_000,
        size = 1,
        path = "DCIM/$id",
        mimeType = null,
    )
}
