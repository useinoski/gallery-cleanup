package com.useinoski.gallerycleanup.detection.eval

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import com.useinoski.gallerycleanup.detection.nsfw.MarqoNsfwDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One evaluation pass.
 *
 * A fresh [MarqoNsfwDetector] is created for the pass and [close]d in
 * `finally`, including when the coroutine is cancelled or photo access is
 * revoked. The next pass constructs another one. A closed detector throws
 * if it is used again, which is the same rule the library scan follows.
 *
 * [classify] refuses the main thread. [run] refuses it too, before the
 * detector exists, so a click handler cannot start the model on the UI thread.
 * Call [run] from `Dispatchers.Default` (or any other background dispatcher).
 */
internal class NsfwEvaluationPass(
    private val openSession: () -> NsfwScoreSession,
    private val decode: suspend (Long) -> Bitmap?,
) {
    constructor(context: Context) : this(
        openSession = { MarqoNsfwScoreSession(context.applicationContext) },
        decode = { mediaId ->
            withContext(Dispatchers.IO) {
                NsfwPhotoQueue(context.applicationContext.contentResolver).decode(mediaId)
            }
        },
    )

    suspend fun run(
        labels: Map<Long, NudityLabel>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): NsfwEvalReport {
        checkOffMainThread()
        val session = openSession()
        try {
            val items = ArrayList<LabeledScore>(labels.size)
            val total = labels.size
            var done = 0
            for ((mediaId, label) in labels) {
                coroutineContext.ensureActive()
                items += LabeledScore(mediaId, label, scoreOne(session, mediaId))
                done++
                onProgress(done, total)
            }
            return NsfwEvalMetrics.report(items)
        } finally {
            session.close()
        }
    }

    private suspend fun scoreOne(session: NsfwScoreSession, mediaId: Long): Double? {
        val bitmap = try {
            decode(mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (revoked: SecurityException) {
            throw revoked
        } catch (ignored: Exception) {
            return null
        }
        if (bitmap == null) return null
        return try {
            try {
                val score = session.score(bitmap)
                if (score != null && score.isFinite()) score else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (revoked: SecurityException) {
                throw revoked
            } catch (ignored: Exception) {
                null
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}

/** The piece of [MarqoNsfwDetector] this pass needs. Tests substitute a fake. */
internal interface NsfwScoreSession : AutoCloseable {
    suspend fun score(bitmap: Bitmap): Double?
}

internal class MarqoNsfwScoreSession(
    context: Context,
) : NsfwScoreSession {
    private val detector = MarqoNsfwDetector(context.applicationContext)

    override suspend fun score(bitmap: Bitmap): Double? {
        val raw = detector.classify(bitmap).scores[MarqoNsfwDetector.LABEL_NSFW] ?: return null
        if (!raw.isFinite()) return null
        return raw.toDouble()
    }

    override fun close() {
        detector.close()
    }
}

private fun checkOffMainThread() {
    val main = Looper.getMainLooper() ?: return
    check(Thread.currentThread() !== main.thread) {
        "Evaluation must run off the main thread."
    }
}
