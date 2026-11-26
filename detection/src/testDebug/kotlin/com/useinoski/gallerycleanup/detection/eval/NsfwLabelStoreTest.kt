package com.useinoski.gallerycleanup.detection.eval

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NsfwLabelStoreTest {
    @Test
    fun labelsRoundTripAcrossANewStore() {
        val directory = tempDir()
        val store = NsfwLabelStore(directory)
        store.put(5L, NudityLabel.EXPLICIT)
        store.put(9L, NudityLabel.UNSURE)
        store.put(5L, NudityLabel.IMMODEST)
        val again = NsfwLabelStore(directory)
        assertEquals(NudityLabel.IMMODEST, again.read()[5L])
        assertEquals(NudityLabel.UNSURE, again.read()[9L])
        again.put(4L, NudityLabel.NOT_EXPLICIT)
        assertEquals(NudityLabel.NOT_EXPLICIT, again.read()[4L])
    }

    @Test
    fun theFileContainsIdsAndLabelsOnly() {
        val directory = tempDir()
        val store = NsfwLabelStore(directory)
        store.put(12L, NudityLabel.NOT_EXPLICIT)
        store.put(3L, NudityLabel.EXPLICIT)
        val text = store.storageFile().readText()
        assertEquals("v1\n3 EXPLICIT\n12 NOT_EXPLICIT\n", text)
        assertFalse(text.contains("/"))
        assertFalse(text.contains("content:"))
        assertFalse(text.contains("DCIM"))
        assertEquals(listOf("labels.txt"), directory.list()?.sorted())
        assertEquals(directory, store.storageFile().parentFile)
    }

    @Test
    fun aBrokenLineIsSkippedAndTheNextSaveRewritesTheFile() {
        val directory = tempDir()
        File(directory, "labels.txt").writeText("garbage\n12 MAYBE\nv1\n7 EXPLICIT\n")
        val store = NsfwLabelStore(directory)
        assertEquals(mapOf(7L to NudityLabel.EXPLICIT), store.read())
        store.put(8L, NudityLabel.UNSURE)
        assertEquals(setOf(7L, 8L), store.read().keys)
        assertFalse(store.storageFile().readText().contains("MAYBE"))
        assertFalse(store.storageFile().readText().contains("garbage"))
    }

    @Test
    fun aContextStoreStaysInsideNoBackupFilesDir() {
        val context = RuntimeEnvironment.getApplication()
        val store = NsfwLabelStore(context)
        store.put(3L, NudityLabel.EXPLICIT)
        val file = store.storageFile()
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertEquals("nsfw-labeler", file.parentFile?.name)
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            assertFalse(file.canonicalPath.startsWith(external.canonicalPath))
        }
        assertTrue(file.isFile)
    }

    private fun tempDir(): File = Files.createTempDirectory("nsfw-labels").toFile()
}
