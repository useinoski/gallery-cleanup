package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint

/**
 * The single checkpoint row.
 *
 * [singleton] is always 1. Room entities cannot declare
 * `CHECK (singleton = 1)`, so the store writes that primary key and reads it
 * back. Saving a checkpoint replaces the row. Clearing it deletes the row.
 */
@Entity(tableName = "scan_checkpoint")
data class ScanCheckpointEntity(
    @PrimaryKey
    val singleton: Int,
    val type: MediaType,
    @ColumnInfo(name = "date_modified")
    val dateModified: Long,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    val scanned: Int,
    @ColumnInfo(name = "generation_modified_after")
    val generationModifiedAfter: Long?,
    @ColumnInfo(name = "generation_modified_at_most")
    val generationModifiedAtMost: Long,
    @ColumnInfo(name = "media_store_version")
    val mediaStoreVersion: String,
)

internal fun ScanCheckpoint.toEntity(): ScanCheckpointEntity = ScanCheckpointEntity(
    singleton = SINGLETON,
    type = type,
    dateModified = dateModified,
    mediaId = id,
    scanned = scanned,
    generationModifiedAfter = generationModifiedAfter,
    generationModifiedAtMost = generationModifiedAtMost,
    mediaStoreVersion = mediaStoreVersion,
)

internal fun ScanCheckpointEntity.toModel(): ScanCheckpoint = ScanCheckpoint(
    type = type,
    dateModified = dateModified,
    id = mediaId,
    scanned = scanned,
    generationModifiedAfter = generationModifiedAfter,
    generationModifiedAtMost = generationModifiedAtMost,
    mediaStoreVersion = mediaStoreVersion,
)

internal const val SINGLETON: Int = 1
