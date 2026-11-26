package com.useinoski.gallerycleanup.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.useinoski.gallerycleanup.model.MediaCategory

/**
 * Version 1 is the review table only. Version 2 adds the [ScanStore] tables
 * and does not rewrite `scan_results`. Kept rows stay where they are.
 *
 * The `CREATE` statements are the ones Room exports in `2.json`. A migration
 * that drifts from that file fails to open, because Room checks the resulting
 * schema against the entity definition.
 *
 * Version 3 is [MIGRATION_2_3]. It adds `analysis_outcome` and does not
 * delete rows. Version 4 is [MIGRATION_3_4]. It adds `item_category` and
 * does not rewrite `scan_results`.
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

/**
 * Version 4 adds `item_category`: one row per media item per [MediaCategory].
 * `scan_results` is not rewritten. Kept and trashed decisions,
 * [AnalysisOutcome], and `failure_count` stay on that table. Access revoked
 * stays on the scan run. None of those facts are copied into `item_category`.
 *
 * `category_id` stores [MediaCategory.id] (`sensitive`), not the Kotlin name
 * (`Sensitive`) and not the ordinal. The `experimental` flag is not a column.
 * [MediaCategory.Experimental] has that flag set. A reader loads the enum
 * with [MediaCategory.fromId] and reads the flag there.
 *
 * Backfill reads the single `scan_results.category` value:
 *
 * - `Sensitive` and `Immodest` → `sensitive`
 * - `Screenshot` → `screenshots`
 * - `People` → `people`
 * - `Video` → `videos`
 * - `AlcoholGambling` → `experimental`
 * - `Audio` and `Unflagged` → no row
 *
 * Audio is not split into music and other audio. The stored row has no
 * IS_MUSIC column, so this migration does not invent those ids. Screenshot
 * stays `screenshots`. Chat images and old items have no old bucket.
 * An empty set is not a clean score.
 *
 * There is no foreign key. [RoomScanResultStore.remove] deletes the category
 * rows for a key in the same transaction as the review row.
 *
 * The `CREATE` statements are the ones Room exports in `4.json`.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `item_category` (
                `media_store_id` INTEGER NOT NULL,
                `media_type` TEXT NOT NULL,
                `category_id` TEXT NOT NULL,
                PRIMARY KEY(`media_store_id`, `media_type`, `category_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_item_category_category_id`
            ON `item_category` (`category_id`)
            """.trimIndent(),
        )
        db.execSQL(categoryBackfillSql())
    }
}

/**
 * Maps each stored [ScanCategory] name onto a [MediaCategory.id].
 *
 * `Audio` and `Unflagged` are absent on purpose. The `WHERE` list is what
 * keeps those rows out. A `CASE` without a match would insert NULL into
 * `category_id`, and that column is NOT NULL.
 */
private fun categoryBackfillSql(): String {
    fun id(category: MediaCategory): String = category.id
    fun stored(category: ScanCategory): String = category.name
    return """
        INSERT INTO `item_category` (`media_store_id`, `media_type`, `category_id`)
        SELECT `media_store_id`, `media_type`,
            CASE `category`
                WHEN '${stored(ScanCategory.Sensitive)}' THEN '${id(MediaCategory.Sensitive)}'
                WHEN '${stored(ScanCategory.Immodest)}' THEN '${id(MediaCategory.Sensitive)}'
                WHEN '${stored(ScanCategory.Screenshot)}' THEN '${id(MediaCategory.Screenshots)}'
                WHEN '${stored(ScanCategory.People)}' THEN '${id(MediaCategory.People)}'
                WHEN '${stored(ScanCategory.Video)}' THEN '${id(MediaCategory.Videos)}'
                WHEN '${stored(ScanCategory.AlcoholGambling)}' THEN '${id(MediaCategory.Experimental)}'
            END
        FROM `scan_results`
        WHERE `category` IN (
            '${stored(ScanCategory.Sensitive)}',
            '${stored(ScanCategory.Immodest)}',
            '${stored(ScanCategory.Screenshot)}',
            '${stored(ScanCategory.People)}',
            '${stored(ScanCategory.Video)}',
            '${stored(ScanCategory.AlcoholGambling)}'
        )
    """.trimIndent()
}
