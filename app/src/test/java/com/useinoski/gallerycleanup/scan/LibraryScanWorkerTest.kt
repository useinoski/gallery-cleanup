package com.useinoski.gallerycleanup.scan

import android.content.Context
import androidx.work.Configuration
import java.util.concurrent.Executor
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.ListenableWorker.Result
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
import com.useinoski.gallerycleanup.media.ScanCursor
import com.useinoski.gallerycleanup.media.ScanPage
import com.useinoski.gallerycleanup.media.ScanPageRequest
import com.useinoski.gallerycleanup.media.ScannedRow
import com.useinoski.gallerycleanup.media.VolumeStamp
import com.useinoski.gallerycleanup.media.VolumeStamps
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaItem
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScanWorkerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun workerRunsTheScannerAndSucceeds() = runTest {
        val store = InMemoryScanStore()
        val scanner = LibraryScanner(
            repository = OneImageLibrary(),
            store = store,
            readable = { ReadableMedia(images = true, videos = false, audio = false) },
            stamps = VolumeStamps { VolumeStamp(version = "v", generation = 1, generationUsable = true) },
            observer = ScanBatchObserver.NONE,
        )
        val worker = TestListenableWorkerBuilder<LibraryScanWorker>(context)
            .setWorkerFactory(InjectedScannerFactory(scanner))
            .build()

        assertEquals(Result.success(), worker.doWork())
        assertEquals(ScanPhase.Completed, store.readRun().phase)
        assertEquals(1, store.presentKeys(MediaType.Image).size)
    }

    @Test
    fun workerReportsFailureWhenTheScannerThrows() = runTest {
        val store = InMemoryScanStore()
        val scanner = LibraryScanner(
            repository = OneImageLibrary(),
            store = store,
            readable = { ReadableMedia(images = true, videos = false, audio = false) },
            stamps = VolumeStamps { VolumeStamp(version = "v", generation = 1, generationUsable = true) },
            observer = ScanBatchObserver { error("detector failed") },
        )
        val worker = TestListenableWorkerBuilder<LibraryScanWorker>(context)
            .setWorkerFactory(InjectedScannerFactory(scanner))
            .build()

        assertEquals(Result.failure(), worker.doWork())
        assertEquals(ScanPhase.Failed, store.readRun().phase)
    }

    @Test
    fun keepPolicyDropsASecondScanWhileTheFirstHasNotFinished() {
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(Executor { command -> command.run() })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val workManager = WorkManager.getInstance(context)
        val blocked = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        workManager.enqueueUniqueWork(UNIQUE_LIBRARY_SCAN, ExistingWorkPolicy.KEEP, blocked)
            .result.get(5, TimeUnit.SECONDS)
        val second = OneTimeWorkRequestBuilder<LibraryScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        workManager.enqueueUniqueWork(UNIQUE_LIBRARY_SCAN, ExistingWorkPolicy.KEEP, second)
            .result.get(5, TimeUnit.SECONDS)

        val infos = workManager.getWorkInfosForUniqueWork(UNIQUE_LIBRARY_SCAN).get(5, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        assertEquals(blocked.id, infos.single().id)
        assertFalse(infos.single().state.isFinished)
    }
}

/**
 * [TestListenableWorkerBuilder] asks the factory for the worker. Returning
 * the production constructor would open MediaStore; the test passes the
 * scanner it built.
 */
private class InjectedScannerFactory(
    private val scanner: LibraryScanner,
) : androidx.work.WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: androidx.work.WorkerParameters,
    ): androidx.work.ListenableWorker {
        return LibraryScanWorker(appContext, workerParameters, scanner)
    }
}

private class OneImageLibrary : MediaRepository {
    private val row = ScannedRow(
        item = MediaItem(
            id = 7,
            uri = "content://media/external/images/media/7",
            type = MediaType.Image,
            dateAdded = 10,
            size = 4,
            path = "DCIM/7.jpg",
            mimeType = "image/jpeg",
        ),
        dateModified = 10,
        generationModified = 1,
    )

    override suspend fun count(readable: ReadableMedia): MediaCounts {
        return MediaCounts(images = 1, videos = null, music = null, otherAudio = null)
    }

    override suspend fun page(request: MediaPageRequest): MediaPage = error("unused")

    override suspend fun scanPage(request: ScanPageRequest): ScanPage {
        val after: ScanCursor? = request.after
        val include = after == null
        return ScanPage(rows = if (include) listOf(row) else emptyList(), hasMore = false)
    }

    override suspend fun countScan(request: ScanCountRequest): Int = 1

    override suspend fun pageIds(request: IdPageRequest): IdPage {
        val ids = if (request.afterId == null) listOf(7L) else emptyList()
        return IdPage(ids = ids, hasMore = false)
    }
}
