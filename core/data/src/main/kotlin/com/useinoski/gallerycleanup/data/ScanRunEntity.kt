package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.useinoski.gallerycleanup.model.ScanPhase

/**
 * The single scan-run row. Progress for each collection lives in
 * [TypeProgressEntity], replaced in the same transaction.
 *
 * A missing row is [com.useinoski.gallerycleanup.model.ScanRun.IDLE]. The
 * worker does not write [ScanPhase.Idle].
 */
@Entity(tableName = "scan_run")
data class ScanRunEntity(
    @PrimaryKey
    val singleton: Int,
    val phase: ScanPhase,
    @ColumnInfo(name = "media_store_version")
    val mediaStoreVersion: String?,
    @ColumnInfo(name = "completed_volume_generation")
    val completedVolumeGeneration: Long?,
    val detail: String?,
)
