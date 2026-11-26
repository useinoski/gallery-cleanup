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
        Index(value = ["analysis_outcome", "model_version"]),
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
    /**
     * `Scored` unless [RoomScanResultStore.recordFailure] wrote the row.
     * The SQL default matches [MIGRATION_2_3], which adds the column as
     * `Scored` and then marks pending `Unflagged` / `none` rows `Failed`.
     */
    @ColumnInfo(name = "analysis_outcome", defaultValue = "'Scored'")
    val analysisOutcome: AnalysisOutcome,
    /**
     * How many times analysis of this file has failed since the last score.
     * [RoomScanResultStore.recordFailure] adds one. A scored [RoomScanResultStore.upsert]
     * sets it back to 0. [RoomScanResultStore.keysNeedingRescan] does not read it.
     */
    @ColumnInfo(name = "failure_count", defaultValue = "0")
    val failureCount: Int,
)

internal fun ScanResult.toEntity(): ScanResultEntity = ScanResultEntity(
    mediaStoreId = mediaStoreId,
    mediaType = mediaType,
    contentUri = contentUri,
    category = category,
    confidence = confidence,
    modelVersion = modelVersion,
    scannedAtEpochMillis = scannedAtEpochMillis,
    // The store fills review status and failure count from the row already
    // stored. A new row is pending, scored, and has never failed.
    reviewStatus = ReviewStatus.Pending,
    analysisOutcome = AnalysisOutcome.Scored,
    failureCount = 0,
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
    analysisOutcome = analysisOutcome,
)
