package com.useinoski.gallerycleanup.data

import androidx.room.withTransaction
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.ScanStore
import com.useinoski.gallerycleanup.model.TypeProgress
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Room implementation of [ScanStore], plus the review rows for keep / trash.
 *
 * The scan contract uses `media_item`, `scan_checkpoint`, `scan_run`, and
 * `scan_type_progress`. [upsertBatch] inserts or replaces a library row and
 * clears `removed`. [markRemoved] records that MediaStore no longer returns
 * the row. It does not delete the file. [observeProgress] reads the run and
 * its per-type rows, and it does not emit for item, checkpoint, or review
 * writes.
 *
 * The review API is not part of [ScanStore]. It uses `scan_results`:
 * [upsert], [recordFailure], [markKept], [markTrashed], [markPending],
 * [remove], [resetReviewed], [keptKeys], [keysNeedingRescan],
 * [observePending], and [observePendingCounts]. [upsert] stores
 * [ReviewStatus.Pending] and [AnalysisOutcome.Scored], and never overwrites
 * a kept row. [recordFailure] stores [AnalysisOutcome.Failed] for one key
 * and also leaves a kept row alone. [remove] deletes a review row after the
 * system trash dialog succeeds. It is not [markRemoved].
 *
 * Writes are chunked. SQLite rejects a statement with too many `?`
 * placeholders (999 on older builds). A trash batch can be up to 2000 URIs.
 *
 * Obtain an instance with [roomScanResultStore]. There is no DI framework.
 */
class RoomScanResultStore internal constructor(
    private val database: GalleryDatabase,
) : ScanStore {

    private val results = database.scanResultDao()
    private val scans = database.scanStoreDao()

    /**
     * Inserts or replaces each result, matched on [MediaKey].
     *
     * The last copy of a key in [results] wins. A row already marked
     * [ReviewStatus.Kept] is not written: category, score, model version,
     * timestamp, URI, and status all stay. Call [keptKeys] before a detector
     * run so those files are skipped. This method is the backstop when a
     * batch still contains one.
     *
     * Every other row is stored as [ReviewStatus.Pending] and
     * [AnalysisOutcome.Scored], including a row that was
     * [ReviewStatus.Trashed] or [AnalysisOutcome.Failed]. The
     * [ScanResult.reviewStatus] and [ScanResult.analysisOutcome] on the
     * argument are ignored. A file that shows up in a later scan is visible
     * again, so the old trash mark would be a lie. A scored write is also
     * how a previous failure leaves the rescan set.
     */
    suspend fun upsert(results: List<ScanResult>) {
        if (results.isEmpty()) return
        val latestByKey = LinkedHashMap<MediaKey, ScanResult>(results.size)
        for (result in results) {
            latestByKey[result.key] = result
        }
        writeUnlessKept(latestByKey.values.map { result -> result.toEntity() })
    }

    /**
     * Records that analysis of [key] failed.
     *
     * The row is [AnalysisOutcome.Failed], [ReviewStatus.Pending], category
     * [ScanCategory.Unflagged], confidence 0, and model version
     * [MODEL_VERSION_NONE]. Those category and version values are
     * placeholders. [observePending] and [observePendingCounts] skip the row,
     * so it is not a clean photo. [keysNeedingRescan] returns it until a
     * later [upsert] stores a scored row.
     *
     * A row already marked [ReviewStatus.Kept] is left unchanged, same as
     * [upsert]. [contentUri] is `MediaItem.uri`. It has to be unique in the
     * table.
     *
     * [scannedAtEpochMillis] defaults to the current wall clock. A Kotlin
     * caller can omit it. A Java caller has the two-argument overload from
     * [JvmOverloads] and the three-argument form when the clock is injected.
     */
    @JvmOverloads
    suspend fun recordFailure(
        key: MediaKey,
        contentUri: String,
        scannedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        recordFailures(
            listOf(
                AnalysisFailure(
                    key = key,
                    contentUri = contentUri,
                    scannedAtEpochMillis = scannedAtEpochMillis,
                ),
            ),
        )
    }

    /**
     * Records each failed analysis. The last copy of a key in [failures]
     * wins. An empty list does nothing. Kept rows are not written.
     */
    suspend fun recordFailures(failures: List<AnalysisFailure>) {
        if (failures.isEmpty()) return
        val latestByKey = LinkedHashMap<MediaKey, AnalysisFailure>(failures.size)
        for (failure in failures) {
            latestByKey[failure.key] = failure
        }
        writeUnlessKept(latestByKey.values.map { failure -> failure.toEntity() })
    }

    /**
     * Keys the scanner should classify again.
     *
     * A row is included when analysis [AnalysisOutcome.Failed], or when its
     * stored `model_version` is not in [currentModelVersions]. Pass every
     * version string the current pipeline still writes. That includes each
     * detector identity (`detectorId|modelVersion`, the same text [upsert]
     * stores) and each rule name. Detector id is not its own column. A row
     * from another detector has a different string, so passing only one
     * detector's version also returns the other detectors' rows.
     *
     * [ReviewStatus.Kept] rows are excluded, including a kept row migration
     * marked failed. Rows whose `media_item` is marked removed are excluded.
     * A review row with no `media_item` row is included. Trashed rows are
     * included when they are stale: [upsert] already replaces a trashed row
     * when the file shows up again. The caller should open only keys it
     * still has bytes for.
     *
     * [currentModelVersions] must be non-empty, and each entry must be
     * non-blank. Include [MODEL_VERSION_NONE] when a clean photo scored by
     * the current pipeline should stay put. Leave it out when a model change
     * should score those clean rows again.
     */
    suspend fun keysNeedingRescan(currentModelVersions: Collection<String>): List<MediaKey> {
        require(currentModelVersions.isNotEmpty()) {
            "Pass at least one current model version."
        }
        require(currentModelVersions.all { version -> version.isNotBlank() }) {
            "Model version is required."
        }
        val versions = currentModelVersions.distinct()
        return results.keysNeedingRescan(
            currentModelVersions = versions,
            kept = ReviewStatus.Kept,
            failedOutcome = AnalysisOutcome.Failed,
        ).map { row -> MediaKey(type = row.mediaType, id = row.mediaStoreId) }
    }

    /** One current version. Same rules as [keysNeedingRescan]. */
    suspend fun keysNeedingRescan(currentModelVersion: String): List<MediaKey> {
        require(currentModelVersion.isNotBlank()) { "Model version is required." }
        return keysNeedingRescan(listOf(currentModelVersion))
    }

    /**
     * Items in [category] that are still [ReviewStatus.Pending], newest scan
     * first. Kept, trashed, failed, and removed rows are absent.
     * [ScanCategory.Unflagged] is a valid argument; those rows were scored
     * and nothing was suggested.
     *
     * The flow emits the current list, then emits again when `scan_results`
     * or `media_item` changes. Nothing is queried until a collector starts.
     */
    fun observePending(category: ScanCategory): Flow<List<ScanResult>> {
        return results.observeByCategoryAndStatus(
            category = category,
            status = ReviewStatus.Pending,
            failedOutcome = AnalysisOutcome.Failed,
        ).map { rows -> rows.map(ScanResultEntity::toResult) }
    }

    /**
     * Pending counts for every category, including zeros. Kept, trashed,
     * failed, and removed rows are not counted. A removed row is counted
     * again after [upsertBatch] marks that media item present. Emits again
     * when `scan_results` or `media_item` changes.
     */
    fun observePendingCounts(): Flow<CategoryCounts> {
        return results.observeCounts(
            status = ReviewStatus.Pending,
            failedOutcome = AnalysisOutcome.Failed,
        ).map { rows ->
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
     * Deletes these review rows after the system trash request succeeds.
     * This drops the score only. The photo bytes were never stored here.
     * Works for any status, so only pass the keys the dialog just trashed.
     */
    suspend fun remove(keys: Collection<MediaKey>) {
        if (keys.isEmpty()) return
        database.withTransaction {
            keys.groupBy { key -> key.type }.forEach { (type, grouped) ->
                grouped.map { key -> key.id }.distinct().chunked(ID_CHUNK).forEach { ids ->
                    results.deleteByTypeAndIds(type, ids)
                }
            }
        }
    }

    /**
     * Every kept item becomes pending again. Trashed rows are not changed.
     * The next scan may replace a row that this call returned to pending.
     */
    suspend fun resetReviewed() {
        results.resetStatus(kept = ReviewStatus.Kept, pending = ReviewStatus.Pending)
    }

    /**
     * Identities a later detector pass must not classify again.
     * Only [ReviewStatus.Kept]. Pending rows can be scored again so a newer
     * model can move them. Trashed rows are omitted: MediaStore does not
     * return trashed files unless a query asks for them, and this app does not.
     */
    suspend fun keptKeys(): Set<MediaKey> = keptKeySet()

    override suspend fun upsertBatch(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val latestByKey = LinkedHashMap<MediaKey, MediaItem>(items.size)
        for (item in items) {
            latestByKey[MediaKey(item.type, item.id)] = item
        }
        val rows = latestByKey.values.map { item -> item.toEntity() }
        database.withTransaction {
            rows.chunked(UPSERT_CHUNK).forEach { chunk -> scans.upsertMedia(chunk) }
        }
    }

    override suspend fun markRemoved(keys: List<MediaKey>) {
        if (keys.isEmpty()) return
        database.withTransaction {
            keys.groupBy { key -> key.type }.forEach { (type, grouped) ->
                grouped.map { key -> key.id }.distinct().chunked(ID_CHUNK).forEach { ids ->
                    scans.markRemoved(type, ids)
                }
            }
        }
    }

    override suspend fun presentKeys(type: MediaType): List<MediaKey> = scans.presentKeys(type)

    override suspend fun readCheckpoint(): ScanCheckpoint? = scans.readCheckpoint()?.toModel()

    override suspend fun saveCheckpoint(checkpoint: ScanCheckpoint) {
        scans.saveCheckpoint(checkpoint.toEntity())
    }

    override suspend fun clearCheckpoint() {
        scans.clearCheckpoint()
    }

    override suspend fun readRun(): ScanRun = scans.readRunRows().toRun()

    override suspend fun saveRun(run: ScanRun) {
        val snapshot = run.copy(byType = run.byType.toList())
        database.withTransaction {
            scans.saveRun(
                ScanRunEntity(
                    singleton = SINGLETON,
                    phase = snapshot.phase,
                    mediaStoreVersion = snapshot.mediaStoreVersion,
                    completedVolumeGeneration = snapshot.completedVolumeGeneration,
                    detail = snapshot.detail,
                ),
            )
            scans.deleteProgress()
            if (snapshot.byType.isNotEmpty()) {
                scans.insertProgress(
                    snapshot.byType.mapIndexed { index, progress ->
                        TypeProgressEntity(
                            type = progress.type,
                            scanned = progress.scanned,
                            total = progress.total,
                            position = index,
                        )
                    },
                )
            }
        }
    }

    override fun observeProgress(): Flow<ScanProgress> {
        return scans.observeRunRows()
            .map { rows -> ScanProgress.from(rows.toRun()) }
            .distinctUntilChanged()
    }

    private suspend fun writeUnlessKept(rows: List<ScanResultEntity>) {
        if (rows.isEmpty()) return
        database.withTransaction {
            val kept = keptKeySet()
            val writable = ArrayList<ScanResultEntity>(rows.size)
            for (row in rows) {
                val key = MediaKey(type = row.mediaType, id = row.mediaStoreId)
                if (key !in kept) writable += row
            }
            writable.chunked(UPSERT_CHUNK).forEach { chunk -> results.upsertAll(chunk) }
        }
    }

    private suspend fun keptKeySet(): Set<MediaKey> {
        return results.keysWithStatus(ReviewStatus.Kept)
            .map { row -> MediaKey(type = row.mediaType, id = row.mediaStoreId) }
            .toSet()
    }

    private suspend fun updateStatus(keys: Collection<MediaKey>, status: ReviewStatus) {
        if (keys.isEmpty()) return
        database.withTransaction {
            keys.groupBy { key -> key.type }.forEach { (type, grouped) ->
                grouped.map { key -> key.id }.distinct().chunked(ID_CHUNK).forEach { ids ->
                    results.updateStatus(type, ids, status)
                }
            }
        }
    }

    private companion object {
        // 9 columns per review row. 100 stays under the old 999-placeholder limit.
        const val UPSERT_CHUNK = 100

        // ids plus the type and status placeholders.
        const val ID_CHUNK = 500
    }
}

internal fun List<RunProgressRow>.toRun(): ScanRun {
    val first = firstOrNull() ?: return ScanRun.IDLE
    val progress = filter { row -> row.progressType != null }
        .sortedBy { row -> row.progressPosition ?: 0 }
        .map { row ->
            TypeProgress(
                type = checkNotNull(row.progressType),
                scanned = checkNotNull(row.progressScanned),
                total = checkNotNull(row.progressTotal),
            )
        }
    return ScanRun(
        phase = first.phase,
        byType = progress,
        mediaStoreVersion = first.mediaStoreVersion,
        completedVolumeGeneration = first.completedVolumeGeneration,
        detail = first.detail,
    )
}
