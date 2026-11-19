package com.useinoski.gallerycleanup.data

import com.useinoski.gallerycleanup.model.MediaType

internal fun sample(
    id: Long,
    type: MediaType = MediaType.Image,
    category: ScanCategory = ScanCategory.Screenshot,
    confidence: Float = 0.5f,
    modelVersion: String = "screenshot-rules-1",
    scannedAtEpochMillis: Long = 1_000,
    uri: String? = null,
    reviewStatus: ReviewStatus = ReviewStatus.Pending,
): ScanResult = ScanResult(
    mediaStoreId = id,
    contentUri = uri ?: "content://media/external/${type.collection}/media/$id",
    mediaType = type,
    category = category,
    confidence = confidence,
    modelVersion = modelVersion,
    scannedAtEpochMillis = scannedAtEpochMillis,
    reviewStatus = reviewStatus,
)

private val MediaType.collection: String
    get() = when (this) {
        MediaType.Image -> "images"
        MediaType.Video -> "video"
        MediaType.Audio -> "audio"
    }
