package com.useinoski.gallerycleanup.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.useinoski.gallerycleanup.model.MediaCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens a version 3 database from the exported schema, runs [MIGRATION_3_4],
 * and checks the result against `4.json`.
 *
 * [MediaCategory.Experimental.experimental] is true. PR #13's description
 * table said that flag was no. The merged enum is what this backfill stores:
 * the id `experimental`, and not a separate flag column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3To4Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryDatabase::class.java,
    )

    @Test
    fun migrate3To4BackfillsCategoriesAndKeepsReviewState() {
        assertEquals(ScanCategory.entries.map { it.name }.toSet(), ROWS.map { it.scanCategory }.toSet())
        assertTrue(MediaCategory.Experimental.experimental)
        assertTrue(MediaCategory.Sensitive.sensitive)
        assertNull(MediaCategory.fromId("Experimental"))
        assertNull(MediaCategory.fromId("Sensitive"))

        helper.createDatabase(DB_NAME, 3).use { db ->
            ROWS.forEach { row -> db.insertReviewRow(row) }
            db.execSQL(
                "INSERT INTO scan_run (singleton, phase, media_store_version, " +
                    "completed_volume_generation, detail) VALUES " +
                    "(1, 'AccessRevoked', 'v3', 9, 'media access was removed')",
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)
        migrated.use { db ->
            assertEquals(4, db.version)
            assertEquals(ROWS.size, db.intQuery("SELECT COUNT(*) FROM scan_results"))
            ROWS.forEach { row ->
                val stored = db.reviewRow(row.id, row.type)
                assertEquals(row.scanCategory, stored.category)
                assertEquals(row.reviewStatus, stored.reviewStatus)
                assertEquals(row.outcome, stored.outcome)
                assertEquals(row.failureCount, stored.failureCount)
                assertEquals(row.confidence, stored.confidence, 0.0001)
                assertEquals(row.modelVersion, stored.modelVersion)
                assertEquals(row.scannedAt, stored.scannedAt)
                val expectedIds = if (row.categoryId == null) emptySet() else setOf(row.categoryId)
                assertEquals(expectedIds, db.categoryIds(row.id, row.type))
            }

            val writtenIds = db.stringSet("SELECT DISTINCT category_id FROM item_category")
            assertEquals(
                setOf("sensitive", "screenshots", "people", "videos", "experimental"),
                writtenIds,
            )
            assertEquals(MediaCategory.Experimental, MediaCategory.fromId("experimental"))
            assertTrue(checkNotNull(MediaCategory.fromId("experimental")).experimental)
            listOf("music", "other-audio", "chat-images", "old-items").forEach { id ->
                assertTrue("$id must not be invented by the migration", id !in writtenIds)
            }

            assertEquals("AccessRevoked", db.stringQuery("SELECT phase FROM scan_run"))
            assertEquals(
                "media access was removed",
                db.stringQuery("SELECT detail FROM scan_run"),
            )
            assertEquals(0, db.intQuery("SELECT COUNT(*) FROM item_category WHERE media_type = 'Audio'"))
        }
    }

    private fun SupportSQLiteDatabase.insertReviewRow(row: V3Row) {
        execSQL(
            "INSERT INTO scan_results (media_store_id, media_type, content_uri, category, confidence, " +
                "model_version, scanned_at_epoch_millis, review_status, analysis_outcome, failure_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                row.id,
                row.type,
                "content://media/${row.type}/${row.id}/${row.scanCategory}",
                row.scanCategory,
                row.confidence,
                row.modelVersion,
                row.scannedAt,
                row.reviewStatus,
                row.outcome,
                row.failureCount.toLong(),
            ),
        )
    }

    private fun SupportSQLiteDatabase.reviewRow(id: Long, type: String): StoredReview {
        return query(
            "SELECT category, review_status, analysis_outcome, failure_count, confidence, " +
                "model_version, scanned_at_epoch_millis FROM scan_results " +
                "WHERE media_store_id = ? AND media_type = ?",
            arrayOf<Any>(id, type),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "missing $type $id" }
            StoredReview(
                category = cursor.getString(0),
                reviewStatus = cursor.getString(1),
                outcome = cursor.getString(2),
                failureCount = cursor.getInt(3),
                confidence = cursor.getDouble(4),
                modelVersion = cursor.getString(5),
                scannedAt = cursor.getLong(6),
            )
        }
    }

    private fun SupportSQLiteDatabase.categoryIds(id: Long, type: String): Set<String> {
        return query(
            "SELECT category_id FROM item_category WHERE media_store_id = ? AND media_type = ?",
            arrayOf<Any>(id, type),
        ).use { cursor ->
            val ids = mutableSetOf<String>()
            while (cursor.moveToNext()) ids += cursor.getString(0)
            ids
        }
    }

    private fun SupportSQLiteDatabase.intQuery(sql: String): Int {
        return query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String {
        return query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }

    private fun SupportSQLiteDatabase.stringSet(sql: String): Set<String> {
        return query(sql).use { cursor ->
            val values = mutableSetOf<String>()
            while (cursor.moveToNext()) values += cursor.getString(0)
            values
        }
    }

    private data class V3Row(
        val id: Long,
        val type: String,
        val scanCategory: String,
        val reviewStatus: String,
        val outcome: String,
        val failureCount: Int,
        val categoryId: String?,
        val confidence: Double = 0.5,
        val modelVersion: String = "model-1",
        val scannedAt: Long = 1_000L + id,
    )

    private data class StoredReview(
        val category: String,
        val reviewStatus: String,
        val outcome: String,
        val failureCount: Int,
        val confidence: Double,
        val modelVersion: String,
        val scannedAt: Long,
    )

    private companion object {
        const val DB_NAME = "migration-3-4"

        val ROWS = listOf(
            V3Row(1, "Image", "Sensitive", "Pending", "Scored", 0, "sensitive", confidence = 0.91, modelVersion = "nsfw-1"),
            V3Row(2, "Image", "Immodest", "Kept", "Scored", 0, "sensitive", confidence = 0.4, modelVersion = "nsfw-1"),
            V3Row(3, "Image", "Screenshot", "Pending", "Scored", 0, "screenshots", modelVersion = "screenshot-rules-1"),
            V3Row(4, "Image", "People", "Trashed", "Scored", 7, "people", confidence = 2.0, modelVersion = "mlkit-face-count|face-v1"),
            V3Row(4, "Video", "Video", "Pending", "Scored", 0, "videos", modelVersion = "video-manual-review|v1"),
            V3Row(5, "Image", "AlcoholGambling", "Pending", "Scored", 0, "experimental", confidence = 0.2, modelVersion = "labels-1"),
            V3Row(6, "Audio", "Audio", "Pending", "Scored", 0, null, modelVersion = "audio-1"),
            V3Row(7, "Image", "Unflagged", "Pending", "Scored", 0, null, confidence = 0.0, modelVersion = "old-rule"),
            V3Row(8, "Image", "Unflagged", "Pending", "Failed", 3, null, confidence = 0.0, modelVersion = "none"),
            V3Row(9, "Image", "Sensitive", "Pending", "Failed", 5, "sensitive", confidence = 0.0, modelVersion = "none"),
            V3Row(10, "Image", "Screenshot", "Kept", "Scored", 2, "screenshots", modelVersion = "screenshot-rules-1"),
            V3Row(11, "Audio", "Audio", "Trashed", "Failed", 6, null, confidence = 0.0, modelVersion = "none"),
            V3Row(12, "Video", "People", "Pending", "Scored", 1, "people", modelVersion = "mlkit-face-count|face-v1"),
        )
    }
}
