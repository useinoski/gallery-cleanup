package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.media.LibraryScanner

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
 */
internal class LibraryScan(
    private val factory: ScanPassFactory,
) {
    constructor(open: () -> Pass) : this(ScanPassFactory { open() })

    suspend fun run() {
        val pass = factory.open()
        try {
            pass.scanner.scan()
            pass.afterPass()
        } finally {
            pass.detectors.close()
        }
    }

    internal class Pass(
        val scanner: LibraryScanner,
        val detectors: AutoCloseable = AutoCloseable { },
        val afterPass: suspend () -> Unit = {},
    )
}
