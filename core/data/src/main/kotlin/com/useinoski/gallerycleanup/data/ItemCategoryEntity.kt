package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaType

/**
 * One [MediaCategory] on one media item. A file can have many rows.
 *
 * The key is the same pair as `scan_results` (`media_store_id`, `media_type`)
 * plus the category id. There is no foreign key. The other tables in this
 * database do not use them, and [MIGRATION_3_4] keeps that shape.
 * [RoomScanResultStore.remove] deletes these rows in the same transaction as
 * the review row, so a removed review item does not leave category rows behind.
 *
 * [category] is stored as [MediaCategory.id] by [MediaCategoryConverter].
 * `sensitive` and `experimental` stay on the enum. [MediaCategory.Experimental]
 * is experimental. Version 4 does not add a column for that flag.
 *
 * No row for an item is an empty category set. That is not a clean score.
 * [AnalysisOutcome], `failure_count`, and [ReviewStatus] stay on `scan_results`.
 * Access revoked stays on the scan run.
 */
@Entity(
    tableName = "item_category",
    primaryKeys = ["media_store_id", "media_type", "category_id"],
    indices = [
        Index(value = ["category_id"]),
    ],
)
@TypeConverters(MediaCategoryConverter::class)
data class ItemCategoryEntity(
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "category_id")
    val category: MediaCategory,
)
