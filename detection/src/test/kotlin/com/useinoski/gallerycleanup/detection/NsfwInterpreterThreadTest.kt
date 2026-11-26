package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.nsfw.InterpreterSingleThread
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LiteRT GPU delegate has to be created, used, and closed on one thread.
 * This stands in for [com.useinoski.gallerycleanup.detection.nsfw.NsfwInterpreter]
 * so the check can run on the JVM.
 */
class NsfwInterpreterThreadTest {
    @Test
    fun createRunAndCloseShareOneThread() {
        val callers = Collections.synchronizedSet(mutableSetOf<Thread>())
        lateinit var fake: FakeInterpreter
        val session = InterpreterSingleThread {
            FakeInterpreter().also { created -> fake = created }
        }
        try {
            runBlocking {
                val first = async(Dispatchers.Default) {
                    synchronized(callers) { callers += Thread.currentThread() }
                    session.use { interpreter -> interpreter.run(floatArrayOf(1f)) }
                }
                val second = async(Dispatchers.Default) {
                    synchronized(callers) { callers += Thread.currentThread() }
                    session.use { interpreter -> interpreter.run(floatArrayOf(2f)) }
                }
                assertEquals(listOf(1f, 0f), first.await().toList())
                assertEquals(listOf(2f, 0f), second.await().toList())
            }
            thread(name = "close-caller") {
                synchronized(callers) { callers += Thread.currentThread() }
                session.close()
            }.join()

            val events = fake.events
            assertEquals(listOf("create", "run", "run", "close"), events.map { event -> event.first })
            val threads = events.map { event -> event.second }.toSet()
            assertEquals(1, threads.size)
            val interpreterThread = threads.single()
            assertEquals("marqo-nsfw-interpreter", interpreterThread.name)
            assertTrue(callers.none { caller -> caller === interpreterThread })
            assertTrue(callers.size >= 2)
        } finally {
            session.close()
        }
    }

    @Test
    fun detectorRoutesInferenceThroughThatThread() {
        val source = File(
            "src/main/kotlin/com/useinoski/gallerycleanup/detection/nsfw/MarqoNsfwDetector.kt",
        ).readText()
        val code = source.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
        assertTrue(source.contains("InterpreterSingleThread"))
        assertTrue(code.contains("session.use"))
        assertTrue(code.contains("session.close()"))
        assertFalse(code.contains("Dispatchers"))
    }

    private class FakeInterpreter : AutoCloseable {
        val events = mutableListOf<Pair<String, Thread>>()

        init {
            events += "create" to Thread.currentThread()
        }

        fun run(input: FloatArray): FloatArray {
            events += "run" to Thread.currentThread()
            return floatArrayOf(input.first(), 0f)
        }

        override fun close() {
            events += "close" to Thread.currentThread()
        }
    }
}
