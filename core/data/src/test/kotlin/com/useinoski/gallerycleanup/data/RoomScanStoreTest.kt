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
    fun presentItemsSkipsMissingAndRemovedRowsAndKeepsRequestOrder() = runBlocking {
        val image = item(MediaType.Image, 4, size = 8)
        val video = item(MediaType.Video, 4, size = 2)
        val gone = item(MediaType.Image, 5)
        store.upsertBatch(listOf(image, video, gone))
        store.markRemoved(listOf(MediaKey(MediaType.Image, 5)))

        val found = store.presentItems(
            listOf(
                MediaKey(MediaType.Video, 4),
                MediaKey(MediaType.Image, 5),
                MediaKey(MediaType.Image, 9),
                MediaKey(MediaType.Image, 4),
                MediaKey(MediaType.Video, 4),
            ),
        )

        assertEquals(listOf(video, image), found)
        assertEquals(emptyList<MediaItem>(), store.presentItems(emptyList()))
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
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, migrated.openHelper.readableDatabase.version)
            val migratedStore = RoomScanResultStore(migrated)
            assertEquals(setOf(MediaKey(MediaType.Image, 4)), migratedStore.keptKeys())
            val row = migrated.scanResultDao().find(4, MediaType.Image)
            assertEquals(ReviewStatus.Kept, row!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, row.analysisOutcome)
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

    @Test
    fun versionTwoRowsSurviveAndCleanPhotosStayScored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("scan-migration-v2.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
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
                            "CREATE TABLE IF NOT EXISTS `media_item` (`type` TEXT NOT NULL, " +
                                "`media_id` INTEGER NOT NULL, `uri` TEXT NOT NULL, `date_added` INTEGER NOT NULL, " +
                                "`size` INTEGER NOT NULL, `path` TEXT, `mime_type` TEXT, `removed` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`type`, `media_id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_checkpoint` (`singleton` INTEGER NOT NULL, " +
                                "`type` TEXT NOT NULL, `date_modified` INTEGER NOT NULL, `media_id` INTEGER NOT NULL, " +
                                "`scanned` INTEGER NOT NULL, `generation_modified_after` INTEGER, " +
                                "`generation_modified_at_most` INTEGER NOT NULL, `media_store_version` TEXT NOT NULL, " +
                                "PRIMARY KEY(`singleton`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_run` (`singleton` INTEGER NOT NULL, " +
                                "`phase` TEXT NOT NULL, `media_store_version` TEXT, " +
                                "`completed_volume_generation` INTEGER, `detail` TEXT, PRIMARY KEY(`singleton`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_type_progress` (`type` TEXT NOT NULL, " +
                                "`scanned` INTEGER NOT NULL, `total` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`type`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                                "VALUES(42, 'c9ba4207ab4c6e25b59590db0676e78d')",
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
                    "(1, 'Image', 'content://media/external/images/media/1', 'Screenshot', 0.25, " +
                    "'screenshot-rules-1', 1000, 'Kept'), " +
                    "(2, 'Image', 'content://media/external/images/media/2', 'Sensitive', 0.91, " +
                    "'nsfw-1', 2000, 'Pending'), " +
                    "(3, 'Image', 'content://media/external/images/media/3', 'Unflagged', 0.0, " +
                    "'none', 3000, 'Pending'), " +
                    "(4, 'Image', 'content://media/external/images/media/4', 'Unflagged', 0.0, " +
                    "'none', 4000, 'Kept'), " +
                    "(5, 'Image', 'content://media/external/images/media/5', 'People', 2.0, " +
                    "'mlkit-face-count|face-v1', 5000, 'Trashed'), " +
                    "(6, 'Image', 'content://media/external/images/media/6', 'Unflagged', 0.0, " +
                    "'old-rule', 6000, 'Pending'), " +
                    "(7, 'Image', 'content://media/external/images/media/7', 'Unflagged', 0.0, " +
                    "'none', 7000, 'Trashed')",
            )
            db.execSQL(
                "INSERT INTO media_item (type, media_id, uri, date_added, size, path, mime_type, removed) " +
                    "VALUES ('Image', 1, 'content://media/external/images/media/1', 10, 20, 'DCIM/1', NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO scan_run (singleton, phase, media_store_version, completed_volume_generation, detail) " +
                    "VALUES (1, 'Completed', 'v1', 4, NULL)",
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            dbFile.absolutePath,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, migrated.openHelper.readableDatabase.version)
            val migratedStore = RoomScanResultStore(migrated)
            val keptShot = migrated.scanResultDao().find(1, MediaType.Image)
            assertEquals(ReviewStatus.Kept, keptShot!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, keptShot.analysisOutcome)
            assertEquals(ScanCategory.Screenshot, keptShot.category)
            assertEquals(0.25f, keptShot.confidence, 0.0001f)
            assertEquals("screenshot-rules-1", keptShot.modelVersion)
            assertEquals(1000L, keptShot.scannedAtEpochMillis)
            assertEquals("content://media/external/images/media/1", keptShot.contentUri)

            val scored = migrated.scanResultDao().find(2, MediaType.Image)
            assertEquals(ReviewStatus.Pending, scored!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, scored.analysisOutcome)
            assertEquals(ScanCategory.Sensitive, scored.category)
            assertEquals(0.91f, scored.confidence, 0.0001f)

            // A pending Unflagged/none row is a clean photo, or a version-2
            // failure that was stored the same way. Both stay Scored. Kept
            // and trashed copies of that pair stay Scored too.
            val ambiguous = migrated.scanResultDao().find(3, MediaType.Image)
            assertEquals(ReviewStatus.Pending, ambiguous!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, ambiguous.analysisOutcome)
            assertEquals(0, ambiguous.failureCount)
            assertEquals(ScanCategory.Unflagged, ambiguous.category)
            assertEquals(MODEL_VERSION_NONE, ambiguous.modelVersion)
            assertEquals(3000L, ambiguous.scannedAtEpochMillis)

            val keptClean = migrated.scanResultDao().find(4, MediaType.Image)
            assertEquals(ReviewStatus.Kept, keptClean!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, keptClean.analysisOutcome)
            assertEquals(0, keptClean.failureCount)
            assertEquals(ScanCategory.Unflagged, keptClean.category)
            assertEquals(0f, keptClean.confidence, 0.0001f)
            assertEquals(MODEL_VERSION_NONE, keptClean.modelVersion)
            assertEquals(4000L, keptClean.scannedAtEpochMillis)
            assertEquals("content://media/external/images/media/4", keptClean.contentUri)

            val trashed = migrated.scanResultDao().find(5, MediaType.Image)
            assertEquals(ReviewStatus.Trashed, trashed!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, trashed.analysisOutcome)
            assertEquals(0, trashed.failureCount)
            assertEquals("mlkit-face-count|face-v1", trashed.modelVersion)
            assertEquals(2f, trashed.confidence, 0.0001f)
            assertEquals(5000L, trashed.scannedAtEpochMillis)

            val trashedClean = migrated.scanResultDao().find(7, MediaType.Image)
            assertEquals(ReviewStatus.Trashed, trashedClean!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, trashedClean.analysisOutcome)
            assertEquals(0, trashedClean.failureCount)
            assertEquals(ScanCategory.Unflagged, trashedClean.category)
            assertEquals(MODEL_VERSION_NONE, trashedClean.modelVersion)
            assertEquals(7000L, trashedClean.scannedAtEpochMillis)
            assertEquals("content://media/external/images/media/7", trashedClean.contentUri)

            val namedUnflagged = migrated.scanResultDao().find(6, MediaType.Image)
            assertEquals(AnalysisOutcome.Scored, namedUnflagged!!.analysisOutcome)
            assertEquals("old-rule", namedUnflagged.modelVersion)

            assertEquals(setOf(MediaKey(MediaType.Image, 1), MediaKey(MediaType.Image, 4)), migratedStore.keptKeys())
            val counts = migratedStore.observePendingCounts().first()
            assertEquals(1, counts[ScanCategory.Sensitive])
            assertEquals(2, counts[ScanCategory.Unflagged])
            assertEquals(0, counts[ScanCategory.People])
            assertEquals(0, counts[ScanCategory.Screenshot])

            val stillCurrent = setOf(
                "screenshot-rules-1",
                "nsfw-1",
                "mlkit-face-count|face-v1",
                MODEL_VERSION_NONE,
                "old-rule",
            )
            assertTrue(migratedStore.keysNeedingRescan(stillCurrent).isEmpty())

            migratedStore.upsert(
                listOf(
                    sample(
                        id = 1,
                        category = ScanCategory.Sensitive,
                        confidence = 0.99f,
                        modelVersion = "nsfw-9",
                        scannedAtEpochMillis = 9_000,
                    ),
                ),
            )
            val keptAfterUpsert = migrated.scanResultDao().find(1, MediaType.Image)
            assertEquals(ReviewStatus.Kept, keptAfterUpsert!!.reviewStatus)
            assertEquals(ScanCategory.Sensitive, keptAfterUpsert.category)
            assertEquals(0.99f, keptAfterUpsert.confidence, 0.0001f)
            assertEquals("nsfw-9", keptAfterUpsert.modelVersion)
            assertEquals(AnalysisOutcome.Scored, keptAfterUpsert.analysisOutcome)
            assertEquals(0, keptAfterUpsert.failureCount)

            assertEquals(ScanPhase.Completed, migratedStore.readRun().phase)
            assertEquals("v1", migratedStore.readRun().mediaStoreVersion)
            assertEquals(listOf(MediaKey(MediaType.Image, 1)), migratedStore.presentKeys(MediaType.Image))
        } finally {
            migrated.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        }
    }

    @Test
    fun versionThreeFalseFailuresWithNoCountReturnToScored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = context.getDatabasePath("scan-migration-v3.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
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
                            "ALTER TABLE `scan_results` ADD COLUMN `analysis_outcome` TEXT NOT NULL DEFAULT 'Scored'",
                        )
                        db.execSQL(
                            "ALTER TABLE `scan_results` ADD COLUMN `failure_count` INTEGER NOT NULL DEFAULT 0",
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_scan_results_analysis_outcome_model_version` " +
                                "ON `scan_results` (`analysis_outcome`, `model_version`)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `media_item` (`type` TEXT NOT NULL, " +
                                "`media_id` INTEGER NOT NULL, `uri` TEXT NOT NULL, `date_added` INTEGER NOT NULL, " +
                                "`size` INTEGER NOT NULL, `path` TEXT, `mime_type` TEXT, `removed` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`type`, `media_id`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_checkpoint` (`singleton` INTEGER NOT NULL, " +
                                "`type` TEXT NOT NULL, `date_modified` INTEGER NOT NULL, `media_id` INTEGER NOT NULL, " +
                                "`scanned` INTEGER NOT NULL, `generation_modified_after` INTEGER, " +
                                "`generation_modified_at_most` INTEGER NOT NULL, `media_store_version` TEXT NOT NULL, " +
                                "PRIMARY KEY(`singleton`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_run` (`singleton` INTEGER NOT NULL, " +
                                "`phase` TEXT NOT NULL, `media_store_version` TEXT, " +
                                "`completed_volume_generation` INTEGER, `detail` TEXT, PRIMARY KEY(`singleton`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `scan_type_progress` (`type` TEXT NOT NULL, " +
                                "`scanned` INTEGER NOT NULL, `total` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`type`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY,identity_hash TEXT)",
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                                "VALUES(42, '867f7bffe1bfa763041cfe65cdd588e2')",
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
                    "model_version, scanned_at_epoch_millis, review_status, analysis_outcome, failure_count) VALUES " +
                    "(1, 'Image', 'content://media/external/images/media/1', 'Unflagged', 0.0, " +
                    "'none', 3000, 'Pending', 'Failed', 0), " +
                    "(2, 'Image', 'content://media/external/images/media/2', 'Unflagged', 0.0, " +
                    "'none', 4000, 'Pending', 'Failed', 1), " +
                    "(3, 'Image', 'content://media/external/images/media/3', 'Unflagged', 0.0, " +
                    "'none', 5000, 'Pending', 'Failed', 4), " +
                    "(4, 'Image', 'content://media/external/images/media/4', 'Unflagged', 0.0, " +
                    "'none', 6000, 'Pending', 'Scored', 0), " +
                    "(5, 'Image', 'content://media/external/images/media/5', 'Sensitive', 0.91, " +
                    "'nsfw-1', 7000, 'Pending', 'Scored', 0), " +
                    "(6, 'Image', 'content://media/external/images/media/6', 'People', 2.0, " +
                    "'mlkit-face-count|face-v1', 8000, 'Pending', 'Failed', 0), " +
                    "(7, 'Image', 'content://media/external/images/media/7', 'Unflagged', 0.0, " +
                    "'none', 9000, 'Kept', 'Failed', 0), " +
                    "(8, 'Image', 'content://media/external/images/media/8', 'Unflagged', 0.0, " +
                    "'none', 10000, 'Trashed', 'Failed', 2)",
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            dbFile.absolutePath,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, migrated.openHelper.readableDatabase.version)
            val dao = migrated.scanResultDao()
            val store = RoomScanResultStore(migrated)

            val clean = dao.find(1, MediaType.Image)
            assertEquals(AnalysisOutcome.Scored, clean!!.analysisOutcome)
            assertEquals(0, clean.failureCount)
            assertEquals(ReviewStatus.Pending, clean.reviewStatus)
            assertEquals(ScanCategory.Unflagged, clean.category)
            assertEquals(MODEL_VERSION_NONE, clean.modelVersion)
            assertEquals(0f, clean.confidence, 0.0001f)
            assertEquals(3000L, clean.scannedAtEpochMillis)
            assertEquals("content://media/external/images/media/1", clean.contentUri)

            val failedOnce = dao.find(2, MediaType.Image)
            assertEquals(AnalysisOutcome.Failed, failedOnce!!.analysisOutcome)
            assertEquals(1, failedOnce.failureCount)
            assertEquals(ReviewStatus.Pending, failedOnce.reviewStatus)
            assertEquals(MODEL_VERSION_NONE, failedOnce.modelVersion)

            val failedAgain = dao.find(3, MediaType.Image)
            assertEquals(AnalysisOutcome.Failed, failedAgain!!.analysisOutcome)
            assertEquals(4, failedAgain.failureCount)

            val alreadyScored = dao.find(4, MediaType.Image)
            assertEquals(AnalysisOutcome.Scored, alreadyScored!!.analysisOutcome)
            assertEquals(0, alreadyScored.failureCount)
            assertEquals(6000L, alreadyScored.scannedAtEpochMillis)

            val flagged = dao.find(5, MediaType.Image)
            assertEquals(AnalysisOutcome.Scored, flagged!!.analysisOutcome)
            assertEquals(ScanCategory.Sensitive, flagged.category)
            assertEquals("nsfw-1", flagged.modelVersion)
            assertEquals(0.91f, flagged.confidence, 0.0001f)

            // Count 0 is not enough on its own. recordFailure never writes a
            // category other than Unflagged, so this row is not the migration bug.
            val unexplained = dao.find(6, MediaType.Image)
            assertEquals(AnalysisOutcome.Failed, unexplained!!.analysisOutcome)
            assertEquals(0, unexplained.failureCount)
            assertEquals(ScanCategory.People, unexplained.category)
            assertEquals("mlkit-face-count|face-v1", unexplained.modelVersion)
            assertEquals(ReviewStatus.Pending, unexplained.reviewStatus)

            val kept = dao.find(7, MediaType.Image)
            assertEquals(ReviewStatus.Kept, kept!!.reviewStatus)
            assertEquals(AnalysisOutcome.Scored, kept.analysisOutcome)
            assertEquals(0, kept.failureCount)
            assertEquals(ScanCategory.Unflagged, kept.category)
            assertEquals(MODEL_VERSION_NONE, kept.modelVersion)
            assertEquals(9000L, kept.scannedAtEpochMillis)

            val trashedFailure = dao.find(8, MediaType.Image)
            assertEquals(ReviewStatus.Trashed, trashedFailure!!.reviewStatus)
            assertEquals(AnalysisOutcome.Failed, trashedFailure.analysisOutcome)
            assertEquals(2, trashedFailure.failureCount)

            val counts = store.observePendingCounts().first()
            assertEquals(2, counts[ScanCategory.Unflagged])
            assertEquals(1, counts[ScanCategory.Sensitive])
            assertEquals(0, counts[ScanCategory.People])

            assertEquals(
                setOf(
                    MediaKey(MediaType.Image, 2),
                    MediaKey(MediaType.Image, 3),
                    MediaKey(MediaType.Image, 6),
                ),
                store.keysNeedingRescan(
                    setOf(MODEL_VERSION_NONE, "nsfw-1", "mlkit-face-count|face-v1"),
                ).toSet(),
            )
        } finally {
            migrated.close()
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
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
