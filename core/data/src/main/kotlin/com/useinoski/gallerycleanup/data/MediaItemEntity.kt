package com.useinoski.gallerycleanup.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType

/**
 * One MediaStore row the scan has seen.
 *
 * [removed] is 1 after [ScanStoreDao.markRemoved]. The file itself is not
 * deleted. A later [MediaItem.toEntity] writes 0 again, because MediaStore
 * returned the row.
 */
@Entity(
    tableName = "media_item",
    primaryKeys = ["type", "media_id"],
)
data class MediaItemEntity(
    val type: MediaType,
    @ColumnInfo(name = "media_id")
    val mediaId: Long,
    val uri: String,
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,
    val size: Long,
    val path: String?,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    val removed: Boolean,
)

internal fun MediaItem.toEntity(): MediaItemEntity = MediaItemEntity(
    type = type,
    mediaId = id,
    uri = uri,
    dateAdded = dateAdded,
    size = size,
    path = path,
    mimeType = mimeType,
    removed = false,
)
