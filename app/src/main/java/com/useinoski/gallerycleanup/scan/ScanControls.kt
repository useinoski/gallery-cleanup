package com.useinoski.gallerycleanup.scan

import android.content.Context
import androidx.work.WorkManager
import com.useinoski.gallerycleanup.model.ScanProgress
import kotlinx.coroutines.flow.Flow

/**
 * What the home screen needs from the scan: the latest progress, and the two
 * buttons. The ViewModel does not talk to WorkManager itself, so a test can
 * pass a fake and never start a worker.
 */
interface ScanControls {
    val progress: Flow<ScanProgress>
    fun start()
    fun cancel()
}

class WorkManagerScanControls(
    context: Context,
) : ScanControls {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    override val progress: Flow<ScanProgress> = ScanRuntime.store().observeProgress()

    override fun start() {
        enqueueLibraryScan(workManager)
    }

    override fun cancel() {
        cancelLibraryScan(workManager)
    }
}
