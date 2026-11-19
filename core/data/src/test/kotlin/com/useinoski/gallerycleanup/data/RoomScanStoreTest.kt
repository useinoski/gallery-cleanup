package com.useinoski.gallerycleanup.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.ScanStore
import com.useinoski.gallerycleanup.model.TypeProgress
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomScanStoreTest {
    @get:Rule
    val timeout: Timeout = Timeout(20, TimeUnit.SECONDS)

    private lateinit var database: GalleryDatabase
    private lateinit var store: RoomScanResultStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GalleryDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomScanResultStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun theRoomStoreIsAScanStore() {
        val asContract: ScanStore = store
        assertTrue(asContract === store)
    }

    @Test
    fun upsertInsertsAndReplacesByTypeAndId() = runBlocking {
        store.upsertBatch(listOf(item(MediaType.Image, 1, size = 10), item(MediaType.Audio, 1, size = 3)))
        store.upsertBatch(
            listOf(
                item(MediaType.Image, 1, size = 11),
                item(MediaType.Image, 1, size = 12),
            ),
        )
        assertEquals(12, stored(MediaType.Image, 1).size)
        assertEquals(item(MediaType.Image, 1).uri, stored(MediaType.Image, 1).uri)
        assertEquals(3, stored(MediaType.Audio, 1).size)
        assertEquals(listOf(MediaKey(MediaType.Image, 1)), store.presentKeys(MediaType.Image))
        assertEquals(listOf(MediaKey(MediaType.Audio, 1)), store.presentKeys(MediaType.Audio))
        assertTrue(store.presentKeys(MediaType.Video).isEmpty())
    }

    @Test
    fun emptyWritesAreNoOps() = runBlocking {
        store.upsertBatch(emptyList())
        store.markRemoved(emptyList())
        store.clearCheckpoint()
        assertEquals(ScanRun.IDLE, store.readRun())
        assertNull(store.readCheckpoint())
        assertTrue(store.presentKeys(MediaType.Video).isEmpty())
    }

    @Test
    fun markRemovedHidesTheRowUntilItIsUpsertedAgain() = runBlocking {
        val image = item(MediaType.Image, 4)
        store.upsertBatch(listOf(image, item(MediaType.Image, 5)))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 4), MediaKey(MediaType.Video, 4)))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 4)))
        assertEquals(listOf(MediaKey(MediaType.Image, 5)), store.presentKeys(MediaType.Image))
        assertTrue(database.scanStoreDao().findMedia(MediaType.Image, 4)!!.removed)

        store.upsertBatch(listOf(image.copy(size = 9)))
        assertEquals(9, stored(MediaType.Image, 4).size)
        assertEquals(false, stored(MediaType.Image, 4).removed)
        assertEquals(
            setOf(MediaKey(MediaType.Image, 4), MediaKey(MediaType.Image, 5)),
            store.presentKeys(MediaType.Image).toSet(),
        )
    }

    @Test
    fun aLargeRemovalListIsChunked() = runBlocking {
        val items = (1..600).map { id -> item(MediaType.Image, id.toLong(), size = id.toLong()) }
        store.upsertBatch(items)
        store.markRemoved(items.map { media -> MediaKey(media.type, media.id) })
        assertTrue(store.presentKeys(MediaType.Image).isEmpty())
        store.upsertBatch(listOf(item(MediaType.Image, 600, size = 7)))
        assertEquals(listOf(MediaKey(MediaType.Image, 600)), store.presentKeys(MediaType.Image))
        assertEquals(7, stored(MediaType.Image, 600).size)
    }

    @Test
    fun enumsAreStoredByName() = runBlocking {
        store.upsertBatch(listOf(item(MediaType.Video, 8)))
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Video, scanned = 1, total = 4)),
                mediaStoreVersion = null,
                completedVolumeGeneration = null,
                detail = null,
            ),
        )
        val db = database.openHelper.readableDatabase
        db.query("SELECT type, removed FROM media_item").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Video", cursor.getString(0))
            assertEquals(0, cursor.getLong(1))
        }
        db.query("SELECT phase FROM scan_run").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Running", cursor.getString(0))
        }
    }

    @Test
    fun checkpointIsASingleSlot() = runBlocking {
        assertNull(store.readCheckpoint())
        store.saveCheckpoint(checkpoint(MediaType.Image, id = 3, scanned = 3, after = null))
        store.saveCheckpoint(
            checkpoint(MediaType.Video, id = 8, scanned = 1, after = 2L).copy(
                generationModifiedAtMost = 9,
                mediaStoreVersion = "volumes:v2",
            ),
        )
        val saved = store.readCheckpoint()
        assertEquals(MediaType.Video, saved!!.type)
        assertEquals(8, saved.id)
        assertEquals(1, saved.scanned)
        assertEquals(2L, saved.generationModifiedAfter)
        assertEquals(9L, saved.generationModifiedAtMost)
        assertEquals("volumes:v2", saved.mediaStoreVersion)
        store.clearCheckpoint()
        store.clearCheckpoint()
        assertNull(store.readCheckpoint())
    }

    @Test
    fun saveRunReplacesThePreviousRunAndCopiesTheProgressList() = runBlocking {
        assertEquals(ScanRun.IDLE, store.readRun())
        val progress = mutableListOf(
            TypeProgress(MediaType.Video, scanned = 2, total = 10),
            TypeProgress(MediaType.Image, scanned = 1, total = 3),
        )
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = progress,
                mediaStoreVersion = null,
                completedVolumeGeneration = null,
                detail = null,
            ),
        )
        progress[0] = TypeProgress(MediaType.Video, scanned = 9, total = 10)
        progress.removeAt(1)
        val saved = store.readRun()
        assertEquals(ScanPhase.Running, saved.phase)
        assertEquals(MediaType.Video, saved.byType[0].type)
        assertEquals(2, saved.byType[0].scanned)
        assertEquals(MediaType.Image, saved.byType[1].type)
        assertEquals(1, saved.byType[1].scanned)

        store.saveRun(
            ScanRun(
                phase = ScanPhase.Completed,
                byType = listOf(TypeProgress(MediaType.Video, scanned = 10, total = 10)),
                mediaStoreVersion = "v1",
                completedVolumeGeneration = 4,
                detail = null,
            ),
        )
        val completed = store.readRun()
        assertEquals(ScanPhase.Completed, completed.phase)
        assertEquals("v1", completed.mediaStoreVersion)
        assertEquals(4L, completed.completedVolumeGeneration)
        assertEquals(listOf(MediaType.Video), completed.byType.map { row -> row.type })
        assertEquals(10, completed.byType.single().scanned)
    }

    @Test
    fun progressFollowsTheRunAndIgnoresItemAndCheckpointWrites() = runBlocking {
        val phases = CopyOnWriteArrayList<ScanPhase>()
        val job = launch {
            store.observeProgress().collect { progress -> phases += progress.phase }
        }
        awaitUntil { phases.isNotEmpty() }
        assertEquals(listOf(ScanPhase.Idle), phases.toList())

        store.upsertBatch(listOf(item(MediaType.Image, 1)))
        store.saveCheckpoint(checkpoint(MediaType.Image, id = 1, scanned = 1, after = null))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 1)))
        delay(250)
        assertEquals(listOf(ScanPhase.Idle), phases.toList())

        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = listOf(TypeProgress(MediaType.Image, scanned = 1, total = 4)),
                mediaStoreVersion = null,
                completedVolumeGeneration = null,
                detail = "reading photos",
            ),
        )
        awaitUntil { phases.lastOrNull() == ScanPhase.Running }
        val latest = store.observeProgress().first()
        assertEquals(ScanPhase.Running, latest.phase)
        assertEquals(1, latest.scanned)
        assertEquals(4, latest.total)
        assertEquals("reading photos", latest.detail)
        assertEquals(listOf(ScanPhase.Idle, ScanPhase.Running), phases.toList())

        store.saveRun(store.readRun())
        delay(250)
        assertEquals(listOf(ScanPhase.Idle, ScanPhase.Running), phases.toList())
        job.cancel()
    }

    @Test
    fun reviewRowsAndScanRowsDoNotShareATable() = runBlocking {
        val review = sample(id = 4, type = MediaType.Image)
        store.upsert(listOf(review))
        store.markKept(listOf(review.key))
        store.upsertBatch(listOf(item(MediaType.Image, 4, size = 20)))

        assertEquals(setOf(review.key), store.keptKeys())
        assertEquals(listOf(review.key), store.presentKeys(MediaType.Image))

        store.markRemoved(listOf(review.key))
        assertEquals(ReviewStatus.Kept, database.scanResultDao().find(4, MediaType.Image)!!.reviewStatus)
        assertTrue(store.presentKeys(MediaType.Image).isEmpty())

        store.remove(listOf(review.key))
        assertNull(database.scanResultDao().find(4, MediaType.Image))
        assertTrue(database.scanStoreDao().findMedia(MediaType.Image, 4)!!.removed)
    }

    @Test
    fun versionOneReviewRowsSurviveTheScanTableMigration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("scan-migration.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_results` (`media_store_id` INTEGER NOT NULL, " +
                                "`media_type` TEXT NOT NULL, `content_uri` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                                "`confidence` REAL NOT NULL, `model_version` TEXT NOT NULL, " +
                                "`scanned_at_epoch_millis` INTEGER NOT NULL, `review_status` TEXT NOT NULL, " +
                                "PRIMARY KEY(`media_store_id`, `media_type`))",
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_results_content_uri` " +
                                "ON `scan_results` (`content_uri`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_scan_results_category_review_status` " +
                                "ON `scan_results` (`category`, `review_status`)",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_scan_results_review_status` " +
                                "ON `scan_results` (`review_status`)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                                "VALUES(42, 'afc0d901110265ceaf4329a37186c472')",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO scan_results (media_store_id, media_type, content_uri, category, confidence, " +
                    "model_version, scanned_at_epoch_millis, review_status) VALUES " +
                    "(4, 'Image', 'content://media/external/images/media/4', 'Screenshot', 0.25, " +
                    "'screenshot-rules-1', 1000, 'Kept')",
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            dbFile.absolutePath,
        ).addMigrations(MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val migratedStore = RoomScanResultStore(migrated)
            assertEquals(setOf(MediaKey(MediaType.Image, 4)), migratedStore.keptKeys())
            val row = migrated.scanResultDao().find(4, MediaType.Image)
            assertEquals(ReviewStatus.Kept, row!!.reviewStatus)
            assertEquals(0.25f, row.confidence, 0.0001f)
            assertEquals(ScanRun.IDLE, migratedStore.readRun())
            assertNull(migratedStore.readCheckpoint())
            migratedStore.upsertBatch(listOf(item(MediaType.Image, 9, size = 2)))
            assertEquals(listOf(MediaKey(MediaType.Image, 9)), migratedStore.presentKeys(MediaType.Image))
            val latest = migratedStore.observeProgress().first()
            assertEquals(ScanProgress.from(ScanRun.IDLE), latest)
        } finally {
            migrated.close()
            dbFile.delete()
        }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate()) {
            if (System.nanoTime() > deadline) error("timed out waiting for scan progress")
            delay(20)
        }
    }

    private suspend fun stored(type: MediaType, id: Long): MediaItemEntity {
        return database.scanStoreDao().findMedia(type, id) ?: error("missing $type $id")
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

    private fun checkpoint(type: MediaType, id: Long, scanned: Int, after: Long?) = ScanCheckpoint(
        type = type,
        dateModified = 1_700_000_100,
        id = id,
        scanned = scanned,
        generationModifiedAfter = after,
        generationModifiedAtMost = 4,
        mediaStoreVersion = "v1",
    )
}
