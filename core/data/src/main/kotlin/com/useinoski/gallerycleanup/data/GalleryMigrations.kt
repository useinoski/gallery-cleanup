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
 * delete rows. Version 4 is [MIGRATION_3_4]. It does not change the schema.
 * It restores clean rows that an earlier copy of version 3 marked failed.
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
 * Every existing row stays [AnalysisOutcome.Scored], with `failure_count` 0.
 * The column defaults and the new index are the ones Room exports in `3.json`.
 *
 * Version 2 wrote a clean photo and a failed decode as the same row:
 * [ReviewStatus.Pending], [ScanCategory.Unflagged], confidence 0, and model
 * version [MODEL_VERSION_NONE]. No column tells those rows apart, so this
 * migration does not mark them [AnalysisOutcome.Failed]. That used to queue
 * every clean photo for [RoomScanResultStore.keysNeedingRescan]. A real
 * version-2 failure stays `Scored` with model version `none`. The rescan
 * query returns it once `none` is no longer a version the pipeline writes.
 *
 * Category, score, URI, model version, time, and review status are not
 * changed. Devices that already ran the old statement are repaired by
 * [MIGRATION_3_4].
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
            CREATE INDEX IF NOT EXISTS `index_scan_results_analysis_outcome_model_version`
            ON `scan_results` (`analysis_outcome`, `model_version`)
            """.trimIndent(),
        )
    }
}

/**
 * Version 4 does not change tables, columns, or indexes. `4.json` is the
 * version 3 schema with the version number bumped.
 *
 * The first release of [MIGRATION_2_3] set `analysis_outcome` to `Failed` on
 * every pending [ScanCategory.Unflagged] row whose model version was
 * [MODEL_VERSION_NONE], and it left `failure_count` at 0. Those rows are
 * clean photos and failed decodes mixed together. Devices that already
 * applied that statement are on version 3, so this migration puts the clean
 * shape back to [AnalysisOutcome.Scored].
 *
 * The rewrite is limited to rows [RoomScanResultStore.recordFailure] cannot
 * have written. That method is the only writer of `Failed`, and it stores a
 * `failure_count` of at least 1. A scored [RoomScanResultStore.upsert] sets
 * the count back to 0 and stores `Scored`, so a clean rescan is not updated
 * again. A rescan that failed stays `Failed` because its count is at least 1.
 * A `Failed` row with count 0 and any other category or model version is
 * left alone: version 2 and [RoomScanResultStore.recordFailure] never wrote
 * that shape.
 *
 * Review status, category, score, URI, model version, time, and
 * `failure_count` are not changed.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE `scan_results`
            SET `analysis_outcome` = 'Scored'
            WHERE `analysis_outcome` = 'Failed'
                AND `failure_count` = 0
                AND `category` = 'Unflagged'
                AND `model_version` = 'none'
            """.trimIndent(),
        )
    }
}
