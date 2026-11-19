package com.useinoski.gallerycleanup.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
        store.markKept(listOf(MediaKey(2, MediaType.Image)))
        store.markTrashed(listOf(MediaKey(5, MediaType.Audio)))

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
        store.markKept(listOf(MediaKey(3, MediaType.Image)))
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
        store.markTrashed(listOf(MediaKey(99, MediaType.Audio)))
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
}
