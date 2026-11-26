package com.useinoski.gallerycleanup.scan

import androidx.work.WorkInfo
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The home screen is in a scan while WorkManager still has the unique work,
 * or while the stored run says [ScanPhase.Running].
 *
 * WorkManager is the source of truth for "is a worker alive". The stored
 * phase can lag it: a system stop writes [ScanPhase.Cancelled] for a moment,
 * and a process death can leave [ScanPhase.Running] after the worker is gone.
 */
internal fun scanAppearsRunning(phase: ScanPhase, workActive: Boolean): Boolean =
    workActive || phase == ScanPhase.Running

internal fun WorkInfo.State.isScanActive(): Boolean =
    this == WorkInfo.State.ENQUEUED ||
        this == WorkInfo.State.RUNNING ||
        this == WorkInfo.State.BLOCKED

/**
 * A [ScanPhase.Running] row whose worker is gone.
 *
 * Cancel used to call only `cancelUniqueWork`. That does nothing when no
 * request is queued, so a row left `Running` by a killed process stayed on
 * the Cancel button forever. The checkpoint is kept: the next Start
 * continues from the last saved batch instead of from zero.
 *
 * [workActive] true means a worker is queued or in progress. Leave that row
 * alone; the worker owns the phase.
 */
internal suspend fun abandonRunIfNoWork(store: ScanStore, workActive: Boolean) {
    if (workActive) return
    val run = store.readRun()
    if (run.phase != ScanPhase.Running) return
    store.saveRun(run.copy(phase = ScanPhase.Cancelled, detail = null))
}

/**
 * Set from the Cancel button before WorkManager is asked to stop the worker.
 *
 * [androidx.work.ListenableWorker.getStopReason] is often still
 * [WorkInfo.STOP_REASON_NOT_STOPPED] while the worker's coroutine is being
 * cancelled. The button records the tap here first, so that window does not
 * have to guess. [enqueueLibraryScan] clears it, and so does the worker once
 * it has applied a known stop reason.
 */
internal object UserScanCancel {
    private val requested = AtomicBoolean(false)

    fun mark() {
        requested.set(true)
    }

    fun clear() {
        requested.set(false)
    }

    fun isRequested(): Boolean = requested.get()
}

/**
 * True only when the user tapped Cancel.
 *
 * [stopReason] is [androidx.work.ListenableWorker.getStopReason]. Any reason
 * other than [WorkInfo.STOP_REASON_CANCELLED_BY_APP] — preemption, constraints,
 * quota, system processing, and the rest — is a system stop, and the same
 * work resumes from the checkpoint. [WorkInfo.STOP_REASON_NOT_STOPPED] means
 * the reason is not available yet; then [userCancelRequested], the flag
 * recorded by the Cancel button, decides.
 */
internal fun isUserScanCancel(stopReason: Int, userCancelRequested: Boolean): Boolean {
    if (stopReason == WorkInfo.STOP_REASON_NOT_STOPPED) return userCancelRequested
    return stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
}
