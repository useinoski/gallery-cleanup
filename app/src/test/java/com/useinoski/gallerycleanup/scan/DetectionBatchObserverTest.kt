package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.ReviewStatus
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.data.roomScanResultStore
import com.useinoski.gallerycleanup.detection.DetectionResult
import com.useinoski.gallerycleanup.detection.DetectionScoring
import com.useinoski.gallerycleanup.detection.face.FaceScanVersion
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The observer writes the Room review rows. Redelivery is the crash case:
 * the scanner saves the checkpoint only after [DetectionBatchObserver.onBatch]
 * returns, so the same page can be classified twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionBatchObserverTest {
    private val clock = AtomicInteger(1_000)
    private val store by lazy { roomScanResultStore(RuntimeEnvironment.getApplication()) }

    @Test
    fun resultsLandInTheStoreWithCategoryConfidenceAndModelVersion() = runBlocking {
        val image = item(nextId(), MediaType.Image)
        val video = item(nextId(), MediaType.Video)
        val observer = observer { peopleSignals(2f) }
        observer.onBatch(listOf(image, video))

        val person = pending(ScanCategory.People).single { it.mediaStoreId == image.id }
        assertEquals(MediaType.Image, person.mediaType)
        assertEquals(image.uri, person.contentUri)
        assertEquals(2f, person.confidence, 0.0001f)
        assertEquals(DetectionScoring.reviewIdentity(peopleSignals(2f).faces!!), person.modelVersion)
        assertEquals(1_000L, person.scannedAtEpochMillis)
        assertEquals(ReviewStatus.Pending, person.reviewStatus)

        val clip = pending(ScanCategory.Video).single { it.mediaStoreId == video.id }
        assertEquals(1f, clip.confidence, 0.0001f)
        assertEquals(FlagPolicy.VIDEO_RULE, clip.modelVersion)
    }

    @Test
    fun redeliveryReplacesTheSameRow() = runBlocking {
        val image = item(nextId(), MediaType.Image)
        val video = item(nextId(), MediaType.Video)
        val audio = item(nextId(), MediaType.Audio)
        var imageCalls = 0
        val observer = observer {
            imageCalls += 1
            peopleSignals(1f)
        }
        observer.onBatch(listOf(image, video, audio))
        clock.set(5_000)
        observer.onBatch(listOf(audio, image, video, image))

        assertEquals(2, imageCalls)
        assertEquals(1, pending(ScanCategory.People).count { it.mediaStoreId == image.id })
        assertEquals(1, pending(ScanCategory.Video).count { it.mediaStoreId == video.id })
        assertEquals(1, pending(ScanCategory.Audio).count { it.mediaStoreId == audio.id })
        val person = pending(ScanCategory.People).single { it.mediaStoreId == image.id }
        assertEquals(1f, person.confidence, 0.0001f)
        assertEquals(5_000L, person.scannedAtEpochMillis)
        val song = pending(ScanCategory.Audio).single { it.mediaStoreId == audio.id }
        assertEquals(FlagPolicy.AUDIO_RULE, song.modelVersion)
    }

    @Test
    fun aKeptRowIsNotClassifiedAgain() = runBlocking {
        val image = item(nextId(), MediaType.Image)
        var calls = 0
        val first = observer {
            calls += 1
            peopleSignals(1f)
        }
        first.onBatch(listOf(image))
        store.markKept(listOf(MediaKey(MediaType.Image, image.id)))

        val second = observer {
            calls += 1
            nuditySignals(0.99f)
        }
        second.onBatch(listOf(image))

        assertEquals(1, calls)
        assertTrue(store.keptKeys().contains(MediaKey(MediaType.Image, image.id)))
        assertTrue(pending(ScanCategory.Sensitive).none { it.mediaStoreId == image.id })
        assertTrue(pending(ScanCategory.People).none { it.mediaStoreId == image.id })
    }

    @Test
    fun oneDetectorFailureDoesNotDropTheRestOfTheBatch() = runBlocking {
        val ok = item(nextId(), MediaType.Image)
        val broken = item(nextId(), MediaType.Image)
        val alsoOk = item(nextId(), MediaType.Video)
        val observer = observer { item ->
            if (item.id == broken.id) error("detector failed")
            peopleSignals(1f)
        }
        observer.onBatch(listOf(ok, broken, alsoOk))

        assertEquals(1, pending(ScanCategory.People).count { it.mediaStoreId == ok.id })
        assertEquals(1, pending(ScanCategory.Video).count { it.mediaStoreId == alsoOk.id })
        assertTrue(pending(ScanCategory.Unflagged).none { it.mediaStoreId == broken.id })
        val person = pending(ScanCategory.People).single { it.mediaStoreId == ok.id }
        val again = store.keysNeedingRescan(
            setOf(person.modelVersion, FlagPolicy.VIDEO_RULE, FlagPolicy.NONE),
        )
        assertTrue(again.contains(MediaKey(MediaType.Image, broken.id)))
        assertTrue(again.none { key -> key.id == ok.id || key.id == alsoOk.id })

        val repaired = observer { peopleSignals(1f) }
        repaired.onBatch(listOf(broken))
        assertTrue(pending(ScanCategory.Unflagged).none { it.mediaStoreId == broken.id })
        assertEquals(1, pending(ScanCategory.People).count { it.mediaStoreId == broken.id })
        assertTrue(
            store.keysNeedingRescan(
                setOf(person.modelVersion, FlagPolicy.VIDEO_RULE, FlagPolicy.NONE),
            ).none { key -> key.id == broken.id },
        )
    }

    @Test
    fun duplicateKeysInOneBatchAreClassifiedOnce() = runBlocking {
        val image = item(nextId(), MediaType.Image)
        var calls = 0
        val observer = observer {
            calls += 1
            screenshotSignals()
        }
        observer.onBatch(listOf(image, image))
        assertEquals(1, calls)
        val row = pending(ScanCategory.Screenshot).single { it.mediaStoreId == image.id }
        assertEquals(FlagPolicy.SCREENSHOT_PATH_RULE, row.modelVersion)
    }

    @Test
    fun videosAndAudioDoNotRunTheImageClassifier() = runBlocking {
        var calls = 0
        val observer = observer {
            calls += 1
            peopleSignals(1f)
        }
        observer.onBatch(
            listOf(
                item(nextId(), MediaType.Video),
                item(nextId(), MediaType.Audio),
            ),
        )
        assertEquals(0, calls)
    }

    @Test
    fun cancelDoesNotStoreTheCancelledItemAsUnflagged() = runBlocking {
        val image = item(nextId(), MediaType.Image)
        val started = CompletableDeferred<Unit>()
        val observer = observer {
            started.complete(Unit)
            suspendCancellableCoroutine { }
        }
        val job = launch { observer.onBatch(listOf(image)) }
        started.await()
        job.cancel()
        job.join()
        assertTrue(pending(ScanCategory.Unflagged).none { it.mediaStoreId == image.id })
        assertTrue(pending(ScanCategory.People).none { it.mediaStoreId == image.id })
        assertTrue(
            store.keysNeedingRescan(setOf(FlagPolicy.NONE)).none { key -> key.id == image.id },
        )
    }

    @Test
    fun imageWorkStaysInsideTheParallelismBound() = runBlocking {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val observer = observer(parallelism = 2) {
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { previous -> maxOf(previous, now) }
            delay(30)
            inFlight.decrementAndGet()
            peopleSignals(1f)
        }
        val items = List(6) { item(nextId(), MediaType.Image) }
        observer.onBatch(items)
        assertTrue("max in flight was ${maxInFlight.get()}", maxInFlight.get() <= 2)
        assertTrue("max in flight was ${maxInFlight.get()}", maxInFlight.get() >= 2)
        assertEquals(6, pending(ScanCategory.People).count { row -> items.any { it.id == row.mediaStoreId } })
    }

    @Test
    fun recordsObserverOverheadPerImage() = runBlocking {
        val samplesMs = ArrayList<Double>(4)
        val items = List(4) { item(nextId(), MediaType.Image) }
        val observer = observer {
            val started = System.nanoTime()
            val bitmap = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFF208060.toInt())
            bitmap.recycle()
            samplesMs += (System.nanoTime() - started) / 1_000_000.0
            peopleSignals(1f)
        }
        observer.onBatch(items)
        val perImageMs = samplesMs.average()
        // Stub only: allocate a 640px bitmap and drop it. LiteRT and ML Kit are not in this number.
        println("observer stub per image (640x480 allocate + recycle): $perImageMs ms")
        assertEquals(4, samplesMs.size)
        assertTrue("per-image stub $perImageMs ms", perImageMs < 200)
        assertEquals(items.size, pending(ScanCategory.People).count { row -> items.any { it.id == row.mediaStoreId } })
    }

    private fun observer(
        parallelism: Int = 1,
        classify: suspend (MediaItem) -> ImageSignals,
    ) = DetectionBatchObserver(
        results = RoomAnalysisRecords(store),
        classifyImage = classify,
        parallelism = parallelism,
        clock = { clock.get().toLong() },
    )

    private suspend fun pending(category: ScanCategory) = store.observePending(category).first()

    private var idSequence = System.nanoTime().and(0x7FFF_FFFF_FFFFL)

    private fun nextId(): Long {
        idSequence += 1
        return idSequence
    }

    private fun item(id: Long, type: MediaType) = MediaItem(
        id = id,
        uri = "content://media/external/${type.name.lowercase()}/media/$id",
        type = type,
        dateAdded = 1_700_000_000,
        size = 2_048,
        path = if (type == MediaType.Image) "DCIM/Camera/IMG_$id.jpg" else null,
        mimeType = if (type == MediaType.Image) "image/jpeg" else null,
    )

    private fun peopleSignals(count: Float) = ImageSignals(
        faces = DetectionResult(
            scores = mapOf(FaceScanVersion.FACE_COUNT to count),
            detectorId = "mlkit-face-count",
            modelVersion = "face-v1",
        ),
    )

    private fun nuditySignals(score: Float) = ImageSignals(
        nsfw = DetectionResult(
            scores = mapOf("NSFW" to score),
            detectorId = "marqo-nsfw-384",
            modelVersion = "marqo-v1",
        ),
    )

    private fun screenshotSignals() = ImageSignals(screenshotPath = true)
}
