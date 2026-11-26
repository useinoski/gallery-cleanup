package com.useinoski.gallerycleanup.scan

import androidx.work.WorkInfo
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanStore
import kotlinx.coroutines.CancellationException

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
 * True when this cancellation is the user tapping Cancel.
 *
 * WorkManager stops the worker with `WorkerStoppedException`. The reason is
 * [WorkInfo.STOP_REASON_CANCELLED_BY_APP] for [cancelLibraryScan]. A shade
 * pull, a quota stop, or the process moving to the background uses another
 * reason. Those are not a user cancel: the same unique work runs again from
 * the checkpoint. A plain [CancellationException] (a test, or a coroutine
 * we cancelled ourselves) is treated as a user cancel.
 *
 * The exception type lives in WorkManager's impl package and is not app API.
 * Reading it by name keeps the lint check for restricted APIs quiet.
 */
internal fun CancellationException.isUserScanCancel(): Boolean {
    val seen = HashSet<Throwable>(4)
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current.javaClass.name == "androidx.work.impl.WorkerStoppedException") {
            val reason = current.javaClass.methods
                .firstOrNull { method -> method.name == "getReason" && method.parameterCount == 0 }
                ?.invoke(current) as? Int
            return reason == null || reason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
        }
        current = current.cause
    }
    return true
}
