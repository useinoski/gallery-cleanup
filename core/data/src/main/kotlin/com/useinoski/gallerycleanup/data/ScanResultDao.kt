package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * SQL for [ScanResultEntity]. Other modules call [RoomScanResultStore], not this.
 *
 * Counts and the pending list join `media_item` so a file the scanner has
 * marked removed is left out. A review row with no `media_item` row stays:
 * nothing has marked it removed. The same queries skip
 * [AnalysisOutcome.Failed], so a failed pass is not a clean
 * [ScanCategory.Unflagged] photo.
 *
 * Room registers every table named in the SQL with the invalidation tracker.
 * A write to `media_item.removed` wakes [observeByCategoryAndStatus] and
 * [observeCounts] even though `scan_results` did not change.
 */
@Dao
interface ScanResultDao {
    @Upsert
    suspend fun upsertAll(rows: List<ScanResultEntity>)

    @Query(OBSERVE_BY_CATEGORY_SQL)
    fun observeByCategoryAndStatus(
        category: ScanCategory,
        status: ReviewStatus,
        failedOutcome: AnalysisOutcome,
    ): Flow<List<ScanResultEntity>>

    @Query(OBSERVE_COUNTS_SQL)
    fun observeCounts(
        status: ReviewStatus,
        failedOutcome: AnalysisOutcome,
    ): Flow<List<CategoryCountRow>>

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

    @Query(KEYS_NEEDING_RESCAN_SQL)
    suspend fun keysNeedingRescan(
        currentModelVersions: Collection<String>,
        kept: ReviewStatus,
        failedOutcome: AnalysisOutcome,
    ): List<MediaKeyRow>

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

/**
 * A review row is visible when its media item is missing or still present.
 *
 * `media_item` is joined on the same pair the scanner writes: `type` plus
 * `media_id` here, `media_type` plus `media_store_id` on the review row.
 * `removed = 0` is the present flag. A missing media row makes `media_id`
 * null, and that row stays visible. `NOT EXISTS (... removed = 1)` is the
 * same test; the join is what this query uses so the pending list can keep
 * `scan_results.*`.
 */
private const val PRESENT_MEDIA_JOIN = """
    LEFT JOIN media_item
        ON media_item.type = scan_results.media_type
        AND media_item.media_id = scan_results.media_store_id
"""

private const val STILL_PRESENT = "(media_item.media_id IS NULL OR media_item.removed = 0)"

private const val OBSERVE_BY_CATEGORY_SQL = """
    SELECT scan_results.*
    FROM scan_results
    $PRESENT_MEDIA_JOIN
    WHERE scan_results.category = :category
        AND scan_results.review_status = :status
        AND scan_results.analysis_outcome != :failedOutcome
        AND $STILL_PRESENT
    ORDER BY scan_results.scanned_at_epoch_millis DESC, scan_results.media_store_id DESC
"""

private const val OBSERVE_COUNTS_SQL = """
    SELECT scan_results.category AS category, COUNT(*) AS count
    FROM scan_results
    $PRESENT_MEDIA_JOIN
    WHERE scan_results.review_status = :status
        AND scan_results.analysis_outcome != :failedOutcome
        AND $STILL_PRESENT
    GROUP BY scan_results.category
"""

private const val KEYS_NEEDING_RESCAN_SQL = """
    SELECT scan_results.media_store_id AS media_store_id,
        scan_results.media_type AS media_type
    FROM scan_results
    $PRESENT_MEDIA_JOIN
    WHERE scan_results.review_status != :kept
        AND (
            scan_results.analysis_outcome = :failedOutcome
            OR scan_results.model_version NOT IN (:currentModelVersions)
        )
        AND $STILL_PRESENT
"""
