package com.useinoski.gallerycleanup.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCategoryTest {
    @Test
    fun stableIdsRoundTrip() {
        val expected = listOf(
            "sensitive" to MediaCategory.Sensitive,
            "screenshots" to MediaCategory.Screenshots,
            "chat-images" to MediaCategory.ChatImages,
            "old-items" to MediaCategory.OldItems,
            "music" to MediaCategory.Music,
            "other-audio" to MediaCategory.OtherAudio,
            "videos" to MediaCategory.Videos,
            "people" to MediaCategory.People,
            "experimental" to MediaCategory.Experimental,
        )
        assertEquals(expected.map { (id, _) -> id }, MediaCategory.entries.map { category -> category.id })
        expected.forEach { (id, category) ->
            assertEquals(id, category.id)
            assertEquals(category, MediaCategory.fromId(id))
        }
        // Names stored by :core:data today, and a padded id, are not these ids.
        assertNull(MediaCategory.fromId("Sensitive"))
        assertNull(MediaCategory.fromId("Screenshot"))
        assertNull(MediaCategory.fromId("Immodest"))
        assertNull(MediaCategory.fromId("AlcoholGambling"))
        assertNull(MediaCategory.fromId("Audio"))
        assertNull(MediaCategory.fromId("Video"))
        assertNull(MediaCategory.fromId("Unflagged"))
        assertNull(MediaCategory.fromId("sensitive "))
        assertNull(MediaCategory.fromId(""))
    }

    @Test
    fun idsAreUnique() {
        val ids = MediaCategory.entries.map { category -> category.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(MediaCategory.entries.size, ids.size)
        MediaCategory.entries.forEach { category ->
            assertTrue(category.id.isNotBlank())
            assertFalse(category.id == category.name)
            assertFalse(category.id.any { character -> character.isWhitespace() })
        }
    }

    @Test
    fun sensitiveAndExperimentalFlags() {
        assertEquals(
            listOf(MediaCategory.Sensitive),
            MediaCategory.entries.filter { category -> category.sensitive },
        )
        assertEquals(
            listOf(MediaCategory.Experimental),
            MediaCategory.entries.filter { category -> category.experimental },
        )
        assertTrue(MediaCategory.Sensitive.sensitive)
        assertFalse(MediaCategory.Sensitive.experimental)
        assertTrue(MediaCategory.Experimental.experimental)
        assertFalse(MediaCategory.Experimental.sensitive)

        val chatAndNudity = setOf(MediaCategory.ChatImages, MediaCategory.Sensitive)
        assertTrue(chatAndNudity.any { category -> category.sensitive })
        assertFalse(chatAndNudity.any { category -> category.experimental })
        assertFalse(setOf(MediaCategory.ChatImages, MediaCategory.Screenshots).any { category -> category.sensitive })
    }
}
