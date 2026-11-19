package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanQueryTest {
    @Test
    fun fullPassHasNoGenerationClauseAndSortsByDateModified() {
        val query = scanPageQuery(
            ScanPageRequest(type = MediaType.Image, limit = 40),
        )
        assertEquals(NOT_PENDING, query.selection)
        assertEquals(MODIFIED_NEWEST_FIRST, query.sortOrder)
        assertEquals(41, query.limit)
        assertEquals(MediaType.Image, query.type)
    }

    @Test
    fun incrementalPassKeepsBothGenerationBoundsAndTheKeyset() {
        val query = scanPageQuery(
            ScanPageRequest(
                type = MediaType.Video,
                limit = 10,
                after = ScanCursor(dateModified = 500, id = 7),
                generationModifiedAfter = 4,
                generationModifiedAtMost = 9,
            ),
        )
        assertTrue(query.selection.contains("$COL_GENERATION_MODIFIED <= 9"))
        assertTrue(query.selection.contains("$COL_GENERATION_MODIFIED > 4"))
        assertTrue(query.selection.contains("$COL_DATE_MODIFIED < 500"))
        assertTrue(query.selection.contains("$COL_DATE_MODIFIED = 500 AND $COL_ID < 7"))
        assertFalse(query.selection.contains(COL_DATE_ADDED))
        assertEquals(11, query.limit)
    }

    @Test
    fun anUncappedGenerationDoesNotAddAnUpperBound() {
        val selection = scanSelection(
            generationModifiedAfter = null,
            generationModifiedAtMost = NO_GENERATION_CAP,
            after = null,
        )
        assertEquals(NOT_PENDING, selection)
        assertTrue(matchesGeneration(Long.MAX_VALUE, generationModifiedAfter = null, NO_GENERATION_CAP))
        assertTrue(matchesGeneration(3, generationModifiedAfter = 2, generationModifiedAtMost = 9))
        assertFalse(matchesGeneration(2, generationModifiedAfter = 2, generationModifiedAtMost = 9))
        assertFalse(matchesGeneration(10, generationModifiedAfter = 2, generationModifiedAtMost = 9))
    }

    @Test
    fun countUsesTheSameFilterWithoutACursor() {
        val query = scanCountQuery(
            ScanCountRequest(
                type = MediaType.Audio,
                generationModifiedAfter = 1,
                generationModifiedAtMost = 3,
            ),
        )
        assertEquals(MediaType.Audio, query.type)
        assertTrue(query.selection.contains("$COL_GENERATION_MODIFIED > 1"))
        assertFalse(query.selection.contains(COL_DATE_MODIFIED))
        assertEquals(null, query.limit)
    }

    @Test
    fun idPagesWalkForwardFromTheLastId() {
        val first = idPageQuery(IdPageRequest(MediaType.Image, afterId = null, limit = 10))
        assertEquals(NOT_PENDING, first.selection)
        assertEquals("$COL_ID ASC", first.sortOrder)
        assertEquals(11, first.limit)
        val next = idPageQuery(IdPageRequest(MediaType.Image, afterId = 15, limit = 10))
        assertTrue(next.selection.contains("$COL_ID > 15"))
    }

    @Test
    fun scanPageOfDropsTheExtraRow() {
        val rows = List(3) { index ->
            ScannedRow(sampleItem(index.toLong()), dateModified = index.toLong(), generationModified = 1)
        }
        val page = scanPageOf(rows, limit = 2)
        assertEquals(2, page.rows.size)
        assertTrue(page.hasMore)
        val last = idPageOf(listOf(1, 2), limit = 5)
        assertEquals(listOf(1L, 2L), last.ids)
        assertFalse(last.hasMore)
    }

    private fun sampleItem(id: Long) = com.useinoski.gallerycleanup.model.MediaItem(
        id = id,
        uri = "content://media/external/images/media/$id",
        type = MediaType.Image,
        dateAdded = 0,
        size = 1,
        path = null,
        mimeType = "image/jpeg",
    )
}
