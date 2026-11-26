package com.useinoski.gallerycleanup.detection.eval

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsfwEvaluationPassTest {
    @Test
    fun theMainThreadFailsBeforeTheSessionExists() = runBlocking {
        var opened = false
        val pass = NsfwEvaluationPass(
            openSession = {
                opened = true
                error("session should not be created")
            },
            decode = { _ -> null },
        )
        try {
            pass.run(emptyMap())
            fail("main-thread evaluation should fail")
        } catch (rejected: IllegalStateException) {
            assertTrue(rejected.message!!.contains("main thread"))
        }
        assertFalse(opened)
    }

    @Test
    fun aScoringFailureIsNotScoredAndTheSessionStillCloses() = runBlocking {
        withContext(Dispatchers.Default) {
            var closed = false
            var scored: Bitmap? = null
            val pass = pass(
                score = { bitmap ->
                    assertFalse(bitmap.isRecycled)
                    scored = bitmap
                    error("score failed")
                },
                onClose = { closed = true },
                onBitmap = {},
            )
            val report = pass.run(mapOf(2L to NudityLabel.EXPLICIT))
            assertTrue(closed)
            assertTrue(scored!!.isRecycled)
            assertEquals(1, report.notScored)
            assertEquals(1, report.labelCounts.explicit)
            val rows = rowsFor(report, 2L)
            assertEquals(0, rowNear(rows, 0.05).confusion.truePositive)
            assertEquals(0, rowNear(rows, 0.05).confusion.falseNegative)
        }
    }

    @Test
    fun aDecodeFailureDoesNotAbortTheRest() = runBlocking {
        withContext(Dispatchers.Default) {
            var closed = false
            val pass = NsfwEvaluationPass(
                openSession = { closingSession(score = { _ -> 0.9 }, onClose = { closed = true }) },
                decode = { mediaId ->
                    if (mediaId == 1L) throw IllegalStateException("unreadable")
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                },
            )
            val report = pass.run(
                mapOf(
                    1L to NudityLabel.NOT_EXPLICIT,
                    2L to NudityLabel.EXPLICIT,
                ),
            )
            assertTrue(closed)
            assertEquals(1, report.notScored)
            val rows = rowsFor(report, 2L)
            assertEquals(1, rowNear(rows, 0.05).confusion.truePositive)
        }
    }

    @Test
    fun revokedAccessClosesTheSessionAndPropagates() = runBlocking {
        withContext(Dispatchers.Default) {
            var closed = false
            val pass = NsfwEvaluationPass(
                openSession = { closingSession(score = { _ -> 0.0 }, onClose = { closed = true }) },
                decode = { _ -> throw SecurityException("denied") },
            )
            try {
                pass.run(mapOf(1L to NudityLabel.EXPLICIT))
                fail("access revocation should propagate")
            } catch (expected: SecurityException) {
                assertEquals("denied", expected.message)
            }
            assertTrue(closed)
        }
    }

    @Test
    fun cancellationClosesTheSession() = runBlocking {
        var closed = false
        val pass = NsfwEvaluationPass(
            openSession = { closingSession(score = { _ -> 0.0 }, onClose = { closed = true }) },
            decode = { _ -> awaitCancellation() },
        )
        val job = launch(Dispatchers.Default) {
            pass.run(mapOf(1L to NudityLabel.EXPLICIT))
        }
        job.cancelAndJoin()
        assertTrue(closed)
    }

    @Test
    fun progressIsCountsOnlyAndScoresReachTheReport() = runBlocking {
        withContext(Dispatchers.Default) {
            var closed = false
            val seen = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val pass = NsfwEvaluationPass(
                openSession = {
                    closingSession(
                        score = { bitmap ->
                            assertFalse(bitmap.isRecycled)
                            0.25
                        },
                        onClose = { closed = true },
                    )
                },
                decode = { _ -> seen },
            )
            val progress = ArrayList<Pair<Int, Int>>()
            val report = pass.run(mapOf(2L to NudityLabel.EXPLICIT)) { done, total ->
                progress += done to total
            }
            assertEquals(listOf(1 to 1), progress)
            assertTrue(closed)
            assertTrue(seen.isRecycled)
            val rows = rowsFor(report, 2L)
            assertEquals(1, rowNear(rows, 0.05).confusion.truePositive)
            assertEquals(1, rowNear(rows, 0.25).confusion.truePositive)
            assertEquals(0, rowNear(rows, 0.30).confusion.truePositive)
            assertEquals(1, rowNear(rows, 0.30).confusion.falseNegative)
        }
    }

    @Test
    fun aNonFiniteScoreIsNotScored() = runBlocking {
        withContext(Dispatchers.Default) {
            val pass = pass(score = { _ -> Double.NaN }, onClose = {}, onBitmap = {})
            val report = pass.run(mapOf(4L to NudityLabel.NOT_EXPLICIT))
            assertEquals(1, report.notScored)
            assertEquals(1, report.labelCounts.notExplicit)
        }
    }

    private fun pass(
        score: suspend (Bitmap) -> Double?,
        onClose: () -> Unit,
        onBitmap: (Bitmap) -> Unit,
    ): NsfwEvaluationPass = NsfwEvaluationPass(
        openSession = { closingSession(score = score, onClose = onClose) },
        decode = { _ ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            onBitmap(bitmap)
            bitmap
        },
    )
}

private fun closingSession(
    score: suspend (Bitmap) -> Double?,
    onClose: () -> Unit,
): NsfwScoreSession = object : NsfwScoreSession {
    override suspend fun score(bitmap: Bitmap): Double? = score(bitmap)
    override fun close() = onClose()
}

private fun rowsFor(report: NsfwEvalReport, mediaId: Long): List<GateRow> =
    if (NsfwEvalMetrics.half(mediaId) == EvalHalf.TUNING) report.tuning.rows else report.heldOut.rows

private fun rowNear(rows: List<GateRow>, threshold: Double): GateRow =
    rows.first { abs(it.threshold - threshold) < 1e-9 }
