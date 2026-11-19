package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanCheckpoint
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScannerTest {
    @Test
    fun fullScanPagesEveryGrantedTypeAndClearsTheCheckpoint() = runTest {
        val library = MemoryLibrary(
            listOf(
                row(MediaType.Image, id = 1, modified = 30, generation = 2),
                row(MediaType.Image, id = 2, modified = 20, generation = 2),
                row(MediaType.Image, id = 3, modified = 10, generation = 1),
                row(MediaType.Video, id = 9, modified = 15, generation = 2),
                row(MediaType.Audio, id = 4, modified = 12, generation = 2),
            ),
        )
        val seen = mutableListOf<Long>()
        val store = InMemoryScanStore()
        scanner(
            library = library,
            store = store,
            access = ReadableMedia(images = true, videos = true, audio = false),
            stamp = stamp(generation = 2),
            observer = { batch -> seen += batch.map { item -> item.id } },
            batchSize = 2,
        ).scan()

        assertEquals(listOf(1L, 2L, 3L, 9L), seen)
        assertEquals(setOf(1L, 2L, 3L), store.presentKeys(MediaType.Image).map { it.id }.toSet())
        assertEquals(listOf(9L), store.presentKeys(MediaType.Video).map { it.id })
        assertTrue(store.presentKeys(MediaType.Audio).isEmpty())
        assertTrue(library.scanRequests.none { request -> request.type == MediaType.Audio })
        assertEquals(listOf(MediaType.Image, MediaType.Image, MediaType.Video), library.scanRequests.map { it.type })
        assertNull(library.scanRequests.first().after)
        assertEquals(ScanCursor(dateModified = 20, id = 2), library.scanRequests[1].after)
        val run = store.readRun()
        assertEquals(ScanPhase.Completed, run.phase)
        assertEquals("v1", run.mediaStoreVersion)
        assertEquals(2L, run.completedVolumeGeneration)
        assertNull(store.readCheckpoint())
        assertEquals(3, run.byType.first { it.type == MediaType.Image }.scanned)
        assertEquals(3, run.byType.first { it.type == MediaType.Image }.total)
    }

    @Test
    fun killAfterTheFirstBatchResumesFromTheCheckpoint() = runTest {
        val library = MemoryLibrary(
            (1L..5L).map { id -> row(MediaType.Image, id = id, modified = 100 - id, generation = 1) },
        )
        val store = InMemoryScanStore()
        val killing = KillOnPage(library, pageNumber = 2)
        try {
            scanner(killing, store, imagesOnly(), stamp(generation = 1), batchSize = 2).scan()
            error("the kill should have cancelled the scan")
        } catch (cancelled: CancellationException) {
            assertEquals("killed", cancelled.message)
        }
        val checkpoint = store.readCheckpoint()
        assertEquals(2, checkpoint!!.scanned)
        assertEquals(100 - 2, checkpoint.dateModified)
        assertEquals(ScanPhase.Cancelled, store.readRun().phase)

        library.scanRequests.clear()
        scanner(library, store, imagesOnly(), stamp(generation = 1), batchSize = 2).scan()
        assertEquals(ScanCursor(checkpoint.dateModified, checkpoint.id), library.scanRequests.first().after)
        assertEquals((1L..5L).toSet(), store.presentKeys(MediaType.Image).map { it.id }.toSet())
        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertNull(store.readCheckpoint())
        assertTrue(library.scanRequests.none { request -> request.after == null })
    }

    @Test
    fun aRevokedGrantStopsThePassAndKeepsTheCheckpoint() = runTest {
        val library = MemoryLibrary(
            (1L..5L).map { id -> row(MediaType.Image, id = id, modified = 50 - id, generation = 1) },
        )
        val store = InMemoryScanStore()
        var batches = 0
        scanner(
            library = library,
            store = store,
            access = imagesOnly(),
            stamp = stamp(generation = 1),
            observer = { _ -> batches += 1 },
            batchSize = 2,
            readable = {
                if (batches == 0) imagesOnly() else ReadableMedia(false, false, false)
            },
        ).scan()

        assertEquals(1, batches)
        assertEquals(ScanPhase.AccessRevoked, store.readRun().phase)
        assertEquals(2, store.readCheckpoint()!!.scanned)
        assertTrue(library.scanRequests.none { request -> request.type == MediaType.Video })
        assertEquals(2, store.presentKeys(MediaType.Image).size)
    }

    @Test
    fun securityExceptionFromMediaStoreIsAccessRevokedAndDoesNotThrow() = runTest {
        val store = InMemoryScanStore()
        scanner(
            library = ThrowingLibrary(SecurityException("permission")),
            store = store,
            access = imagesOnly(),
            stamp = stamp(generation = 1),
        ).scan()
        assertEquals(ScanPhase.AccessRevoked, store.readRun().phase)
        assertNull(store.readCheckpoint())
    }

    @Test
    fun noReadableCollectionDoesNotOpenMediaStore() = runTest {
        val library = MemoryLibrary(listOf(row(MediaType.Image, 1, 10, 1)))
        val store = InMemoryScanStore()
        scanner(library, store, ReadableMedia(false, false, false), stamp(generation = 1)).scan()
        assertTrue(library.scanRequests.isEmpty())
        assertTrue(library.idRequests.isEmpty())
        assertEquals(ScanPhase.AccessRevoked, store.readRun().phase)
    }

    @Test
    fun secondPassWithTheSameGenerationDoesNotPage() = runTest {
        val library = MemoryLibrary(listOf(row(MediaType.Image, 1, 10, generation = 4)))
        val store = InMemoryScanStore()
        val stamp = stamp(generation = 4)
        scanner(library, store, imagesOnly(), stamp).scan()
        library.scanRequests.clear()
        library.idRequests.clear()
        library.countCalls = 0
        scanner(library, store, imagesOnly(), stamp).scan()
        assertTrue(library.scanRequests.isEmpty())
        assertTrue(library.idRequests.isEmpty())
        assertEquals(1, library.countCalls)
        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertEquals(1, store.readRun().byType.single().scanned)
    }

    @Test
    fun aNewerGenerationReadsOnlyChangedRowsAndDropsMissingIds() = runTest {
        val library = MemoryLibrary(
            mutableListOf(
                row(MediaType.Image, id = 1, modified = 20, generation = 3),
                row(MediaType.Image, id = 2, modified = 10, generation = 3),
            ),
        )
        val store = InMemoryScanStore()
        scanner(library, store, imagesOnly(), stamp(generation = 3)).scan()
        library.items.removeAll { row -> row.item.id == 2L }
        library.items += row(MediaType.Image, id = 8, modified = 40, generation = 6)
        library.scanRequests.clear()

        scanner(library, store, imagesOnly(), stamp(generation = 6)).scan()

        assertTrue(library.scanRequests.all { request ->
            request.generationModifiedAfter == 3L && request.generationModifiedAtMost == 6L
        })
        assertEquals(setOf(1L, 8L), store.presentKeys(MediaType.Image).map { it.id }.toSet())
        assertNull(store.storedItem(MediaKey(MediaType.Image, 2)))
        assertEquals(6L, store.readRun().completedVolumeGeneration)
    }

    @Test
    fun aVersionChangeThrowsAwayTheCheckpointAndWalksEverything() = runTest {
        val library = MemoryLibrary(
            listOf(
                row(MediaType.Image, id = 1, modified = 20, generation = 1),
                row(MediaType.Image, id = 2, modified = 10, generation = 1),
            ),
        )
        val store = InMemoryScanStore()
        store.saveCheckpoint(
            ScanCheckpoint(
                type = MediaType.Image,
                dateModified = 20,
                id = 1,
                scanned = 1,
                generationModifiedAfter = null,
                generationModifiedAtMost = 1,
                mediaStoreVersion = "old",
            ),
        )
        store.saveRun(
            ScanRun(
                phase = ScanPhase.Running,
                byType = emptyList(),
                mediaStoreVersion = "old",
                completedVolumeGeneration = 1,
                detail = null,
            ),
        )
        scanner(library, store, imagesOnly(), stamp(version = "new", generation = 9), batchSize = 10).scan()
        assertNull(library.scanRequests.single().after)
        assertNull(library.scanRequests.single().generationModifiedAfter)
        assertEquals(setOf(1L, 2L), store.presentKeys(MediaType.Image).map { it.id }.toSet())
        assertEquals("new", store.readRun().mediaStoreVersion)
    }

    @Test
    fun aFailingObserverDoesNotAdvanceTheCheckpoint() = runTest {
        val library = MemoryLibrary(listOf(row(MediaType.Image, 1, 10, 1)))
        val store = InMemoryScanStore()
        val failed = runCatching {
            scanner(
                library = library,
                store = store,
                access = imagesOnly(),
                stamp = stamp(generation = 1),
                observer = { error("detector failed") },
            ).scan()
        }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertEquals(ScanPhase.Failed, store.readRun().phase)
        assertNull(store.readCheckpoint())
        assertTrue(store.presentKeys(MediaType.Image).isEmpty())
    }

    @Test
    fun audioStaysOutUntilTheOptInMakesItReadable() = runTest {
        val library = MemoryLibrary(
            listOf(
                row(MediaType.Image, 1, 10, 1),
                row(MediaType.Audio, 5, 9, 1),
            ),
        )
        val store = InMemoryScanStore()
        scanner(library, store, ReadableMedia(images = true, videos = false, audio = false), stamp(1)).scan()
        assertTrue(library.scanRequests.none { it.type == MediaType.Audio })
        assertTrue(store.presentKeys(MediaType.Audio).isEmpty())
    }

    private fun imagesOnly() = ReadableMedia(images = true, videos = false, audio = false)

    private fun stamp(generation: Long, version: String = "v1") = VolumeStamp(
        version = version,
        generation = generation,
        generationUsable = true,
    )

    private fun scanner(
        library: MediaRepository,
        store: InMemoryScanStore,
        access: ReadableMedia,
        stamp: VolumeStamp,
        observer: ScanBatchObserver = ScanBatchObserver.NONE,
        batchSize: Int = 10,
        readable: suspend () -> ReadableMedia = { access },
    ) = LibraryScanner(
        repository = library,
        store = store,
        readable = readable,
        stamps = VolumeStamps { stamp },
        observer = observer,
        batchSize = batchSize,
    )
}

private fun row(type: MediaType, id: Long, modified: Long, generation: Long) = ScannedRow(
    item = MediaItem(
        id = id,
        uri = "content://media/external/${type.name.lowercase()}/media/$id",
        type = type,
        dateAdded = modified,
        size = 100,
        path = "DCIM/$id",
        mimeType = null,
    ),
    dateModified = modified,
    generationModified = generation,
)

private class MemoryLibrary(
    rows: List<ScannedRow>,
) : MediaRepository {
    val items: MutableList<ScannedRow> = rows.toMutableList()
    val scanRequests = mutableListOf<ScanPageRequest>()
    val idRequests = mutableListOf<IdPageRequest>()
    var countCalls: Int = 0

    override suspend fun count(readable: ReadableMedia): MediaCounts {
        countCalls += 1
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
            .filter { row -> row.item.type == request.type }
            .filter { row ->
                matchesGeneration(row.generationModified, request.generationModifiedAfter, request.generationModifiedAtMost)
            }
            .filter { row ->
                val after = request.after ?: return@filter true
                row.dateModified < after.dateModified ||
                    (row.dateModified == after.dateModified && row.item.id < after.id)
            }
            .sortedWith(compareByDescending<ScannedRow> { row -> row.dateModified }.thenByDescending { row -> row.item.id })
        return scanPageOf(matched.take(request.limit + 1), request.limit)
    }

    override suspend fun countScan(request: ScanCountRequest): Int {
        return items.count { row ->
            row.item.type == request.type &&
                matchesGeneration(row.generationModified, request.generationModifiedAfter, request.generationModifiedAtMost)
        }
    }

    override suspend fun pageIds(request: IdPageRequest): IdPage {
        idRequests += request
        val ids = items
            .filter { row -> row.item.type == request.type }
            .map { row -> row.item.id }
            .filter { id -> request.afterId == null || id > request.afterId }
            .sorted()
            .distinct()
        return idPageOf(ids.take(request.limit + 1), request.limit)
    }
}

private class KillOnPage(
    private val delegate: MemoryLibrary,
    private val pageNumber: Int,
) : MediaRepository by delegate {
    private var scans = 0

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        scans += 1
        if (scans == pageNumber) throw CancellationException("killed")
        return delegate.scanPage(request)
    }
}

private class ThrowingLibrary(private val error: Throwable) : MediaRepository {
    override suspend fun count(readable: ReadableMedia): MediaCounts = error("unused")
    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")
    override suspend fun scanPage(request: ScanPageRequest): ScanPage = throw error
    override suspend fun countScan(request: ScanCountRequest): Int = throw error
    override suspend fun pageIds(request: IdPageRequest): IdPage = throw error
}
