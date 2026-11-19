package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType

/**
 * Which collections the caller is allowed to read. A false entry is not queried,
 * and the matching count stays null. Null means "not allowed", which is different
 * from a count of zero.
 */
data class ReadableMedia(
    val images: Boolean,
    val videos: Boolean,
    val audio: Boolean,
)

data class MediaCounts(
    val images: Int?,
    val videos: Int?,
    val music: Int?,
    val otherAudio: Int?,
) {
    val audio: Int?
        get() = if (music == null || otherAudio == null) null else music + otherAudio
}

data class MediaPageRequest(
    val type: MediaType,
    val offset: Int,
    val limit: Int,
)

/**
 * One page of [MediaItem]s, newest first.
 * [nextOffset] is null on the last page. Pass it back as the next request's offset.
 */
data class MediaPage(
    val items: List<MediaItem>,
    val nextOffset: Int?,
)

/**
 * The only way the rest of the app reads the library.
 * Week 2's scan should page with [page] instead of adding another MediaStore call.
 */
interface MediaRepository {
    suspend fun count(readable: ReadableMedia): MediaCounts

    suspend fun page(request: MediaPageRequest): MediaPage
}
