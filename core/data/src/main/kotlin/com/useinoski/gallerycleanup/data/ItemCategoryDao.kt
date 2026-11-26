package com.useinoski.gallerycleanup.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * SQL for [ItemCategoryEntity].
 *
 * [replace] is the write. It deletes the item's category rows and inserts the
 * new set. An empty set leaves no rows and does not write a clean marker.
 * `scan_results` is not updated here: outcome, failure count, and review
 * status stay on that table.
 *
 * [observePending] reads items that have [category], are still pending, were
 * scored, and are still present. A failed row can have category rows and
 * still stay out of this list. An item with no category rows is in no list.
 */
@Dao
@TypeConverters(MediaCategoryConverter::class)
abstract class ItemCategoryDao {
    @Insert
    abstract suspend fun insertAll(rows: List<ItemCategoryEntity>)

    @Query(
        """
        DELETE FROM item_category
        WHERE media_store_id = :mediaStoreId AND media_type = :mediaType
        """,
    )
    abstract suspend fun deleteFor(mediaStoreId: Long, mediaType: MediaType)

    @Query(
        """
        DELETE FROM item_category
        WHERE media_type = :type AND media_store_id IN (:ids)
        """,
    )
    abstract suspend fun deleteByTypeAndIds(type: MediaType, ids: List<Long>)

    @Query(
        """
        SELECT category_id FROM item_category
        WHERE media_store_id = :mediaStoreId AND media_type = :mediaType
        """,
    )
    abstract suspend fun categoryIds(mediaStoreId: Long, mediaType: MediaType): List<MediaCategory>

    /**
     * Drops the category rows for this item and inserts [categories].
     *
     * Empty [categories] stops after the delete. The review row is unchanged,
     * including a [AnalysisOutcome.Failed] row and its `failure_count`.
     */
    @Transaction
    open suspend fun replace(
        mediaStoreId: Long,
        mediaType: MediaType,
        categories: Set<MediaCategory>,
    ) {
        deleteFor(mediaStoreId, mediaType)
        if (categories.isEmpty()) return
        insertAll(
            categories.map { category ->
                ItemCategoryEntity(
                    mediaStoreId = mediaStoreId,
                    mediaType = mediaType,
                    category = category,
                )
            },
        )
    }

    @Query(OBSERVE_PENDING_BY_MEDIA_CATEGORY_SQL)
    abstract fun observePending(
        category: MediaCategory,
        status: ReviewStatus,
        failedOutcome: AnalysisOutcome,
    ): Flow<List<ScanResultEntity>>
}

/**
 * Pending scored rows that have this category and are still in the library.
 *
 * The join is the same present-file test as [ScanResultDao]: a missing
 * `media_item` row stays visible, and `removed = 1` hides the row. Category
 * membership is `item_category`, not `scan_results.category`.
 */
private const val OBSERVE_PENDING_BY_MEDIA_CATEGORY_SQL = """
    SELECT scan_results.*
    FROM scan_results
    INNER JOIN item_category
        ON item_category.media_store_id = scan_results.media_store_id
        AND item_category.media_type = scan_results.media_type
    LEFT JOIN media_item
        ON media_item.type = scan_results.media_type
        AND media_item.media_id = scan_results.media_store_id
    WHERE item_category.category_id = :category
        AND scan_results.review_status = :status
        AND scan_results.analysis_outcome != :failedOutcome
        AND (media_item.media_id IS NULL OR media_item.removed = 0)
    ORDER BY scan_results.scanned_at_epoch_millis DESC, scan_results.media_store_id DESC
"""
