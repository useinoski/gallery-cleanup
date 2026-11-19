package app.hearth.media

import app.hearth.model.MediaItem
import app.hearth.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCountTest {
    @Test
    fun skipsCollectionsTheCallerCannotRead() {
        val seen = mutableListOf<StoreQuery>()
        val counts = assembleCounts(
            ReadableMedia(images = true, videos = false, audio = false),
        ) { query ->
            seen += query
            4
        }
        assertEquals(MediaCounts(images = 4, videos = null, music = null, otherAudio = null), counts)
        assertEquals(listOf(MediaType.Image), seen.map { it.type })
    }

    @Test
    fun audioCountSplitsMusicFromEverythingElse() {
        val seen = mutableListOf<StoreQuery>()
        val counts = assembleCounts(
            ReadableMedia(images = false, videos = false, audio = true),
        ) { query ->
            seen += query
            if (query.selection.contains("$COL_MUSIC != 0")) 30 else 6
        }
        assertEquals(30, counts.music)
        assertEquals(6, counts.otherAudio)
        assertEquals(36, counts.audio)
        assertTrue(seen.all { NOT_PENDING in it.selection })
        assertTrue(seen.any { "$COL_MUSIC != 0" in it.selection })
        assertTrue(seen.any { "$COL_MUSIC = 0" in it.selection })
    }

    @Test
    fun pageQueryAsksForOneExtraRowAndSortsNewestFirst() {
        val query = pageQuery(MediaPageRequest(MediaType.Video, offset = 20, limit = 10))
        assertEquals(MediaType.Video, query.type)
        assertEquals(20, query.offset)
        assertEquals(11, query.limit)
        assertEquals(NOT_PENDING, query.selection)
        assertTrue(query.sortOrder!!.contains(COL_DATE_ADDED))
        assertTrue(query.sortOrder.contains(COL_ID))
    }

    @Test
    fun pageOfDropsTheExtraRowAndAdvancesTheOffset() {
        val rows = List(11) { index -> sample(index.toLong()) }
        val page = pageOf(rows, offset = 20, limit = 10)
        assertEquals(10, page.items.size)
        assertEquals(30, page.nextOffset)
        assertEquals(0, page.items.first().id)
    }

    @Test
    fun lastPageHasNoNextOffset() {
        val page = pageOf(listOf(sample(1)), offset = 0, limit = 10)
        assertEquals(1, page.items.size)
        assertNull(page.nextOffset)
    }

    @Test
    fun relativePathJoinsFolderAndDisplayName() {
        assertEquals(
            "DCIM/Camera/IMG_0001.jpg",
            relativeItemPath("DCIM/Camera/", "IMG_0001.jpg"),
        )
        assertEquals("IMG_0001.jpg", relativeItemPath(null, "IMG_0001.jpg"))
        assertNull(relativeItemPath("  ", " "))
    }

    private fun sample(id: Long) = MediaItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = MediaType.Image,
        dateAdded = 0,
        size = 1,
        path = null,
        mimeType = "image/jpeg",
    )
}
