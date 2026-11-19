package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType

/**
 * The last row already persisted, under `DATE_MODIFIED DESC, _ID DESC`.
 * The next page is strictly older than this pair. Both values come from
 * MediaStore: [dateModified] is seconds, [id] is `_ID`.
 */
data class ScanCursor(
    val dateModified: Long,
    val id: Long,
)

/**
 * A keyset page. [generationModifiedAfter] is exclusive and null means no
 * lower bound. [generationModifiedAtMost] is inclusive. [Long.MAX_VALUE]
 * means no upper bound (see [NO_GENERATION_CAP]).
 */
data class ScanPageRequest(
    val type: MediaType,
    val limit: Int,
    val after: ScanCursor? = null,
    val generationModifiedAfter: Long? = null,
    val generationModifiedAtMost: Long = NO_GENERATION_CAP,
)

/** Same generation filter as [ScanPageRequest], without a keyset cursor. */
data class ScanCountRequest(
    val type: MediaType,
    val generationModifiedAfter: Long? = null,
    val generationModifiedAtMost: Long = NO_GENERATION_CAP,
)

/**
 * One MediaStore row plus the two columns the checkpoint needs.
 * [MediaItem] itself stays the week-1 shape. `DATE_MODIFIED` and
 * `GENERATION_MODIFIED` are scan mechanics, not something the review UI has
 * to store. The store still receives [item] only.
 */
data class ScannedRow(
    val item: MediaItem,
    val dateModified: Long,
    val generationModified: Long,
)

data class ScanPage(
    val rows: List<ScannedRow>,
    val hasMore: Boolean,
)

data class IdPageRequest(
    val type: MediaType,
    val afterId: Long?,
    val limit: Int,
)

data class IdPage(
    val ids: List<Long>,
    val hasMore: Boolean,
)

/**
 * `MediaStore.getVersion` / `getGeneration` for the library, reduced to one
 * token the checkpoint can store.
 *
 * One external volume: [version] is `getVersion` and [generation] is
 * `getGeneration` for that volume. [generationUsable] is true.
 *
 * Several volumes (an SD card beside internal shared storage): the counters
 * are not comparable, so [version] is a string that changes when any volume
 * changes and [generationUsable] is false. The scanner then stores
 * [Long.MAX_VALUE] as the upper bound and does a full walk whenever the
 * token changes, instead of pretending one number covers every volume.
 */
data class VolumeStamp(
    val version: String,
    val generation: Long,
    val generationUsable: Boolean,
) {
    init {
        require(version.isNotEmpty()) { "volume version must not be empty" }
        require(generation >= 0) { "generation must be >= 0, was $generation" }
    }

    /** Value written to [com.useinoski.gallerycleanup.model.ScanCheckpoint.generationModifiedAtMost]. */
    fun storedGeneration(): Long = if (generationUsable) generation else NO_GENERATION_CAP
}

fun interface VolumeStamps {
    suspend fun read(): VolumeStamp
}

/**
 * The step that will later classify a batch.
 *
 * No detector is bound in week 2. [NONE] is the production binding. A future
 * detector receives the items here and must not delete them, upload them, or
 * assume it will see each item only once: a crash after this returns and
 * before the checkpoint is saved delivers the same batch again.
 */
fun interface ScanBatchObserver {
    suspend fun onBatch(items: List<MediaItem>)

    companion object {
        val NONE: ScanBatchObserver = ScanBatchObserver { }
    }
}
