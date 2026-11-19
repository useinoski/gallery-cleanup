package com.useinoski.gallerycleanup.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.useinoski.gallerycleanup.media.LibraryScanner
import kotlinx.coroutines.CancellationException

/**
 * One library scan, as WorkManager work.
 *
 * Unique work named [UNIQUE_LIBRARY_SCAN] with [ExistingWorkPolicy.KEEP] so a
 * second Start while a scan is queued or running does not start another one.
 * The scanner re-reads permissions as it goes and writes progress to the
 * [com.useinoski.gallerycleanup.model.ScanStore]. WorkManager is only the
 * thing that keeps the job alive across the app leaving the screen. The
 * checkpoint, not WorkManager's output data, is what a later start resumes
 * from.
 *
 * This is not expedited work and it does not call `setForeground`. A gallery
 * scan can run for a long time. An expedited job that overruns its quota, and
 * a foreground service, both pull this app into Play's foreground-service
 * review. The scan is ordinary work: it runs while the phone allows, and a
 * stop is a checkpoint rather than a notification. On Android 12 and higher
 * WorkManager would otherwise implement expedited work with a foreground
 * service of type dataSync. We do not ask it to.
 */
class LibraryScanWorker(
    context: Context,
    params: WorkerParameters,
    private val scanner: LibraryScanner,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        ScanRuntime.scanner(context),
    )

    override suspend fun doWork(): Result {
        return try {
            scanner.scan()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The scanner has already recorded Failed, and it kept the checkpoint.
            Result.failure()
        }
    }
}

const val UNIQUE_LIBRARY_SCAN = "gallery-library-scan"

fun enqueueLibraryScan(workManager: WorkManager) {
    val request = OneTimeWorkRequestBuilder<LibraryScanWorker>().build()
    workManager.enqueueUniqueWork(
        UNIQUE_LIBRARY_SCAN,
        ExistingWorkPolicy.KEEP,
        request,
    )
}

fun cancelLibraryScan(workManager: WorkManager) {
    workManager.cancelUniqueWork(UNIQUE_LIBRARY_SCAN)
}
