package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.media.LibraryScanner
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Opens one pass of the library scan.
 *
 * [open] runs at the start of every [LibraryScan.run]. It must return new
 * detectors. A closed [com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector]
 * throws `IllegalStateException("detector is closed")` if it is used again.
 */
internal fun interface ScanPassFactory {
    fun open(): LibraryScan.Pass
}

/**
 * One scan pass, plus the single retry of rows the pass did not score, and
 * the close of the detectors that pass owned.
 *
 * [factory] supplies the detectors. [run] closes them when the pass finishes,
 * when it fails, and when it is cancelled. The next [run] asks the factory
 * again, so a closed detector is not reused. [AutoCloseable.close] does not
 * suspend, so cancellation still runs it.
 *
 * Only one pass opens detectors at a time, process-wide. Each pass loads its
 * own NSFW model. Unique work is what stops a second Start from enqueueing
 * another worker. This gate is the backstop when two workers overlap anyway:
 * the second waits, and it does not construct a second model until the first
 * pass has closed its own.
 *
 * A user cancel leaves the stored phase [ScanPhase.Cancelled]. A stop
 * WorkManager uses when the phone backgrounds the process (the notification
 * shade is enough on some devices) is not a user cancel. The scanner records
 * [ScanPhase.Cancelled] for every [CancellationException]; [run] puts the
 * phase back to [ScanPhase.Running] when [userCancel] is false, so the screen
 * does not offer Start while the same work is about to run again. The worker
 * passes [isUserScanCancel] for that, using [androidx.work.ListenableWorker.getStopReason]
 * and, if that is still [androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED], the
 * Cancel-button flag. The checkpoint is unchanged either way. The next
 * execution continues from the last saved batch. It does not start the
 * counted progress over at zero.
 */
internal class LibraryScan(
    private val factory: ScanPassFactory,
) {
    constructor(open: () -> Pass) : this(ScanPassFactory { open() })

    suspend fun run(userCancel: () -> Boolean = { true }) {
        ScanPassGate.exclusive {
            val pass = factory.open()
            try {
                try {
                    pass.scanner.scan()
                    pass.afterPass()
                } catch (cancelled: CancellationException) {
                    val runs = pass.runs
                    if (runs != null && !userCancel()) {
                        restoreRunning(runs)
                    }
                    throw cancelled
                }
            } finally {
                pass.detectors.close()
            }
        }
    }

    internal class Pass(
        val scanner: LibraryScanner,
        val detectors: AutoCloseable = AutoCloseable { },
        val afterPass: suspend () -> Unit = {},
        val runs: ScanStore? = null,
    )
}

private object ScanPassGate {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

private suspend fun restoreRunning(store: ScanStore) {
    withContext(NonCancellable) {
        val run = store.readRun()
        if (run.phase != ScanPhase.Cancelled) return@withContext
        store.saveRun(run.copy(phase = ScanPhase.Running, detail = null))
    }
}
