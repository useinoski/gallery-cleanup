package com.useinoski.gallerycleanup.scan

import com.useinoski.gallerycleanup.data.AnalysisOutcome
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Failures go to [com.useinoski.gallerycleanup.data.RoomScanResultStore.recordFailures].
 * A clean [ScanCategory.Unflagged] row is only a real score.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetectionFailureRecordingTest {
    @Test
    fun aDecodeFailureIsRecordedAndIsNotStoredAsUnflagged() = runTest {
        val analysis = FakeAnalysisRecords()
        val ok = testItem(1)
        val broken = testItem(2)
        val video = testItem(3, MediaType.Video)
        val observer = DetectionBatchObserver(analysis, parallelism = 1) { item ->
            if (item.id == broken.id) error("decode failed")
            peopleSignals()
        }

        observer.onBatch(listOf(ok, broken, video))

        val failure = analysis.failureBatches.single().single()
        assertEquals(MediaKey(MediaType.Image, broken.id), failure.key)
        assertEquals(broken.uri, failure.contentUri)
        assertTrue(analysis.upserted.none { result -> result.mediaStoreId == broken.id })
        val person = analysis.upserted.single { result -> result.mediaStoreId == ok.id }
        assertEquals(ScanCategory.People, person.category)
        assertEquals("mlkit-face-count|face-v2", person.modelVersion)
        assertEquals(ScanCategory.Video, analysis.upserted.single { it.mediaStoreId == video.id }.category)
        val stored = analysis.rows.getValue(failure.key)
        assertEquals(AnalysisOutcome.Failed, stored.outcome)
        assertEquals(
            listOf(failure.key),
            analysis.keysNeedingRescan(TEST_MODEL_VERSIONS),
        )
    }

    @Test
    fun cancellationExceptionIsRethrownAndWritesNothing() = runTest {
        val analysis = FakeAnalysisRecords()
        val observer = DetectionBatchObserver(analysis) {
            throw CancellationException("stop")
        }
        val error = async {
            try {
                observer.onBatch(listOf(testItem(1), testItem(2)))
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }
        }.await()

        assertTrue(error is CancellationException)
        assertTrue(analysis.upserted.isEmpty())
        assertTrue(analysis.failureBatches.isEmpty())
        assertTrue(analysis.rows.isEmpty())
    }

    @Test
    fun cancellingTheBatchDoesNotRecordAFailure() = runTest {
        val analysis = FakeAnalysisRecords()
        val started = CompletableDeferred<Unit>()
        val observer = DetectionBatchObserver(analysis) {
            started.complete(Unit)
            suspendCancellableCoroutine { }
        }
        val job = launch { observer.onBatch(listOf(testItem(8))) }
        started.await()
        job.cancel()
        job.join()
        assertTrue(analysis.upserted.isEmpty())
        assertTrue(analysis.failureBatches.isEmpty())
    }

    @Test
    fun securityExceptionWritesNothingForTheBatch() = runTest {
        val analysis = FakeAnalysisRecords()
        val observer = DetectionBatchObserver(analysis, parallelism = 2) { item ->
            if (item.id == 2L) throw SecurityException("grant revoked")
            peopleSignals()
        }
        val error = async {
            try {
                observer.onBatch(listOf(testItem(1), testItem(2), testItem(3)))
                null
            } catch (revoked: SecurityException) {
                revoked
            }
        }.await()

        assertTrue(error is SecurityException)
        assertTrue(analysis.upserted.isEmpty())
        assertTrue(analysis.failureBatches.isEmpty())
        assertTrue(analysis.rows.isEmpty())
    }
}
