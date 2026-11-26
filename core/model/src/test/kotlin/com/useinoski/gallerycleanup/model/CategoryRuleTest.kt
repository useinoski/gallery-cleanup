package com.useinoski.gallerycleanup.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The object under test is a stand-in. It checks that [CategoryRule] can be
 * implemented against [MediaItemMetadata] and can return more than one
 * category. It is not the music rule, and it does not exclude voice notes.
 */
class CategoryRuleTest {
    @Test
    fun aRuleReturnsTheSetItMatches() {
        val rule = object : CategoryRule {
            override fun categorize(item: MediaItemMetadata): Set<MediaCategory> {
                return if (item.isMusic) setOf(MediaCategory.Music) else emptySet()
            }
        }
        assertEquals(setOf(MediaCategory.Music), rule.categorize(audio(isMusic = true)))
        assertEquals(emptySet<MediaCategory>(), rule.categorize(audio(isMusic = false)))
        assertEquals(emptySet<MediaCategory>(), rule.categorize(image()))
    }

    @Test
    fun oneFileCanMatchMoreThanOneCategory() {
        val both = object : CategoryRule {
            override fun categorize(item: MediaItemMetadata): Set<MediaCategory> =
                if (item.type == MediaType.Image) {
                    setOf(MediaCategory.Screenshots, MediaCategory.OldItems)
                } else {
                    emptySet()
                }
        }
        assertEquals(
            setOf(MediaCategory.Screenshots, MediaCategory.OldItems),
            both.categorize(image()),
        )
        assertEquals(emptySet<MediaCategory>(), both.categorize(audio(isMusic = false)))
    }

    @Test
    fun metadataIsAValueTheRuleCanRead() {
        val item = audio(isMusic = false)
        assertEquals(item, item.copy())
        val music = item.copy(isMusic = true)
        assertFalse(item.isMusic)
        assertTrue(music.isMusic)
        assertEquals(MediaType.Audio, item.type)
        assertEquals("Recordings/", item.relativePath)
        assertEquals("note.ogg", item.displayName)
        assertEquals("audio/ogg", item.mimeType)
        assertEquals(1_700_000_000_000L, item.dateTakenMillis)
        assertEquals(1_700_000_000L, item.dateAdded)
        assertEquals(12_000L, item.durationMillis)
        assertEquals(4_096L, item.size)
    }

    private fun image(): MediaItemMetadata = MediaItemMetadata(
        type = MediaType.Image,
        relativePath = "DCIM/Camera/",
        displayName = "IMG_001.jpg",
        mimeType = "image/jpeg",
        dateTakenMillis = 1_700_000_000_000L,
        dateAdded = 1_700_000_000L,
        durationMillis = null,
        isMusic = false,
        size = 2_048L,
    )

    private fun audio(isMusic: Boolean): MediaItemMetadata = MediaItemMetadata(
        type = MediaType.Audio,
        relativePath = "Recordings/",
        displayName = "note.ogg",
        mimeType = "audio/ogg",
        dateTakenMillis = 1_700_000_000_000L,
        dateAdded = 1_700_000_000L,
        durationMillis = 12_000L,
        isMusic = isMusic,
        size = 4_096L,
    )
}
