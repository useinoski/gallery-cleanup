package com.useinoski.gallerycleanup.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The schema JSON is produced by Room's KSP processor because
 * [GalleryDatabase] sets `exportSchema = true` and the module points Room at
 * `schemas/`. Future migration tests open these files.
 */
class ScanSchemaExportTest {
    @Test
    fun versionOneSchemaIsCheckedIn() {
        val schemas = File("schemas").walkTopDown()
            .filter { file -> file.isFile && file.name == "1.json" }
            .toList()
        assertTrue(
            "Room schema export is missing under core/data/schemas. " +
                "exportSchema is on, and the room plugin writes it at compile time.",
            schemas.isNotEmpty(),
        )
        val columns = listOf(
            "media_store_id",
            "media_type",
            "content_uri",
            "category",
            "confidence",
            "model_version",
            "scanned_at_epoch_millis",
            "review_status",
        )
        schemas.forEach { file ->
            val text = file.readText()
            assertTrue(text.contains("\"tableName\": \"scan_results\""))
            columns.forEach { column ->
                assertTrue(
                    "$column missing from ${file.path}",
                    text.contains("\"columnName\": \"$column\""),
                )
            }
            assertFalse(
                "scan results must not store a binary copy of the media",
                text.contains("\"affinity\": \"BLOB\""),
            )
        }
    }

    @Test
    fun versionTwoAddsTheScanStoreTablesWithoutABlob() {
        val schema = File("schemas").walkTopDown()
            .filter { file -> file.isFile && file.name == "2.json" }
            .singleOrNull()
            ?: error("Room schema export for version 2 is missing under core/data/schemas.")
        val text = schema.readText()
        listOf(
            "scan_results",
            "media_item",
            "scan_checkpoint",
            "scan_run",
            "scan_type_progress",
        ).forEach { table ->
            assertTrue(
                "$table missing from ${schema.path}",
                text.contains("\"tableName\": \"$table\""),
            )
        }
        assertTrue(text.contains("\"columnName\": \"removed\""))
        assertTrue(text.contains("\"columnName\": \"position\""))
        assertFalse(
            "the database must not store a binary copy of the media",
            text.contains("\"affinity\": \"BLOB\""),
        )
    }

    @Test
    fun versionThreeAddsAnalysisOutcomeWithoutABlob() {
        val schema = File("schemas").walkTopDown()
            .filter { file -> file.isFile && file.name == "3.json" }
            .singleOrNull()
            ?: error("Room schema export for version 3 is missing under core/data/schemas.")
        val text = schema.readText()
        assertTrue(text.contains("\"version\": 3"))
        assertTrue(text.contains("\"columnName\": \"analysis_outcome\""))
        assertTrue(text.contains("\"columnName\": \"failure_count\""))
        assertTrue(text.contains("\"defaultValue\": \"'Scored'\""))
        assertTrue(text.contains("\"defaultValue\": \"0\""))
        assertTrue(
            text.contains("\"name\": \"index_scan_results_analysis_outcome_model_version\""),
        )
        assertFalse(
            "the database must not store a binary copy of the media",
            text.contains("\"affinity\": \"BLOB\""),
        )
    }
}
