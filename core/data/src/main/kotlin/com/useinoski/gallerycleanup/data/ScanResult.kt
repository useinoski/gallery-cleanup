package com.useinoski.gallerycleanup.data

import com.useinoski.gallerycleanup.model.MediaType

/**
 * One scanned library file, without the file itself.
 *
 * The table stores the MediaStore id, the content URI, and the score. It does
 * not store a copy of the image, video, or audio. [contentUri] is the string
 * form of the MediaStore URI, the same shape as `MediaItem.uri`, so this module
 * does not need `android.net.Uri`. The review screen parses it when it builds
 * a trash request.
 *
 * [reviewStatus] is filled in when a row is read back. [RoomScanResultStore.upsert]
 * does not take a decision from this field: a new or replaced row is stored as
 * [ReviewStatus.Pending], and a row that is already [ReviewStatus.Kept] is left
 * unchanged. Decisions go through [RoomScanResultStore.markKept],
 * [RoomScanResultStore.markTrashed], [RoomScanResultStore.markPending], and
 * [RoomScanResultStore.resetReviewed].
 *
 * [confidence] is the detector score for [category]. Probabilities are expected
 * in the range 0 to 1. [modelVersion] names the model or the rule set that
 * produced the score, so an old row can still be explained after the detector
 * changes. Pass `"none"` when nothing classified the file.
 *
 * [scannedAtEpochMillis] is the wall-clock time of the scan, in milliseconds.
 * It is not the file's date. The review screen sorts screenshots by file date
 * from MediaStore, not from this column.
 */
data class ScanResult(
    val mediaStoreId: Long,
    val contentUri: String,
    val mediaType: MediaType,
    val category: ScanCategory,
    val confidence: Float,
    val modelVersion: String,
    val scannedAtEpochMillis: Long,
    val reviewStatus: ReviewStatus = ReviewStatus.Pending,
) {
    val key: MediaKey
        get() = MediaKey(mediaStoreId, mediaType)

    init {
        require(mediaStoreId > 0L) { "MediaStore id must be positive." }
        require(contentUri.isNotBlank()) { "Content URI is required." }
        require(confidence.isFinite()) { "Confidence must be a finite number." }
        require(modelVersion.isNotBlank()) {
            "Model version is required. Pass \"none\" when no detector ran."
        }
    }
}
