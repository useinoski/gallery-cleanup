package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.media.LibraryScanner

/**
 * One scan pass, plus the single retry of rows the pass did not score, and
 * the close of the detectors that pass owned.
 *
 * [detectors] stays open while [scanner] and [afterPass] run. [close] runs
 * when the pass finishes, when it fails, and when it is cancelled. A
 * cancelled coroutine still runs a non-suspending [AutoCloseable.close].
 */
internal class LibraryScan(
    private val scanner: LibraryScanner,
    private val detectors: AutoCloseable = AutoCloseable { },
    private val afterPass: suspend () -> Unit = {},
) {
    suspend fun run() {
        try {
            scanner.scan()
            afterPass()
        } finally {
            detectors.close()
        }
    }
}
