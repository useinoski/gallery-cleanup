package com.useinoski.gallerycleanup.detection.eval

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The labeler is a debug source set. Release compilation does not see those
 * files, and this test fails if someone copies the screen or the label file
 * into `src/main` or `src/release`.
 *
 * Working directory is the :detection module.
 */
class DebugLabelerSourceGuardTest {
    @Test
    fun mainAndReleaseSourcesHaveNoLabeler() {
        val hits = listOf(File("src/main"), File("src/release"))
            .flatMap { root -> filesUnder(root) }
            .filter { file ->
                val text = file.readText()
                LABEL_TOKENS.any { token -> text.contains(token) }
            }
        assertTrue(hits.joinToString { it.path }, hits.isEmpty())
        assertFalse(File("src/main/kotlin/com/useinoski/gallerycleanup/detection/eval").exists())
    }

    @Test
    fun debugCodeIsTheOnlyCopyOfTheScreenAndTheStore() {
        val debugFiles = filesUnder(File("src/debug"))
        val text = debugFiles.joinToString("\n") { it.readText() }
        assertTrue(text.contains("class NsfwLabelActivity"))
        assertTrue(text.contains("class NsfwLabelStore"))
        assertTrue(text.contains("noBackupFilesDir"))
        assertTrue(text.contains("NsfwEvalMetrics.summary"))
        assertTrue(text.contains("setPrimaryClip"))
        assertTrue(text.contains("MarqoNsfwDetector"))
        assertTrue(text.contains("session.close()"))
        assertTrue(text.contains("finally"))
        BANNED.forEach { token ->
            val offenders = debugFiles.filter { it.readText().contains(token) }
            assertTrue("debug sources contain $token in $offenders", offenders.isEmpty())
        }
    }

    @Test
    fun debugManifestDeclaresTheScreenAndNoPermission() {
        val debug = File("src/debug/AndroidManifest.xml").readText()
        assertTrue(debug.contains(".eval.NsfwLabelActivity"))
        assertFalse(debug.contains("uses-permission"))
        assertFalse(debug.contains("INTERNET"))
        val main = File("src/main/AndroidManifest.xml").readText()
        assertFalse(main.contains("NsfwLabelActivity"))
        val release = File("src/release/AndroidManifest.xml")
        if (release.isFile) {
            assertFalse(release.readText().contains("NsfwLabelActivity"))
        }
    }
}

private val LABEL_TOKENS = listOf(
    "NsfwLabelActivity",
    "NsfwLabelStore",
    "NsfwEvalMetrics",
    "NsfwEvaluationPass",
    "nsfw-labeler",
    "NudityLabel",
)

private val BANNED = listOf(
    "android.util.Log",
    "android.permission.INTERNET",
    "requestPermissions",
    "ActivityResultContracts",
    "ACTION_SEND",
    "getExternalStorage",
    "getExternalFilesDir",
    "getSharedPreferences",
    "openFileOutput",
    "openOutputStream",
    "MODE_WORLD_READABLE",
    ".insert(",
)

private fun filesUnder(root: File): List<File> {
    if (!root.exists()) return emptyList()
    return root.walkTopDown()
        .filter { file -> file.isFile && (file.extension == "kt" || file.extension == "xml") }
        .toList()
}
