package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.label.LabelCategories
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TSV is the base-model table from
 * https://developers.google.com/ml-kit/vision/image-labeling/label-map
 * saved on 2026-11-19. It matches the copy that shipped with the model notes.
 */
class MlKitLabelListTest {
    private val labels: Map<String, String> by lazy { load() }

    @Test
    fun publishedNamesAndIndexesMatchTheMapper() {
        assertEquals("430", labels.getValue("Alcohol"))
        assertEquals("320", labels.getValue("Wine"))
        assertEquals("94", labels.getValue("Bar"))
        assertEquals("104", labels.getValue("Nightclub"))
        assertEquals("86", labels.getValue("Casino"))
        assertEquals("234", labels.getValue("Money"))
        assertEquals("121", labels.getValue("Screenshot"))
        assertEquals("2", labels.getValue("Comics"))
        assertEquals("93", labels.getValue("Poster"))
        val mapped = LabelCategories.alcoholLabels +
            LabelCategories.gamblingLabels +
            LabelCategories.moneyLabels +
            LabelCategories.memeLabels
        mapped.forEach { name ->
            assertTrue("$name is not in the published list", labels.containsKey(name))
        }
    }

    @Test
    fun theBaseModelHasNoSmokingOrDrugLabel() {
        val blocked = listOf("smok", "cigar", "drug", "tobacco", "cannab", "vape")
        labels.keys.forEach { name ->
            val lower = name.lowercase()
            blocked.forEach { needle ->
                assertFalse("$name looks like $needle", lower.contains(needle))
            }
        }
        LabelCategories.outOfScopeLabels.forEach { name ->
            assertFalse(labels.containsKey(name))
        }
    }

    @Test
    fun snapshotHasThePublishedRowCount() {
        assertEquals(430, labels.size)
    }

    private fun load(): Map<String, String> {
        val file = File("src/test/resources/mlkit_image_labeling_labels.tsv")
        val rows = file.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split("\t")
            check(parts.size == 2) { "bad label row: $trimmed" }
            parts[1] to parts[0]
        }
        return rows.toMap().also { map ->
            assertEquals(rows.size, map.size)
        }
    }
}
