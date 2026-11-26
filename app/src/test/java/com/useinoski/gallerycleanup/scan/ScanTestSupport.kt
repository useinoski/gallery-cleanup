package com.useinoski.gallerycleanup.scan

import android.graphics.Bitmap
import com.useinoski.gallerycleanup.data.AnalysisFailure
import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.MODEL_VERSION_NONE
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.ScanResult
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.MediaDetector
import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.media.IdPage
import com.useinoski.gallerycleanup.media.IdPageRequest
import com.useinoski.gallerycleanup.media.LibraryScanner
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.media.MediaPage
import com.useinoski.gallerycleanup.media.MediaPageRequest
import com.useinoski.gallerycleanup.media.MediaRepository
import com.useinoski.gallerycleanup.media.ReadableMedia
import com.useinoski.gallerycleanup.media.ScanBatchObserver
import com.useinoski.gallerycleanup.media.ScanCountRequest
import com.useinoski.gallerycleanup.media.ScanPage
import com.useinoski.gallerycleanup.media.ScanPageRequest
import com.useinoski.gallerycleanup.media.ScannedRow
import com.useinoski.gallerycleanup.media.VolumeStamp
import com.useinoski.gallerycleanup.media.VolumeStamps
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType

internal val TEST_MODEL_VERSIONS: Set<String> = setOf(
    FlagPolicy.NONE,
    FlagPolicy.VIDEO_RULE,
    FlagPolicy.AUDIO_RULE,
    FlagPolicy.SCREENSHOT_PATH_RULE,
    "mlkit-face-count|face-v2",
    "marqo-nsfw-384|marqo-v1",
    "mlkit-image-labels-v1|labels-v1",
)

internal fun testItem(
    id: Long,
    type: MediaType = MediaType.Image,
): MediaItem = MediaItem(
    id = id,
    uri = "content://media/external/${type.name.lowercase()}/media/$id",
    type = type,
    dateAdded = 1_700_000_000,
    size = 100,
    path = if (type == MediaType.Image) "DCIM/Camera/IMG_$id.jpg" else null,
    mimeType = if (type == MediaType.Image) "image/jpeg" else null,
)

internal fun peopleSignals(count: Float = 2f) = ImageSignals(
    faces = DetectionResult(
        scores = mapOf(FaceScanVersion.FACE_COUNT to count),
        detectorId = "mlkit-face-count",
        modelVersion = "face-v2",
    ),
)

internal class FakeAnalysisRecords : AnalysisRecords {
    data class Stored(
        val uri: String,
        val version: String,
        val outcome: AnalysisOutcome,
        val category: ScanCategory,
        val kept: Boolean = false,
    )

    val rows = LinkedHashMap<MediaKey, Stored>()
    val removed = mutableSetOf<MediaKey>()
    var rescanCalls: Int = 0
    val upserted = mutableListOf<ScanResult>()
    val failureBatches = mutableListOf<List<AnalysisFailure>>()

    fun plant(
        item: MediaItem,
        version: String,
        outcome: AnalysisOutcome = AnalysisOutcome.Scored,
        category: ScanCategory = ScanCategory.Unflagged,
        kept: Boolean = false,
    ) {
        rows[MediaKey(item.type, item.id)] = Stored(
            uri = item.uri,
            version = version,
            outcome = outcome,
            category = category,
            kept = kept,
        )
    }

    override suspend fun keptKeys(): Set<MediaKey> {
        return rows.filterValues { row -> row.kept }.keys
    }

    override suspend fun upsert(results: List<ScanResult>) {
        upserted += results
        for (result in results) {
            if (rows[result.key]?.kept == true) continue
            rows[result.key] = Stored(
                uri = result.contentUri,
                version = result.modelVersion,
                outcome = AnalysisOutcome.Scored,
                category = result.category,
            )
        }
    }

    override suspend fun recordFailures(failures: List<AnalysisFailure>) {
        if (failures.isEmpty()) return
        failureBatches += failures
        for (failure in failures) {
            if (rows[failure.key]?.kept == true) continue
            rows[failure.key] = Stored(
                uri = failure.contentUri,
                version = MODEL_VERSION_NONE,
                outcome = AnalysisOutcome.Failed,
                category = ScanCategory.Unflagged,
            )
        }
    }

    override suspend fun keysNeedingRescan(currentModelVersions: Collection<String>): List<MediaKey> {
        rescanCalls += 1
        return rows.mapNotNull { (key, row) ->
            val stale = row.outcome == AnalysisOutcome.Failed || row.version !in currentModelVersions
            if (!row.kept && key !in removed && stale) key else null
        }
    }
}

internal class VersionedDetector(
    override val id: String,
    override val modelVersion: String,
) : MediaDetector {
    override val inputSizePx: Int = 0
    override suspend fun classify(input: Bitmap): DetectionResult = error("unused")
}

internal class ScanMemoryLibrary(
    rows: List<ScannedRow>,
) : MediaRepository {
    val items: MutableList<ScannedRow> = rows.toMutableList()
    val scanRequests = mutableListOf<ScanPageRequest>()

    override suspend fun count(readable: ReadableMedia): MediaCounts {
        fun n(type: MediaType) = items.count { row -> row.item.type == type }
        return MediaCounts(
            images = if (readable.images) n(MediaType.Image) else null,
            videos = if (readable.videos) n(MediaType.Video) else null,
            music = if (readable.audio) n(MediaType.Audio) else null,
            otherAudio = if (readable.audio) 0 else null,
        )
    }

    override suspend fun page(request: MediaPageRequest): MediaPage = error("offset pages are not used by the scan")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        scanRequests += request
        val matched = items
            .asSequence()
            .filter { row -> row.item.type == request.type }
            .filter { row ->
                matchesGeneration(
                    row.generationModified,
                    request.generationModifiedAfter,
                    request.generationModifiedAtMost,
                )
            }
            .filter { row ->
                val after = request.after
                after == null || row.dateModified < after.dateModified ||
                    (row.dateModified == after.dateModified && row.item.id < after.id)
            }
            .sortedWith(
                compareByDescending<ScannedRow> { row -> row.dateModified }
                    .thenByDescending { row -> row.item.id },
            )
            .toList()
        val window = matched.take(request.limit + 1)
        return ScanPage(rows = window.take(request.limit), hasMore = window.size > request.limit)
    }

    override suspend fun countScan(request: ScanCountRequest): Int {
        return items.count { row ->
            row.item.type == request.type &&
                matchesGeneration(
                    row.generationModified,
                    request.generationModifiedAfter,
                    request.generationModifiedAtMost,
                )
        }
    }

    override suspend fun pageIds(request: IdPageRequest): IdPage {
        val ids = items
            .asSequence()
            .filter { row -> row.item.type == request.type }
            .map { row -> row.item.id }
            .filter { id ->
                val afterId = request.afterId
                afterId == null || id > afterId
            }
            .sorted()
            .distinct()
            .toList()
        val window = ids.take(request.limit + 1)
        return IdPage(ids = window.take(request.limit), hasMore = window.size > request.limit)
    }
}

internal class ThrowingScanLibrary(
    private val error: Throwable,
) : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = MediaCounts(1, null, null, null)
    override suspend fun page(request: MediaPageRequest): MediaPage = throw error
    override suspend fun scanPage(request: ScanPageRequest): ScanPage = throw error
    override suspend fun countScan(request: ScanCountRequest): Int = 1
    override suspend fun pageIds(request: IdPageRequest): IdPage = throw error
}

internal fun scannedRow(
    id: Long,
    modified: Long = 10,
    generation: Long = 1,
    type: MediaType = MediaType.Image,
) = ScannedRow(
    item = testItem(id, type),
    dateModified = modified,
    generationModified = generation,
)

internal fun testScanner(
    library: MediaRepository,
    store: InMemoryScanStore,
    observer: ScanBatchObserver = ScanBatchObserver.NONE,
    readable: suspend () -> ReadableMedia = { ReadableMedia(images = true, videos = false, audio = false) },
    batchSize: Int = 10,
    generation: Long = 4,
    version: String = "v1",
) = LibraryScanner(
    repository = library,
    store = store,
    readable = readable,
    stamps = VolumeStamps { VolumeStamp(version = version, generation = generation, generationUsable = true) },
    observer = observer,
    batchSize = batchSize,
)

private fun matchesGeneration(
    generationModified: Long,
    generationModifiedAfter: Long?,
    generationModifiedAtMost: Long,
): Boolean {
    if (generationModifiedAtMost != Long.MAX_VALUE && generationModified > generationModifiedAtMost) return false
    if (generationModifiedAfter != null && generationModified <= generationModifiedAfter) return false
    return true
}

internal class FakeDetector : AutoCloseable {
    var closeCount: Int = 0
    override fun close() {
        closeCount += 1
    }
}
