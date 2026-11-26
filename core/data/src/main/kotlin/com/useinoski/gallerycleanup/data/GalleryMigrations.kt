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
 *
 * Version 3 is [MIGRATION_2_3]. It adds `analysis_outcome` and does not
 * delete rows.
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

/**
 * Version 3 adds `scan_results.analysis_outcome`, `failure_count`, and an
 * index for the rescan query. Other tables are not rewritten.
 *
 * Every existing row starts as `Scored`, with `failure_count` 0. Version 2
 * could not tell a failed decode from a clean photo: both were category
 * `Unflagged` and model version `none` ([MODEL_VERSION_NONE]). Only rows
 * still [ReviewStatus.Pending] are set to `Failed`, so they leave the clean
 * count and [RoomScanResultStore.keysNeedingRescan] returns them. Kept and
 * trashed rows are not rewritten: a decision the user already made stays
 * `Scored` and is not queued for another pass.
 *
 * Category, score, URI, model version, time, and review status are not
 * changed. The `ADD COLUMN` defaults and the new index are the ones Room
 * exports in `3.json`.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `scan_results` ADD COLUMN `analysis_outcome` TEXT NOT NULL DEFAULT 'Scored'",
        )
        db.execSQL(
            "ALTER TABLE `scan_results` ADD COLUMN `failure_count` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            UPDATE `scan_results`
            SET `analysis_outcome` = 'Failed'
            WHERE `category` = 'Unflagged'
                AND `model_version` = 'none'
                AND `review_status` = 'Pending'
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_scan_results_analysis_outcome_model_version`
            ON `scan_results` (`analysis_outcome`, `model_version`)
            """.trimIndent(),
        )
    }
}
