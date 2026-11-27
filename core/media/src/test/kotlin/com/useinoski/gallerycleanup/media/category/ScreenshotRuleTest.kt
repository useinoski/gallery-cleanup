package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotRuleTest {
    @Test
    fun folderNamesLiveInOneList() {
        assertTrue(screenshotFolderNames.containsAll(listOf("Screenshots", "Bildschirmfotos")))
        assertEquals(
            screenshotFolderNames.size,
            screenshotFolderNames.map { name -> name.lowercase() }.toSet().size,
        )
        assertTrue(screenshotFolderNames.all { name -> name.isNotBlank() })
    }

    @Test
    fun samsungAndPixelScreenshotFoldersMatch() {
        val folders = listOf(
            "DCIM/Screenshots/",
            "Pictures/Screenshots/",
            "DCIM/Screenshots",
            "pictures/SCREENSHOTS/",
            "DCIM\\Screenshots\\",
            "Bilder/Screenshots/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                setOf(MediaCategory.Screenshots),
                ScreenshotRule.categorize(imageAt(folder, name = "IMG_0001.jpg")),
            )
        }
    }

    @Test
    fun germanFolderNamesMatch() {
        val folders = listOf(
            "DCIM/Bildschirmfotos/",
            "Pictures/Bildschirmfotos/",
            "Bilder/Bildschirmfotos/",
            "dcim/bildschirmfotos/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                setOf(MediaCategory.Screenshots),
                ScreenshotRule.categorize(imageAt(folder, name = "Bildschirmfoto_20250101-101530.jpg")),
            )
        }
    }

    @Test
    fun samsungFileNameMatchesEvenInTheCameraFolder() {
        val item = imageAt(
            folder = "DCIM/Camera/",
            name = "Screenshot_20250101-101530.jpg",
        )
        assertEquals(setOf(MediaCategory.Screenshots), ScreenshotRule.categorize(item))
        assertEquals(
            setOf(MediaCategory.Screenshots),
            ScreenshotRule.categorize(imageAt(folder = null, name = "screenshot.JPG")),
        )
    }

    @Test
    fun cameraPhotosOfAScreenDoNotMatch() {
        val names = listOf(
            "IMG_20250101_101530.jpg",
            "IMG_001.jpg",
            "20250101_101530.jpg",
            "20250101-101530.jpg",
        )
        names.forEach { name ->
            assertEquals(
                name,
                emptySet<MediaCategory>(),
                ScreenshotRule.categorize(imageAt(folder = "DCIM/Camera/", name = name)),
            )
        }
    }

    @Test
    fun aGermanFileNameOutsideTheFolderDoesNotMatch() {
        val item = imageAt(
            folder = "DCIM/Camera/",
            name = "Bildschirmfoto_20250101-101530.jpg",
        )
        assertEquals(emptySet<MediaCategory>(), ScreenshotRule.categorize(item))
    }

    @Test
    fun folderMustEqualTheListedName() {
        // `screenshot_ideas` contains the word and is still a different folder.
        // `ScreenshotsBackup` and `My Screenshots` are not the segment either.
        val folders = listOf(
            "DCIM/screenshot_ideas/",
            "Pictures/ScreenshotsBackup/",
            "DCIM/My Screenshots/",
            "Download/",
        )
        folders.forEach { folder ->
            assertEquals(
                folder,
                emptySet<MediaCategory>(),
                ScreenshotRule.categorize(imageAt(folder, name = "notes.jpg")),
            )
        }
    }

    @Test
    fun filePrefixMatchesScreenshotIdeasJpg() {
        // The file rule is a prefix, so this name matches. The folder
        // `screenshot_ideas` does not. See folderMustEqualTheListedName.
        val item = imageAt(folder = "DCIM/Camera/", name = "screenshot_ideas.jpg")
        assertEquals(setOf(MediaCategory.Screenshots), ScreenshotRule.categorize(item))
        assertEquals(
            emptySet<MediaCategory>(),
            ScreenshotRule.categorize(imageAt(folder = "DCIM/Camera/", name = "IMG_Screenshot.jpg")),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            ScreenshotRule.categorize(imageAt(folder = "DCIM/Camera/", name = "MyScreenshot.jpg")),
        )
    }

    @Test
    fun onlyImagesMatch() {
        val folder = "DCIM/Screenshots/"
        assertEquals(
            emptySet<MediaCategory>(),
            ScreenshotRule.categorize(imageAt(folder, name = "clip.mp4", type = MediaType.Video)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            ScreenshotRule.categorize(
                imageAt(folder = "DCIM/Camera/", name = "Screenshot_1.m4a", type = MediaType.Audio),
            ),
        )
    }

    @Test
    fun aScreenshotFolderMatchesWithoutAFileName() {
        assertEquals(
            setOf(MediaCategory.Screenshots),
            ScreenshotRule.categorize(imageAt(folder = "Pictures/Screenshots/", name = null)),
        )
        assertEquals(
            emptySet<MediaCategory>(),
            ScreenshotRule.categorize(imageAt(folder = null, name = null)),
        )
        assertFalse(screenshotFolderNames.contains("screenshot_ideas"))
    }
}
