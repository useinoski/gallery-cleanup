package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.CategoryRule
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItemMetadata
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeCategoryRuleTest {
    private val clock = fixedClock("2026-11-26T12:00:00Z")

    @Test
    fun unionsEveryMatch() {
        val rules = standardCategoryRules(clock)
        val chatScreenshot = imageAt(
            folder = "WhatsApp/Media/WhatsApp Images/Sent/",
            name = "Screenshot_20200101-101010.jpg",
            dateTakenMillis = takenMillis(LocalDate.parse("2020-01-01")),
        )
        assertEquals(
            setOf(MediaCategory.Screenshots, MediaCategory.ChatImages, MediaCategory.OldItems),
            rules.categorize(chatScreenshot),
        )
    }

    @Test
    fun anEmptyRuleDoesNotEraseAnotherMatch() {
        val rules = CompositeCategoryRule(
            listOf(
                object : CategoryRule {
                    override fun categorize(item: MediaItemMetadata): Set<MediaCategory> = emptySet()
                },
                OldItemRule(clock = clock, months = OldItemMonths.One),
            ),
        )
        val item = imageAt(dateTakenMillis = takenMillis(LocalDate.parse("2020-01-01")))
        assertEquals(setOf(MediaCategory.OldItems), rules.categorize(item))
    }

    @Test
    fun aRecentCameraPhotoMatchesNothing() {
        val item = imageAt(
            folder = "DCIM/Camera/",
            name = "IMG_20250101_101530.jpg",
            dateTakenMillis = takenMillis(LocalDate.parse("2026-11-01")),
        )
        assertEquals(emptySet<MediaCategory>(), standardCategoryRules(clock).categorize(item))
        assertEquals(emptySet<MediaCategory>(), CompositeCategoryRule(emptyList()).categorize(item))
    }

    @Test
    fun categorizeOnlyReturnsCategoriesAndDoesNotChangeTheItem() {
        val item = imageAt(
            folder = "Pictures/Screenshots/",
            name = "Screenshot_20200101-000000.png",
            dateTakenMillis = takenMillis(LocalDate.parse("2020-01-01")),
        )
        val before = item.copy()
        val matches = standardCategoryRules(clock).categorize(item)
        assertEquals(before, item)
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { category -> category in MediaCategory.entries })
    }

    @Test
    fun theSameItemAndClockReturnTheSameSet() {
        val rules = standardCategoryRules(clock, OldItemMonths.Three)
        val item = imageAt(
            folder = "DCIM/Screenshots/",
            name = "IMG_0001.jpg",
            dateTakenMillis = takenMillis(LocalDate.parse("2026-01-01")),
        )
        assertEquals(rules.categorize(item), rules.categorize(item))
    }

    @Test
    fun ruleSourcesStayFreeOfAndroidAndDeletion() {
        val dir = File("src/main/kotlin/com/useinoski/gallerycleanup/media/category")
        assertTrue(dir.isDirectory)
        val sources = dir.listFiles { file -> file.extension == "kt" }.orEmpty()
        assertTrue(sources.isNotEmpty())
        val banned = listOf(
            "import android",
            "android.",
            "createTrashRequest",
            "ContentResolver",
            "MediaStore.",
        )
        sources.forEach { file ->
            val code = stripComments(file.readText())
            banned.forEach { token ->
                assertFalse("${file.name} contains $token", code.contains(token))
            }
        }
    }

    @Test
    fun theScannerDoesNotCallTheRulesYet() {
        val main = File("../../app/src/main")
        assertTrue(main.isDirectory)
        main.walkTopDown().filter { file -> file.extension == "kt" }.forEach { file ->
            val text = file.readText()
            assertFalse(file.path, text.contains("standardCategoryRules"))
            assertFalse(file.path, text.contains("ScreenshotRule"))
            assertFalse(file.path, text.contains("ChatImageRule"))
            assertFalse(file.path, text.contains("OldItemRule"))
            assertFalse(file.path, text.contains("CompositeCategoryRule"))
        }
    }

    private fun stripComments(source: String): String {
        val withoutBlocks = source.replace(BLOCK_COMMENT, "")
        return withoutBlocks.lineSequence()
            .filterNot { line -> line.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private companion object {
        val BLOCK_COMMENT: Regex = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    }
}
