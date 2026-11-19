package com.useinoski.gallerycleanup.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room store of one scored row per library file.
 *
 * This is the Room side, not the shared scan contract. The shared contract is
 * `ScanStore` in `:core:model` (open, not on `main` yet). This class will
 * implement that interface once it lands. Until then the scan worker keeps
 * using `InMemoryScanStore` and must not bind this type as its store.
 *
 * What this class stores today is the review row: one category, a confidence
 * score, the model or rule-set version, and whether the user kept the file,
 * trashed it, or has not decided. It does not store image bytes.
 *
 * Writes are chunked. SQLite rejects a statement with too many `?` placeholders
 * (999 on older builds, far more on Android 11, which is our minimum). A trash
 * batch can be up to 2000 URIs, so one `IN (:ids)` list would be unsafe.
 *
 * Obtain an instance with [roomScanResultStore]. There is no DI framework.
 */
class RoomScanResultStore internal constructor(
    private val database: GalleryDatabase,
) {

    private val dao = database.scanResultDao()

    /**
     * Inserts or replaces each result, matched on [MediaKey].
     *
     * The last copy of a key in [results] wins. A row already marked
     * [ReviewStatus.Kept] is not written: category, score, model version,
     * timestamp, URI, and status all stay. Call [keptKeys] before a detector
     * run so those files are skipped. This method is the backstop when a
     * batch still contains one.
     *
     * Every other row is stored as [ReviewStatus.Pending], including a row
     * that was [ReviewStatus.Trashed]. The [ScanResult.reviewStatus] on the
     * argument is ignored. A file that shows up in a later scan is visible
     * again, so the old trash mark would be a lie.
     */
    suspend fun upsert(results: List<ScanResult>) {
        if (results.isEmpty()) return
        val latestByKey = LinkedHashMap<MediaKey, ScanResult>(results.size)
        for (result in results) {
            latestByKey[result.key] = result
        }
        database.withTransaction {
            val kept = keptKeySet()
            val rows = ArrayList<ScanResultEntity>(latestByKey.size)
            for ((key, result) in latestByKey) {
                if (key !in kept) rows += result.toEntity()
            }
            rows.chunked(UPSERT_CHUNK).forEach { chunk -> dao.upsertAll(chunk) }
        }
    }

    /**
     * Items in [category] that are still [ReviewStatus.Pending], newest scan
     * first. Kept and trashed rows are absent. [ScanCategory.Unflagged] is a
     * valid argument; those rows were scanned and nothing was suggested.
     *
     * The flow emits the current list, then emits again when the table changes.
     * Nothing is queried until a collector starts.
     */
    fun observePending(category: ScanCategory): Flow<List<ScanResult>> {
        return dao.observeByCategoryAndStatus(category, ReviewStatus.Pending)
            .map { rows -> rows.map(ScanResultEntity::toResult) }
    }

    /**
     * Pending counts for every category, including zeros. Kept and trashed
     * rows are not counted. Emits again when the table changes.
     */
    fun observePendingCounts(): Flow<CategoryCounts> {
        return dao.observeCounts(ReviewStatus.Pending).map { rows ->
            val found = rows.associate { row -> row.category to row.count }
            CategoryCounts(
                pending = ScanCategory.entries.associateWith { category -> found[category] ?: 0 },
            )
        }
    }

    /** Marks these items kept. Keys that are not in the table are ignored. */
    suspend fun markKept(keys: Collection<MediaKey>) {
        updateStatus(keys, ReviewStatus.Kept)
    }

    /**
     * Marks these items trashed. The rows stay until [remove], so the review
     * screen can call [markPending] if the system dialog is cancelled.
     */
    suspend fun markTrashed(keys: Collection<MediaKey>) {
        updateStatus(keys, ReviewStatus.Trashed)
    }

    /** Puts these items back to pending. Missing keys are ignored. */
    suspend fun markPending(keys: Collection<MediaKey>) {
        updateStatus(keys, ReviewStatus.Pending)
    }

    /**
     * Deletes these rows after the system trash request succeeds.
     * This drops the score only. The photo bytes were never stored here.
     * Works for any status, so only pass the keys the dialog just trashed.
     */
    suspend fun remove(keys: Collection<MediaKey>) {
        if (keys.isEmpty()) return
        database.withTransaction {
            keys.groupBy { key -> key.mediaType }.forEach { (type, grouped) ->
                grouped.map { key -> key.mediaStoreId }.distinct().chunked(ID_CHUNK).forEach { ids ->
                    dao.deleteByTypeAndIds(type, ids)
                }
            }
        }
    }

    /**
     * Every kept item becomes pending again. Trashed rows are not changed.
     * The next scan may replace a row that this call returned to pending.
     */
    suspend fun resetReviewed() {
        dao.resetStatus(kept = ReviewStatus.Kept, pending = ReviewStatus.Pending)
    }

    /**
     * Identities a later detector pass must not classify again.
     * Only [ReviewStatus.Kept]. Pending rows can be scored again so a newer
     * model can move them. Trashed rows are omitted: MediaStore does not
     * return trashed files unless a query asks for them, and this app does not.
     */
    suspend fun keptKeys(): Set<MediaKey> = keptKeySet()

    private suspend fun keptKeySet(): Set<MediaKey> {
        return dao.keysWithStatus(ReviewStatus.Kept)
            .map { row -> MediaKey(row.mediaStoreId, row.mediaType) }
            .toSet()
    }

    private suspend fun updateStatus(keys: Collection<MediaKey>, status: ReviewStatus) {
        if (keys.isEmpty()) return
        database.withTransaction {
            keys.groupBy { key -> key.mediaType }.forEach { (type, grouped) ->
                grouped.map { key -> key.mediaStoreId }.distinct().chunked(ID_CHUNK).forEach { ids ->
                    dao.updateStatus(type, ids, status)
                }
            }
        }
    }

    private companion object {
        // 8 columns per row. 100 stays under the old 999-placeholder limit.
        const val UPSERT_CHUNK = 100

        // ids plus the type and status placeholders.
        const val ID_CHUNK = 500
    }
}
