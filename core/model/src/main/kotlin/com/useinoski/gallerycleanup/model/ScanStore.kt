package com.useinoski.gallerycleanup.model

import kotlinx.coroutines.flow.Flow

/**
 * Identity of one MediaStore row.
 *
 * `_ID` is unique inside a collection, not across collections. An image and
 * an audio row can share the same numeric id, so both fields are required.
 * Room primary key: `([type], [id])`. Persist [type] with [MediaType.name],
 * never with the enum ordinal.
 */
data class MediaKey(
    val type: MediaType,
    val id: Long,
)

/**
 * How far a scan pass has persisted for one collection.
 *
 * There is at most one checkpoint. It is not one row per [MediaType].
 * Saving a checkpoint replaces the previous one. The worker moves to the
 * next collection by saving a checkpoint whose [type] changed, and it drops
 * the row with [ScanStore.clearCheckpoint] when the pass finishes.
 *
 * Resume reads this row and asks MediaStore for the next page *strictly
 * older* than [dateModified] / [id] under `DATE_MODIFIED DESC, _ID DESC`:
 *
 * ```
 * DATE_MODIFIED < dateModified
 * OR (DATE_MODIFIED = dateModified AND _ID < id)
 * ```
 *
 * [dateModified] is MediaStore `DATE_MODIFIED`, seconds since the Unix epoch,
 * the same unit as [MediaItem.dateAdded]. [id] is that row's `_ID`. The pair
 * is the last row that [ScanStore.upsertBatch] has already stored for this
 * pass. A crash before the checkpoint is saved repeats that page. Upserts are
 * idempotent, so the repeat is safe.
 *
 * Generation filter for the pass that produced this checkpoint:
 *
 * ```
 * GENERATION_MODIFIED <= generationModifiedAtMost
 * AND (generationModifiedAfter IS NULL OR GENERATION_MODIFIED > generationModifiedAfter)
 * ```
 *
 * [generationModifiedAtMost] is the inclusive upper bound for this pass,
 * chosen once at the start and then only read back. On a phone with a single
 * external volume it is `MediaStore.getGeneration` for that volume (not the
 * synthetic `VOLUME_EXTERNAL`, which is not a generation the platform will
 * return). Rows inserted or edited after that read have a higher generation
 * and belong to the next pass, which is why a resume must keep this bound
 * instead of calling `getGeneration` again. `Long.MAX_VALUE` means the pass
 * applied no upper bound, which is what a multi-volume library uses because
 * each volume has its own counter.
 *
 * [generationModifiedAfter] is the previous pass's
 * [ScanRun.completedVolumeGeneration], or null when this pass is a full walk
 * (first scan, or `MediaStore.getVersion` changed and generation numbers may
 * have been reset). Null means "no lower bound", not zero. Zero is a real
 * generation.
 *
 * [mediaStoreVersion] is the volume token captured with
 * [generationModifiedAtMost] at the start of the pass. For one external
 * volume it is `MediaStore.getVersion` of that volume. For several volumes
 * it is a single string that changes when any volume's version or generation
 * changes. On resume the worker compares it to a fresh read. If they differ,
 * the checkpoint is discarded and the pass starts over as a full walk. Do not
 * try to continue a keyset cursor across a version change. The store persists
 * the string; it does not interpret it.
 *
 * [scanned] counts rows of [type] already handed to [ScanStore.upsertBatch]
 * in this pass, including the row this checkpoint points at. It is not a
 * count of present rows in the table (a later pass can upsert a row again).
 */
data class ScanCheckpoint(
    val type: MediaType,
    val dateModified: Long,
    val id: Long,
    val scanned: Int,
    val generationModifiedAfter: Long?,
    val generationModifiedAtMost: Long,
    val mediaStoreVersion: String,
) {
    init {
        require(dateModified >= 0) { "dateModified must be >= 0, was $dateModified" }
        require(id >= 0) { "id must be >= 0, was $id" }
        require(scanned >= 0) { "scanned must be >= 0, was $scanned" }
        require(generationModifiedAtMost >= 0) {
            "generationModifiedAtMost must be >= 0, was $generationModifiedAtMost"
        }
        val after = generationModifiedAfter
        if (after != null) {
            require(after >= 0) { "generationModifiedAfter must be >= 0, was $after" }
            require(after <= generationModifiedAtMost) {
                "generationModifiedAfter ($after) cannot exceed generationModifiedAtMost ($generationModifiedAtMost)"
            }
        }
        require(mediaStoreVersion.isNotEmpty()) { "mediaStoreVersion must not be empty" }
    }
}

/**
 * Where a scan stands. Persist with [name], not the ordinal.
 *
 * [Idle] is the value [ScanStore.readRun] returns when nothing has been saved
 * yet. It is not written by the worker. [Running] is in progress in this
 * process or, once the store is durable, was in progress when the process
 * died. The worker sets [Running] again as it resumes.
 *
 * [Cancelled] is the user. [AccessRevoked] is a grant that disappeared, or a
 * [SecurityException] from MediaStore. Both keep the checkpoint so the next
 * start continues. [Failed] is every other error, and it also keeps the
 * checkpoint. [Completed] clears the checkpoint. The generation fields on
 * [ScanRun] are what the next start uses.
 */
enum class ScanPhase {
    Idle,
    Running,
    Completed,
    Cancelled,
    AccessRevoked,
    Failed,
}

/**
 * Progress for one collection inside the current pass.
 *
 * [scanned] and [total] are both non-negative. [total] may be smaller than
 * [scanned] if the library shrinks mid-pass; the UI must tolerate that.
 * [total] is the number of rows the current filter will visit, not always
 * the size of the whole collection: an incremental pass's total is the
 * number of changed rows.
 */
data class TypeProgress(
    val type: MediaType,
    val scanned: Int,
    val total: Int,
) {
    init {
        require(scanned >= 0) { "scanned must be >= 0, was $scanned" }
        require(total >= 0) { "total must be >= 0, was $total" }
    }
}

/**
 * The single scan-run row.
 *
 * [byType] has at most one entry per [MediaType]. Order is the order the
 * worker writes, which is [MediaType.Image], [MediaType.Video],
 * [MediaType.Audio], skipping collections the user has not granted. Replace
 * the whole list on every [ScanStore.saveRun]. Do not merge with the
 * previous list: a pass that no longer includes audio must drop the audio
 * entry by omitting it.
 *
 * [mediaStoreVersion] and [completedVolumeGeneration] describe the last pass
 * that reached [ScanPhase.Completed] and whose `MediaStore.getVersion` was
 * unchanged from the start of that pass to the end. Together they are the
 * incremental cursor:
 *
 * - version unchanged and generation unchanged: nothing was inserted,
 *   updated, or deleted. The worker does not open a cursor.
 * - version unchanged and generation greater: the next pass reads
 *   `GENERATION_MODIFIED > completedVolumeGeneration` and
 *   `GENERATION_MODIFIED <= ` the new `getGeneration()`, then reconciles ids.
 * - version string changed: generation numbers may have been reset. The
 *   worker clears the checkpoint and walks every granted collection.
 *
 * [completedVolumeGeneration] is the generation read at the *start* of the
 * pass that completed, which is that pass's upper bound. It is not the
 * generation read at the end. A generation that moved during the pass means
 * some rows were left for the next pass on purpose. A null generation with a
 * null version means "no completed pass" or "the version changed during the
 * pass, do a full walk next time". A non-null generation requires a non-empty
 * version. The other way around is allowed: a version with a null generation
 * means the pass finished but did not cover any generation.
 *
 * [detail] is a short human sentence for [ScanPhase.Failed] and
 * [ScanPhase.AccessRevoked]. Null for the other phases. It is not the only
 * signal the UI has; [phase] is.
 */
data class ScanRun(
    val phase: ScanPhase,
    val byType: List<TypeProgress>,
    val mediaStoreVersion: String?,
    val completedVolumeGeneration: Long?,
    val detail: String?,
) {
    init {
        require(byType.distinctBy { progress -> progress.type }.size == byType.size) {
            "byType has one entry per MediaType"
        }
        val generation = completedVolumeGeneration
        if (generation != null) {
            require(generation >= 0) { "completedVolumeGeneration must be >= 0, was $generation" }
            require(!mediaStoreVersion.isNullOrEmpty()) {
                "completedVolumeGeneration requires mediaStoreVersion"
            }
        }
    }

    companion object {
        val IDLE: ScanRun = ScanRun(
            phase = ScanPhase.Idle,
            byType = emptyList(),
            mediaStoreVersion = null,
            completedVolumeGeneration = null,
            detail = null,
        )
    }
}

/**
 * What the home screen renders. It is [ScanRun] without the generation
 * cursor, which the screen does not need.
 *
 * [scanned] and [total] sum [byType]. Either can be zero. Do not divide
 * [scanned] by [total] without a zero check.
 */
data class ScanProgress(
    val phase: ScanPhase,
    val byType: List<TypeProgress>,
    val detail: String?,
) {
    val scanned: Int
        get() = byType.sumOf { progress -> progress.scanned }

    val total: Int
        get() = byType.sumOf { progress -> progress.total }

    companion object {
        fun from(run: ScanRun): ScanProgress = ScanProgress(
            phase = run.phase,
            byType = run.byType,
            detail = run.detail,
        )
    }
}

/**
 * Write-side store for one library scan.
 *
 * `:core:data` implements this with Room. The scan worker and the home
 * screen depend on this interface only. This module stays plain Kotlin: no
 * Android SDK, no Room, no `ContentResolver`. `:core:data` depends on
 * `:core:model`. `:core:model` must not depend on `:core:data`.
 *
 * The in-memory stand-in is [InMemoryScanStore]. It is what the app binds
 * until a Room implementation replaces it. Tests of the worker use it too.
 * It does not survive process death. A Room implementation must.
 *
 * ## What this store is not
 *
 * It does not delete files, write to MediaStore, or open a network
 * connection. [markRemoved] records that a row is no longer returned by
 * MediaStore (the user deleted it elsewhere, or it left a partial photo
 * selection). The file, if it still exists, stays where it is.
 *
 * ## Suggested Room tables
 *
 * One database, three tables, no foreign keys. The worker never needs a
 * transaction that spans two of these methods. Each method is its own
 * transaction.
 *
 * ```
 * media_item (
 *   type TEXT NOT NULL,          -- MediaType.name
 *   media_id INTEGER NOT NULL,   -- MediaStore _ID
 *   uri TEXT NOT NULL,
 *   date_added INTEGER NOT NULL,
 *   size INTEGER NOT NULL,
 *   path TEXT,
 *   mime_type TEXT,
 *   removed INTEGER NOT NULL,    -- 0 present, 1 removed
 *   PRIMARY KEY (type, media_id)
 * )
 *
 * scan_checkpoint (
 *   singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
 *   type TEXT NOT NULL,
 *   date_modified INTEGER NOT NULL,
 *   media_id INTEGER NOT NULL,
 *   scanned INTEGER NOT NULL,
 *   generation_modified_after INTEGER,          -- null = no lower bound
 *   generation_modified_at_most INTEGER NOT NULL,
 *   media_store_version TEXT NOT NULL
 * )
 *
 * scan_run (
 *   singleton INTEGER NOT NULL PRIMARY KEY CHECK (singleton = 1),
 *   phase TEXT NOT NULL,                       -- ScanPhase.name
 *   media_store_version TEXT,
 *   completed_volume_generation INTEGER,
 *   detail TEXT
 * )
 *
 * scan_type_progress (
 *   type TEXT NOT NULL PRIMARY KEY,            -- MediaType.name
 *   scanned INTEGER NOT NULL,
 *   total INTEGER NOT NULL
 * )
 * ```
 *
 * Replace every `scan_type_progress` row inside the same transaction as the
 * `scan_run` update. A missing `scan_run` row is [ScanRun.IDLE], not an error.
 * A missing `scan_checkpoint` row is a null checkpoint, not an error.
 *
 * ## Call order the worker actually uses
 *
 * For each page: [upsertBatch], then [saveCheckpoint], then [saveRun].
 * A crash can land between any two. The next start reads the checkpoint and
 * repeats the page that was not yet recorded. [upsertBatch] of a row that is
 * already present overwrites the columns and clears `removed`.
 *
 * After the last page of every granted collection, the worker reads
 * [presentKeys] and calls [markRemoved] for ids MediaStore no longer returns.
 * Then [clearCheckpoint] and a final [saveRun] of [ScanPhase.Completed].
 *
 * Cancel, access revoked, and failure [saveRun] and leave the checkpoint in
 * place.
 *
 * ## Threading
 *
 * Every suspend function is safe to call off the main thread. Room should
 * use its own query dispatcher and must not block the caller's thread with
 * disk. The in-memory implementation may return without actually suspending.
 *
 * One scan runs at a time (WorkManager unique work). The UI collects
 * [observeProgress] while those writes happen. Writes must be linearizable
 * with each other: a [readCheckpoint] that starts after [saveCheckpoint]
 * returns sees that checkpoint. A collector that is slow may skip an
 * intermediate progress snapshot, but a new collector sees the latest run.
 *
 * ## [observeProgress]
 *
 * Emits the latest [ScanProgress] immediately on collect, then again after
 * each [saveRun] whose progress differs. Checkpoint, upsert, and removal do
 * not emit. The flow does not complete. Collection is cancellable. An empty
 * store emits [ScanPhase.Idle], not an error and not an empty flow.
 * Identical snapshots may be conflated ([ScanProgress] equality).
 */
interface ScanStore {
    /**
     * Inserts or replaces each item.
     *
     * The key is [MediaItem.type] plus [MediaItem.id]. The last copy of a key
     * inside [items] wins. An empty list is a no-op. A row previously marked
     * removed becomes present again, because MediaStore returned it.
     */
    suspend fun upsertBatch(items: List<MediaItem>)

    /**
     * Marks these rows removed. Does not delete the user's files.
     *
     * Unknown keys are ignored. Keys that are already removed stay removed.
     * An empty list is a no-op. A later [upsertBatch] of the same key clears
     * the flag.
     *
     * SQLite's variable limit is small (often 999). Chunk an `IN` clause,
     * or write the ids into a temp table, rather than binding the whole list
     * at once. The worker may pass every missing id of one collection in a
     * single call.
     */
    suspend fun markRemoved(keys: List<MediaKey>)

    /**
     * Keys of [type] that are stored and not marked removed.
     *
     * Order is unspecified. No duplicates. An empty store returns an empty
     * list. The worker loads this once per collection at the end of a pass
     * and diffs it against the ids MediaStore still returns. A large library
     * is a list of longs, which is the intended cost. Do not return removed
     * rows.
     */
    suspend fun presentKeys(type: MediaType): List<MediaKey>

    /** The current checkpoint, or null when the pass is not in the middle. */
    suspend fun readCheckpoint(): ScanCheckpoint?

    /** Replaces the single checkpoint. See [ScanCheckpoint]. */
    suspend fun saveCheckpoint(checkpoint: ScanCheckpoint)

    /** Next [readCheckpoint] returns null. A second call is a no-op. */
    suspend fun clearCheckpoint()

    /** The current run, or [ScanRun.IDLE] when [saveRun] has never been called. */
    suspend fun readRun(): ScanRun

    /**
     * Replaces the single run row and, when the progress changed, emits on
     * [observeProgress]. Copies [ScanRun.byType]; later mutation of the
     * caller's list must not change what was saved.
     */
    suspend fun saveRun(run: ScanRun)

    /**
     * Latest progress, then updates from [saveRun]. See the interface KDoc.
     * Room can implement this as a cold `Flow` that queries on collect and
     * re-queries when the run or its progress rows change. That matches this
     * contract, including the immediate first emission.
     */
    fun observeProgress(): Flow<ScanProgress>
}
