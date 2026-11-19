package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * SQL for [ScanResultEntity]. Other modules call [RoomScanResultStore], not this.
 */
@Dao
interface ScanResultDao {
    @Upsert
    suspend fun upsertAll(rows: List<ScanResultEntity>)

    @Query(
        """
        SELECT * FROM scan_results
        WHERE category = :category AND review_status = :status
        ORDER BY scanned_at_epoch_millis DESC, media_store_id DESC
        """,
    )
    fun observeByCategoryAndStatus(
        category: ScanCategory,
        status: ReviewStatus,
    ): Flow<List<ScanResultEntity>>

    @Query(
        """
        SELECT category, COUNT(*) AS count
        FROM scan_results
        WHERE review_status = :status
        GROUP BY category
        """,
    )
    fun observeCounts(status: ReviewStatus): Flow<List<CategoryCountRow>>

    @Query(
        """
        UPDATE scan_results
        SET review_status = :status
        WHERE media_type = :type AND media_store_id IN (:ids)
        """,
    )
    suspend fun updateStatus(type: MediaType, ids: List<Long>, status: ReviewStatus)

    @Query(
        """
        DELETE FROM scan_results
        WHERE media_type = :type AND media_store_id IN (:ids)
        """,
    )
    suspend fun deleteByTypeAndIds(type: MediaType, ids: List<Long>)

    @Query(
        """
        UPDATE scan_results
        SET review_status = :pending
        WHERE review_status = :kept
        """,
    )
    suspend fun resetStatus(kept: ReviewStatus, pending: ReviewStatus)

    @Query(
        """
        SELECT media_store_id, media_type
        FROM scan_results
        WHERE review_status = :status
        """,
    )
    suspend fun keysWithStatus(status: ReviewStatus): List<MediaKeyRow>

    @Query(
        """
        SELECT * FROM scan_results
        WHERE media_store_id = :id AND media_type = :type
        LIMIT 1
        """,
    )
    suspend fun find(id: Long, type: MediaType): ScanResultEntity?
}

data class CategoryCountRow(
    val category: ScanCategory,
    val count: Int,
)

data class MediaKeyRow(
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
)
