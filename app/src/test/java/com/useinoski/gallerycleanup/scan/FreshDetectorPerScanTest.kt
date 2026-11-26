package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.model.InMemoryScanStore
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.ScanPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A closed detector throws, the same way Marqo does after [AutoCloseable.close].
 * The next pass has to be a new instance.
 */
class FreshDetectorPerScanTest {
    @Test
    fun aSecondScanClassifiesWithAFreshDetectorAndRecordsNoFailure() = runTest {
        val library = ScanMemoryLibrary(listOf(scannedRow(id = 1, modified = 10, generation = 1)))
        val store = InMemoryScanStore()
        val analysis = FakeAnalysisRecords()
        val opened = ArrayList<ClosingDetector>()
        var pass = 0
        val scan = LibraryScan {
            pass += 1
            val detector = ClosingDetector()
            opened += detector
            val observer = DetectionBatchObserver(analysis, parallelism = 1) {
                detector.classify()
                ImageSignals()
            }
            LibraryScan.Pass(
                scanner = testScanner(
                    library = library,
                    store = store,
                    observer = observer,
                    generation = pass.toLong(),
                    version = "v$pass",
                ),
                detectors = detector,
            )
        }

        scan.run()
        scan.run()

        assertEquals(2, opened.size)
        assertTrue(opened[0].closed)
        assertTrue(opened[1].closed)
        assertEquals(listOf("use-1", "close-1", "use-2", "close-2"), opened.flatMap { it.events })
        assertTrue(analysis.failureBatches.isEmpty())
        assertEquals(2, analysis.upserted.count { result -> result.mediaStoreId == 1L })
        assertEquals(AnalysisOutcome.Scored, analysis.rows.getValue(MediaKey(MediaType.Image, 1)).outcome)
        assertEquals(ScanPhase.Completed, store.readRun().phase)
    }

    /**
     * Same failure a closed [com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector] raises.
     * [check] throws [IllegalStateException].
     */
    private class ClosingDetector : AutoCloseable {
        var closed: Boolean = false
        val events = ArrayList<String>(2)
        private val id = nextId()

        fun classify() {
            if (closed) throw IllegalStateException("detector is closed")
            events += "use-$id"
        }

        override fun close() {
            closed = true
            events += "close-$id"
        }

        companion object {
            private var sequence = 0
            private fun nextId(): Int {
                sequence += 1
                return sequence
            }
        }
    }
}
