package com.useinoski.gallerycleanup.detection.nsfw

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs one LiteRT interpreter on a single thread for its whole life.
 *
 * The GPU delegate must be created, used, and closed on the same thread.
 * [MarqoNsfwDetector] owns one of these. [use] hops to that thread before the
 * factory runs, and [close] hops back to it before the interpreter is closed,
 * then shuts the dispatcher down.
 */
internal class InterpreterSingleThread<T : AutoCloseable>(
    private val factory: () -> T,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, THREAD_NAME).apply { isDaemon = true }
    }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val thread: Thread = executor.submit(Callable { Thread.currentThread() }).get()
    private val state = Any()
    private var instance: T? = null
    private var closed = false

    val current: T?
        get() = synchronized(state) { instance }

    suspend fun <R> use(block: (T) -> R): R = withContext(dispatcher) {
        check(Thread.currentThread() === thread) { "LiteRT interpreter left $THREAD_NAME" }
        val current = synchronized(state) {
            check(!closed) { "detector is closed" }
            instance ?: factory().also { created -> instance = created }
        }
        block(current)
    }

    override fun close() {
        try {
            if (Thread.currentThread() === thread) {
                closeOnInterpreterThread()
            } else {
                try {
                    executor.submit { closeOnInterpreterThread() }.get()
                } catch (_: RejectedExecutionException) {
                    // A previous close already shut the executor down.
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private fun closeOnInterpreterThread() {
        val toClose = synchronized(state) {
            if (closed) {
                null
            } else {
                closed = true
                instance.also { instance = null }
            }
        }
        toClose?.close()
    }

    private companion object {
        const val THREAD_NAME = "marqo-nsfw-interpreter"
    }
}
