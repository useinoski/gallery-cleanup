package com.useinoski.gallerycleanup.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 is the review table only. Version 2 adds the [ScanStore] tables
 * and does not rewrite `scan_results`. Kept rows stay where they are.
 *
 * The `CREATE` statements are the ones Room exports in `2.json`. A migration
 * that drifts from that file fails to open, because Room checks the resulting
 * schema against the entity definition.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_item` (
                `type` TEXT NOT NULL,
                `media_id` INTEGER NOT NULL,
                `uri` TEXT NOT NULL,
                `date_added` INTEGER NOT NULL,
                `size` INTEGER NOT NULL,
                `path` TEXT,
                `mime_type` TEXT,
                `removed` INTEGER NOT NULL,
                PRIMARY KEY(`type`, `media_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_checkpoint` (
                `singleton` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `date_modified` INTEGER NOT NULL,
                `media_id` INTEGER NOT NULL,
                `scanned` INTEGER NOT NULL,
                `generation_modified_after` INTEGER,
                `generation_modified_at_most` INTEGER NOT NULL,
                `media_store_version` TEXT NOT NULL,
                PRIMARY KEY(`singleton`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_run` (
                `singleton` INTEGER NOT NULL,
                `phase` TEXT NOT NULL,
                `media_store_version` TEXT,
                `completed_volume_generation` INTEGER,
                `detail` TEXT,
                PRIMARY KEY(`singleton`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scan_type_progress` (
                `type` TEXT NOT NULL,
                `scanned` INTEGER NOT NULL,
                `total` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                PRIMARY KEY(`type`)
            )
            """.trimIndent(),
        )
    }
}
