package com.useinoski.gallerycleanup.scan

import android.content.Context
import androidx.work.WorkManager
import com.useinoski.gallerycleanup.model.ScanProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What the home screen needs from the scan: the latest progress, whether
 * WorkManager still has the job, and the two buttons. The ViewModel does not
 * talk to WorkManager itself, so a test can pass a fake and never start a
 * worker.
 *
 * [workActive] is true while the unique scan is queued, running, or blocked.
 * The stored phase can say cancelled or finished for a moment while that is
 * still true. The button follows [workActive] so Start is not offered into
 * a [ExistingWorkPolicy.KEEP][androidx.work.ExistingWorkPolicy.KEEP] no-op.
 */
interface ScanControls {
    val progress: Flow<ScanProgress>
    val workActive: Flow<Boolean>
        get() = flowOf(false)

    fun start()
    fun cancel()

    /**
     * When [workActive] is false and the stored run is still `Running`, mark
     * it cancelled. Opening the screen must not enqueue a scan. A `Running`
     * row left behind by a killed process is not a scan in progress.
     */
    suspend fun settleIfIdle(workActive: Boolean) {}
}

class WorkManagerScanControls(
    context: Context,
) : ScanControls {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    override val progress: Flow<ScanProgress> = ScanRuntime.store(appContext).observeProgress()
    override val workActive: Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_LIBRARY_SCAN)
            .map { infos -> infos.any { info -> info.state.isScanActive() } }
            .distinctUntilChanged()

    override fun start() {
        enqueueLibraryScan(workManager)
    }

    override fun cancel() {
        cancelLibraryScan(workManager)
    }

    override suspend fun settleIfIdle(workActive: Boolean) {
        abandonRunIfNoWork(ScanRuntime.store(appContext), workActive)
    }
}
