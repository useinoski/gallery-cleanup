package com.useinoski.gallerycleanup.data

import com.useinoski.gallerycleanup.model.MediaKey

/**
 * One library file whose decode or detector pass did not produce a score.
 *
 * [RoomScanResultStore.recordFailure] and [RoomScanResultStore.recordFailures]
 * write these. [contentUri] is the string `MediaItem.uri`. The photo bytes
 * are not part of this value.
 */
data class AnalysisFailure(
    val key: MediaKey,
    val contentUri: String,
    val scannedAtEpochMillis: Long,
) {
    init {
        require(key.id > 0L) { "MediaStore id must be positive." }
        require(contentUri.isNotBlank()) { "Content URI is required." }
    }
}

internal fun AnalysisFailure.toEntity(): ScanResultEntity = ScanResultEntity(
    mediaStoreId = key.id,
    mediaType = key.type,
    contentUri = contentUri,
    // The category column is not null. Unflagged is a placeholder, not a
    // clean decision. Queries that mean "clean" also require Scored.
    category = ScanCategory.Unflagged,
    confidence = 0f,
    modelVersion = MODEL_VERSION_NONE,
    scannedAtEpochMillis = scannedAtEpochMillis,
    reviewStatus = ReviewStatus.Pending,
    analysisOutcome = AnalysisOutcome.Failed,
)
