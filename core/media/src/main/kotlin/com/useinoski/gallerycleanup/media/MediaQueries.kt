package com.useinoski.gallerycleanup.media

import android.provider.MediaStore
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType

/**
 * Column names from MediaStore, held as Kotlin constants so the query text can
 * be checked in JVM tests. The Java fields are `static final String`, so the
 * compiler copies the value in and the test never calls Android code.
 */
internal const val COL_PENDING = MediaStore.MediaColumns.IS_PENDING
internal const val COL_MUSIC = MediaStore.Audio.Media.IS_MUSIC
internal const val COL_DATE_ADDED = MediaStore.MediaColumns.DATE_ADDED
internal const val COL_ID = MediaStore.MediaColumns._ID

internal const val NOT_PENDING = "$COL_PENDING = 0"
internal const val NEWEST_FIRST = "$COL_DATE_ADDED DESC, $COL_ID DESC"

internal data class StoreQuery(
    val type: MediaType,
    val selection: String,
    val sortOrder: String? = null,
    val offset: Int? = null,
    val limit: Int? = null,
)

internal fun countQuery(type: MediaType, music: Boolean?): StoreQuery {
    val selection = when (music) {
        null -> NOT_PENDING
        true -> "$NOT_PENDING AND $COL_MUSIC != 0"
        false -> "$NOT_PENDING AND $COL_MUSIC = 0"
    }
    return StoreQuery(type = type, selection = selection)
}

/** Asks the store for one extra row so the caller can tell whether another page exists. */
internal fun pageQuery(request: MediaPageRequest): StoreQuery {
    return StoreQuery(
        type = request.type,
        selection = NOT_PENDING,
        sortOrder = NEWEST_FIRST,
        offset = request.offset,
        limit = request.limit + 1,
    )
}

internal fun assembleCounts(
    readable: ReadableMedia,
    fetch: (StoreQuery) -> Int,
): MediaCounts {
    return MediaCounts(
        images = if (readable.images) fetch(countQuery(MediaType.Image, music = null)) else null,
        videos = if (readable.videos) fetch(countQuery(MediaType.Video, music = null)) else null,
        music = if (readable.audio) fetch(countQuery(MediaType.Audio, music = true)) else null,
        otherAudio = if (readable.audio) fetch(countQuery(MediaType.Audio, music = false)) else null,
    )
}

internal fun pageOf(fetched: List<MediaItem>, offset: Int, limit: Int): MediaPage {
    val hasMore = fetched.size > limit
    val items = if (hasMore) fetched.take(limit) else fetched
    return MediaPage(
        items = items,
        nextOffset = if (hasMore) offset + items.size else null,
    )
}

/** `DCIM/Camera/` plus `IMG_0001.jpg` becomes `DCIM/Camera/IMG_0001.jpg`. */
internal fun relativeItemPath(relativeFolder: String?, displayName: String?): String? {
    val folder = relativeFolder?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        folder == null && name == null -> null
        folder == null -> name
        name == null -> folder
        else -> "$folder/$name"
    }
}
