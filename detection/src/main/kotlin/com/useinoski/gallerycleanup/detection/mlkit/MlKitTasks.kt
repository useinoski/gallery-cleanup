package com.useinoski.gallerycleanup.detection.mlkit

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspend until a Play Services [Task] finishes.
 *
 * This is the same idea as `kotlinx-coroutines-play-services`, written out so
 * the module does not take a dependency whose name contains "play-services".
 * The listener resumes the coroutine. It does not block the thread it was
 * called on. Cancellation of the coroutine does not cancel the Task; ML Kit
 * Tasks generally cannot be cancelled, and the result is then ignored.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
