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
 * thing that keeps the job alive across the app leaving the screen. Pulling
 * the notification shade pauses the activity and, on some phones, stops it.
 * That is not a user cancel, and nothing in the activity cancels this work
 * from `onPause` or `onStop`. The checkpoint, not WorkManager's output data,
 * is what a later start resumes from: the next execution continues at the
 * last saved batch and keeps that batch's scanned count. It does not zero
 * the progress and walk the library from the beginning.
 *
 * [ExistingWorkPolicy.KEEP] drops a second Start while this request is
 * queued or running, so repeated taps cannot load a second detector set.
 * After the request is finished (cancelled, failed, or succeeded), the next
 * Start replaces it. One tap is enough.
 *
 * This is not expedited work and it does not call `setForeground`. A gallery
 * scan can run for a long time. An expedited job that overruns its quota, and
 * a foreground service, both pull this app into Play's foreground-service
 * review. The scan is ordinary work: it runs while the phone allows, and a
 * stop is a checkpoint rather than a notification. On Android 12 and higher
 * WorkManager would otherwise implement expedited work with a foreground
 * service of type dataSync. We do not ask it to.
 *
 * [LibraryScan.run] closes the detectors when the pass ends, fails, or is
 * cancelled.
 */
class LibraryScanWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val scan: LibraryScan,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        ScanRuntime.open(context),
    )

    constructor(
        context: Context,
        params: WorkerParameters,
        scanner: LibraryScanner,
    ) : this(
        context,
        params,
        LibraryScan { LibraryScan.Pass(scanner) },
    )

    override suspend fun doWork(): Result {
        return try {
            scan.run()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The scanner has already recorded Failed, and it kept the checkpoint.
            // Access revoked is recorded and not rethrown, so it is a success
            // here and WorkManager does not retry a scan the user cannot authorize.
            Result.failure()
        }
    }
}

const val UNIQUE_LIBRARY_SCAN = "gallery-library-scan"

/**
 * Tag on every library-scan request. Cancel uses it as well as
 * [UNIQUE_LIBRARY_SCAN], so a scan that was not the unique chain (none today;
 * a second request would still be this tag) stops with the same button.
 */
const val LIBRARY_SCAN_TAG = "gallery-library-scan"

fun enqueueLibraryScan(workManager: WorkManager) {
    val request = OneTimeWorkRequestBuilder<LibraryScanWorker>()
        .addTag(LIBRARY_SCAN_TAG)
        .build()
    workManager.enqueueUniqueWork(
        UNIQUE_LIBRARY_SCAN,
        ExistingWorkPolicy.KEEP,
        request,
    )
}

fun cancelLibraryScan(workManager: WorkManager) {
    // The walk and the rescan are this one worker. Cancelling the unique
    // name stops both. The tag covers any other request marked as a scan.
    workManager.cancelUniqueWork(UNIQUE_LIBRARY_SCAN)
    workManager.cancelAllWorkByTag(LIBRARY_SCAN_TAG)
}
