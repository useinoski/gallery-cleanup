package com.useinoski.gallerycleanup.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import kotlinx.coroutines.flow.Flow

/**
 * SQL for [ScanStore]. Other modules call [RoomScanResultStore], not this.
 *
 * [observeRunRows] is one statement over `scan_run` and `scan_type_progress`,
 * so Room's invalidation tracker wakes the flow only when one of those tables
 * changes. Item upserts and checkpoint writes do not. A missing run row is an
 * empty list. A run with no per-type rows is one list entry whose progress
 * columns are null, because of the left join.
 */
@Dao
interface ScanStoreDao {
    @Upsert
    suspend fun upsertMedia(rows: List<MediaItemEntity>)

    @Query(
        """
        UPDATE media_item
        SET removed = 1
        WHERE type = :type AND media_id IN (:ids)
        """,
    )
    suspend fun markRemoved(type: MediaType, ids: List<Long>)

    @Query(
        """
        SELECT type, media_id AS id
        FROM media_item
        WHERE type = :type AND removed = 0
        """,
    )
    suspend fun presentKeys(type: MediaType): List<MediaKey>

    @Query(
        """
        SELECT * FROM media_item
        WHERE type = :type AND media_id = :id
        LIMIT 1
        """,
    )
    suspend fun findMedia(type: MediaType, id: Long): MediaItemEntity?

    /**
     * Present rows for these ids. Removed rows are left out. An id that is
     * not stored is left out. Callers chunk [ids] under SQLite's variable limit.
     */
    @Query(
        """
        SELECT * FROM media_item
        WHERE type = :type AND removed = 0 AND media_id IN (:ids)
        """,
    )
    suspend fun presentMedia(type: MediaType, ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM scan_checkpoint WHERE singleton = 1")
    suspend fun readCheckpoint(): ScanCheckpointEntity?

    @Upsert
    suspend fun saveCheckpoint(row: ScanCheckpointEntity)

    @Query("DELETE FROM scan_checkpoint")
    suspend fun clearCheckpoint()

    @Upsert
    suspend fun saveRun(row: ScanRunEntity)

    @Query("DELETE FROM scan_type_progress")
    suspend fun deleteProgress()

    @Insert
    suspend fun insertProgress(rows: List<TypeProgressEntity>)

    @Query(RUN_ROWS_SQL)
    fun observeRunRows(): Flow<List<RunProgressRow>>

    @Query(RUN_ROWS_SQL)
    suspend fun readRunRows(): List<RunProgressRow>
}

/**
 * One joined row of the run plus one progress entry.
 *
 * Progress columns are null when the run has an empty [ScanRun.byType].
 */
data class RunProgressRow(
    val phase: ScanPhase,
    val mediaStoreVersion: String?,
    val completedVolumeGeneration: Long?,
    val detail: String?,
    val progressType: MediaType?,
    val progressScanned: Int?,
    val progressTotal: Int?,
    val progressPosition: Int?,
)

private const val RUN_ROWS_SQL = """
    SELECT
        scan_run.phase AS phase,
        scan_run.media_store_version AS mediaStoreVersion,
        scan_run.completed_volume_generation AS completedVolumeGeneration,
        scan_run.detail AS detail,
        scan_type_progress.type AS progressType,
        scan_type_progress.scanned AS progressScanned,
        scan_type_progress.total AS progressTotal,
        scan_type_progress.position AS progressPosition
    FROM scan_run
    LEFT JOIN scan_type_progress ON 1 = 1
    WHERE scan_run.singleton = 1
    ORDER BY scan_type_progress.position
"""
