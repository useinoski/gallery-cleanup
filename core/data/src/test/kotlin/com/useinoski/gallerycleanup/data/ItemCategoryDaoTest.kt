package com.useinoski.gallerycleanup.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemCategoryDaoTest {
    @get:Rule
    val timeout: Timeout = Timeout(20, TimeUnit.SECONDS)

    private lateinit var database: GalleryDatabase
    private lateinit var categories: ItemCategoryDao
    private lateinit var store: RoomScanResultStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GalleryDatabase::class.java,
        ).allowMainThreadQueries().build()
        categories = database.itemCategoryDao()
        store = RoomScanResultStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replaceWritesTheSetAndASecondCallDropsWhatLeft() = runBlocking {
        val key = MediaKey(MediaType.Image, 4)
        store.upsert(listOf(sample(id = 4, category = ScanCategory.Screenshot)))
        categories.replace(
            mediaStoreId = 4,
            mediaType = MediaType.Image,
            categories = setOf(MediaCategory.Screenshots, MediaCategory.Sensitive),
        )

        assertEquals(
            setOf("screenshots", "sensitive"),
            rawCategoryIds(4, MediaType.Image),
        )
        assertTrue(MediaCategory.Experimental.experimental)
        assertEquals(
            setOf(MediaCategory.Screenshots, MediaCategory.Sensitive),
            categories.categoryIds(4, MediaType.Image).toSet(),
        )
        assertEquals(listOf(4L), pendingIds(MediaCategory.Screenshots))
        assertEquals(listOf(4L), pendingIds(MediaCategory.Sensitive))
        assertTrue(pendingIds(MediaCategory.People).isEmpty())

        categories.replace(
            mediaStoreId = 4,
            mediaType = MediaType.Image,
            categories = setOf(MediaCategory.People, MediaCategory.Experimental),
        )
        assertEquals(
            setOf("people", "experimental"),
            rawCategoryIds(4, MediaType.Image),
        )
        assertEquals(
            setOf(MediaCategory.People, MediaCategory.Experimental),
            categories.categoryIds(4, MediaType.Image).toSet(),
        )
        assertTrue(pendingIds(MediaCategory.Screenshots).isEmpty())
        assertTrue(pendingIds(MediaCategory.Sensitive).isEmpty())
        assertEquals(listOf(4L), pendingIds(MediaCategory.People))
        assertEquals(listOf(4L), pendingIds(MediaCategory.Experimental))
        assertEquals(listOf(4L), store.observePending(MediaCategory.Experimental).first().map { it.mediaStoreId })
        assertEquals(ScanCategory.Screenshot, database.scanResultDao().find(4, MediaType.Image)!!.category)
        assertEquals(key, store.observePending(MediaCategory.People).first().single().key)
    }

    @Test
    fun emptyReplaceDeletesCategoryRowsAndLeavesTheScanResult() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 8, category = ScanCategory.Unflagged, confidence = 0f, modelVersion = MODEL_VERSION_NONE),
            ),
        )
        categories.replace(8, MediaType.Image, setOf(MediaCategory.Screenshots, MediaCategory.ChatImages))
        categories.replace(8, MediaType.Image, emptySet())

        assertTrue(rawCategoryIds(8, MediaType.Image).isEmpty())
        assertTrue(categories.categoryIds(8, MediaType.Image).isEmpty())
        val stored = database.scanResultDao().find(8, MediaType.Image)
        assertEquals(ScanCategory.Unflagged, stored!!.category)
        assertEquals(ReviewStatus.Pending, stored.reviewStatus)
        assertEquals(AnalysisOutcome.Scored, stored.analysisOutcome)
        assertEquals(0, stored.failureCount)
        assertEquals(MODEL_VERSION_NONE, stored.modelVersion)
        MediaCategory.entries.forEach { category ->
            assertTrue(pendingIds(category).isEmpty())
        }
    }

    @Test
    fun observePendingListsScoredPresentItemsInThatCategory() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 1, category = ScanCategory.Screenshot, scannedAtEpochMillis = 30),
                sample(id = 2, category = ScanCategory.Screenshot, scannedAtEpochMillis = 10),
                sample(id = 3, category = ScanCategory.Screenshot, scannedAtEpochMillis = 40),
                sample(id = 6, category = ScanCategory.People, scannedAtEpochMillis = 20),
                sample(id = 7, category = ScanCategory.Unflagged, confidence = 0f, modelVersion = "old-rule"),
                sample(id = 8, category = ScanCategory.Screenshot, scannedAtEpochMillis = 50),
            ),
        )
        store.upsert(listOf(sample(id = 4, category = ScanCategory.Sensitive, scannedAtEpochMillis = 60)))
        store.markKept(listOf(MediaKey(MediaType.Image, 3)))
        store.markTrashed(listOf(MediaKey(MediaType.Image, 4)))
        store.recordFailure(
            key = MediaKey(MediaType.Image, 5),
            contentUri = "content://media/external/images/media/5",
            scannedAtEpochMillis = 70,
        )
        listOf(1L, 2L, 3L, 4L, 6L, 8L).forEach { id ->
            categories.replace(id, MediaType.Image, setOf(MediaCategory.Screenshots))
        }
        categories.replace(
            6,
            MediaType.Image,
            setOf(MediaCategory.Screenshots, MediaCategory.Sensitive),
        )
        categories.replace(5, MediaType.Image, setOf(MediaCategory.Sensitive))
        categories.replace(4, MediaType.Image, setOf(MediaCategory.Sensitive))
        store.upsertBatch(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L).map { id -> media(id) })
        store.markRemoved(listOf(MediaKey(MediaType.Image, 8)))

        assertEquals(listOf(1L, 6L, 2L), pendingIds(MediaCategory.Screenshots))
        assertEquals(listOf(6L), pendingIds(MediaCategory.Sensitive))
        assertTrue(pendingIds(MediaCategory.People).isEmpty())
        assertTrue(pendingIds(MediaCategory.Music).isEmpty())
        assertEquals(setOf("screenshots"), rawCategoryIds(8, MediaType.Image))
        assertEquals(setOf(MediaCategory.Sensitive), categories.categoryIds(5, MediaType.Image).toSet())
        val failed = database.scanResultDao().find(5, MediaType.Image)
        assertEquals(AnalysisOutcome.Failed, failed!!.analysisOutcome)
        assertEquals(1, failed.failureCount)
        assertEquals(ReviewStatus.Pending, failed.reviewStatus)
    }

    @Test
    fun observeEmitsAgainWhenTheFileIsRemovedAndReturns() = runBlocking {
        store.upsert(listOf(sample(id = 1, category = ScanCategory.Screenshot)))
        store.upsertBatch(listOf(media(1)))
        categories.replace(1, MediaType.Image, setOf(MediaCategory.Screenshots))

        val seen = CopyOnWriteArrayList<List<Long>>()
        val job = launch {
            categories.observePending(
                category = MediaCategory.Screenshots,
                status = ReviewStatus.Pending,
                failedOutcome = AnalysisOutcome.Failed,
            ).collect { rows -> seen += rows.map { row -> row.mediaStoreId } }
        }
        awaitUntil { seen.lastOrNull() == listOf(1L) }

        store.markRemoved(listOf(MediaKey(MediaType.Image, 1)))
        awaitUntil { seen.last().isEmpty() }
        assertEquals(setOf(MediaCategory.Screenshots), categories.categoryIds(1, MediaType.Image).toSet())

        store.upsertBatch(listOf(media(1)))
        awaitUntil { seen.last() == listOf(1L) }
        job.cancel()
    }

    @Test
    fun failedRowKeepsOutcomeAndFailureCountAcrossReplace() = runBlocking {
        val key = MediaKey(MediaType.Image, 40)
        store.recordFailure(key, "content://media/external/images/media/40", 4_000)
        store.recordFailure(key, "content://media/external/images/media/40", 4_100)
        categories.replace(40, MediaType.Image, setOf(MediaCategory.Sensitive, MediaCategory.People))

        val withCategories = database.scanResultDao().find(40, MediaType.Image)
        assertEquals(AnalysisOutcome.Failed, withCategories!!.analysisOutcome)
        assertEquals(2, withCategories.failureCount)
        assertEquals(ReviewStatus.Pending, withCategories.reviewStatus)
        assertEquals(ScanCategory.Unflagged, withCategories.category)
        assertEquals(MODEL_VERSION_NONE, withCategories.modelVersion)
        assertTrue(pendingIds(MediaCategory.Sensitive).isEmpty())
        assertEquals(
            listOf(key),
            store.keysNeedingRescan(listOf("screenshot-rules-1", MODEL_VERSION_NONE)),
        )

        categories.replace(40, MediaType.Image, emptySet())
        val cleared = database.scanResultDao().find(40, MediaType.Image)
        assertEquals(AnalysisOutcome.Failed, cleared!!.analysisOutcome)
        assertEquals(2, cleared.failureCount)
        assertEquals(ScanCategory.Unflagged, cleared.category)
        assertTrue(categories.categoryIds(40, MediaType.Image).isEmpty())
        assertEquals(
            listOf(key),
            store.keysNeedingRescan(listOf("screenshot-rules-1", MODEL_VERSION_NONE)),
        )
        MediaCategory.entries.forEach { category ->
            assertTrue(pendingIds(category).isEmpty())
        }
    }

    @Test
    fun removeDeletesCategoryRowsForThatKeyOnly() = runBlocking {
        store.upsert(
            listOf(
                sample(id = 5, type = MediaType.Image, category = ScanCategory.Screenshot),
                sample(id = 5, type = MediaType.Video, category = ScanCategory.Video),
                sample(id = 6, type = MediaType.Image, category = ScanCategory.People),
            ),
        )
        categories.replace(5, MediaType.Image, setOf(MediaCategory.Screenshots, MediaCategory.Sensitive))
        categories.replace(5, MediaType.Video, setOf(MediaCategory.Videos, MediaCategory.People))
        categories.replace(6, MediaType.Image, setOf(MediaCategory.People))

        store.remove(listOf(MediaKey(MediaType.Image, 5)))

        assertTrue(categories.categoryIds(5, MediaType.Image).isEmpty())
        assertNull(database.scanResultDao().find(5, MediaType.Image))
        assertEquals(
            setOf(MediaCategory.Videos, MediaCategory.People),
            categories.categoryIds(5, MediaType.Video).toSet(),
        )
        assertEquals(
            setOf(MediaCategory.People),
            categories.categoryIds(6, MediaType.Image).toSet(),
        )
        assertEquals(ReviewStatus.Pending, database.scanResultDao().find(5, MediaType.Video)!!.reviewStatus)
        assertEquals(listOf(5L), pendingIds(MediaCategory.Videos))
    }

    @Test
    fun accessRevokedStaysOnTheRunAndThePendingItemStaysVisible() = runBlocking {
        store.saveRun(
            ScanRun(
                phase = ScanPhase.AccessRevoked,
                byType = emptyList(),
                mediaStoreVersion = "v3",
                completedVolumeGeneration = null,
                detail = "media access was removed",
            ),
        )
        store.upsert(listOf(sample(id = 2, category = ScanCategory.Screenshot)))
        categories.replace(2, MediaType.Image, setOf(MediaCategory.Screenshots))

        assertEquals(ScanPhase.AccessRevoked, store.readRun().phase)
        assertEquals("media access was removed", store.readRun().detail)
        assertEquals(listOf(2L), pendingIds(MediaCategory.Screenshots))
        assertEquals(setOf("screenshots"), rawCategoryIds(2, MediaType.Image))
        val everyId = database.openHelper.readableDatabase.stringSet(
            "SELECT DISTINCT category_id FROM item_category",
        )
        assertEquals(setOf("screenshots"), everyId)
    }

    private suspend fun pendingIds(category: MediaCategory): List<Long> {
        return categories.observePending(
            category = category,
            status = ReviewStatus.Pending,
            failedOutcome = AnalysisOutcome.Failed,
        ).first().map { row -> row.mediaStoreId }
    }

    private fun rawCategoryIds(id: Long, type: MediaType): Set<String> {
        return database.openHelper.readableDatabase.query(
            "SELECT category_id FROM item_category WHERE media_store_id = ? AND media_type = ?",
            arrayOf<Any>(id, type.name),
        ).use { cursor ->
            val ids = mutableSetOf<String>()
            while (cursor.moveToNext()) ids += cursor.getString(0)
            ids
        }
    }

    private fun SupportSQLiteDatabase.stringSet(sql: String): Set<String> {
        return query(sql).use { cursor ->
            val values = mutableSetOf<String>()
            while (cursor.moveToNext()) values += cursor.getString(0)
            values
        }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) {
            if (System.nanoTime() > deadline) error("timed out waiting for category flow")
            delay(20)
        }
    }

    private fun media(id: Long) = MediaItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = MediaType.Image,
        dateAdded = 1_700_000_000,
        size = 1,
        path = "DCIM/$id",
        mimeType = null,
    )
}
