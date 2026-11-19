package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.useinoski.gallerycleanup.model.MediaType

/**
 * The Room row behind [ScanResult].
 *
 * Callers use [RoomScanResultStore]. This type exists so the table can stay
 * a storage detail: column names, the primary key, and indexes.
 *
 * The primary key is the MediaStore id plus the media type. The content URI
 * is stored too, because a trash request needs it, and it is unique. There is
 * no column for image bytes.
 */
@Entity(
    tableName = "scan_results",
    primaryKeys = ["media_store_id", "media_type"],
    indices = [
        Index(value = ["content_uri"], unique = true),
        Index(value = ["category", "review_status"]),
        Index(value = ["review_status"]),
    ],
)
data class ScanResultEntity(
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "category")
    val category: ScanCategory,
    @ColumnInfo(name = "confidence")
    val confidence: Float,
    @ColumnInfo(name = "model_version")
    val modelVersion: String,
    @ColumnInfo(name = "scanned_at_epoch_millis")
    val scannedAtEpochMillis: Long,
    @ColumnInfo(name = "review_status")
    val reviewStatus: ReviewStatus,
)

internal fun ScanResult.toEntity(): ScanResultEntity = ScanResultEntity(
    mediaStoreId = mediaStoreId,
    mediaType = mediaType,
    contentUri = contentUri,
    category = category,
    confidence = confidence,
    modelVersion = modelVersion,
    scannedAtEpochMillis = scannedAtEpochMillis,
    // upsert always stores pending. Kept rows are filtered out before this runs.
    reviewStatus = ReviewStatus.Pending,
)

internal fun ScanResultEntity.toResult(): ScanResult = ScanResult(
    mediaStoreId = mediaStoreId,
    contentUri = contentUri,
    mediaType = mediaType,
    category = category,
    confidence = confidence,
    modelVersion = modelVersion,
    scannedAtEpochMillis = scannedAtEpochMillis,
    reviewStatus = reviewStatus,
)
