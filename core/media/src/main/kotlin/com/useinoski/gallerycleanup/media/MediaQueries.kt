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
internal const val COL_DATE_MODIFIED = MediaStore.MediaColumns.DATE_MODIFIED
internal const val COL_GENERATION_MODIFIED = MediaStore.MediaColumns.GENERATION_MODIFIED
internal const val COL_ID = MediaStore.MediaColumns._ID

internal const val NOT_PENDING = "$COL_PENDING = 0"
internal const val NEWEST_FIRST = "$COL_DATE_ADDED DESC, $COL_ID DESC"
internal const val MODIFIED_NEWEST_FIRST = "$COL_DATE_MODIFIED DESC, $COL_ID DESC"

/**
 * No upper bound. A library spread across more than one volume uses this,
 * because each volume has its own generation counter.
 */
internal const val NO_GENERATION_CAP = Long.MAX_VALUE

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

/**
 * Selection shared by the scan page and the scan count.
 * Generation numbers are inlined. They are longs the scanner just read, not
 * user text. [NO_GENERATION_CAP] leaves the upper bound off.
 */
internal fun scanSelection(
    generationModifiedAfter: Long?,
    generationModifiedAtMost: Long,
    after: ScanCursor?,
): String {
    val parts = mutableListOf(NOT_PENDING)
    if (generationModifiedAtMost != NO_GENERATION_CAP) {
        parts += "$COL_GENERATION_MODIFIED <= $generationModifiedAtMost"
    }
    if (generationModifiedAfter != null) {
        parts += "$COL_GENERATION_MODIFIED > $generationModifiedAfter"
    }
    if (after != null) {
        parts += "($COL_DATE_MODIFIED < ${after.dateModified} OR " +
            "($COL_DATE_MODIFIED = ${after.dateModified} AND $COL_ID < ${after.id}))"
    }
    return parts.joinToString(separator = " AND ")
}

internal fun matchesGeneration(
    generationModified: Long,
    generationModifiedAfter: Long?,
    generationModifiedAtMost: Long,
): Boolean {
    if (generationModifiedAtMost != NO_GENERATION_CAP && generationModified > generationModifiedAtMost) {
        return false
    }
    if (generationModifiedAfter != null && generationModified <= generationModifiedAfter) {
        return false
    }
    return true
}

internal fun scanPageQuery(request: ScanPageRequest): StoreQuery {
    require(request.limit > 0) { "Scan page limit must be positive." }
    return StoreQuery(
        type = request.type,
        selection = scanSelection(
            generationModifiedAfter = request.generationModifiedAfter,
            generationModifiedAtMost = request.generationModifiedAtMost,
            after = request.after,
        ),
        sortOrder = MODIFIED_NEWEST_FIRST,
        limit = request.limit + 1,
    )
}

internal fun scanCountQuery(request: ScanCountRequest): StoreQuery {
    return StoreQuery(
        type = request.type,
        selection = scanSelection(
            generationModifiedAfter = request.generationModifiedAfter,
            generationModifiedAtMost = request.generationModifiedAtMost,
            after = null,
        ),
    )
}

internal fun idPageQuery(request: IdPageRequest): StoreQuery {
    require(request.limit > 0) { "Id page limit must be positive." }
    val afterId = request.afterId
    if (afterId != null) require(afterId >= 0) { "Id cursor cannot be negative." }
    val selection = if (afterId == null) NOT_PENDING else "$NOT_PENDING AND $COL_ID > $afterId"
    return StoreQuery(
        type = request.type,
        selection = selection,
        sortOrder = "$COL_ID ASC",
        limit = request.limit + 1,
    )
}

internal fun scanPageOf(fetched: List<ScannedRow>, limit: Int): ScanPage {
    val hasMore = fetched.size > limit
    return ScanPage(
        rows = if (hasMore) fetched.take(limit) else fetched,
        hasMore = hasMore,
    )
}

internal fun idPageOf(fetched: List<Long>, limit: Int): IdPage {
    val hasMore = fetched.size > limit
    return IdPage(
        ids = if (hasMore) fetched.take(limit) else fetched,
        hasMore = hasMore,
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
