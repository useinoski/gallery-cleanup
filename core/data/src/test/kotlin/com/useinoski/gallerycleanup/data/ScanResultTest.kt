package com.useinoski.gallerycleanup.data

import com.useinoski.gallerycleanup.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScanResultTest {
    @Test
    fun keyPairsTheIdWithTheLibrary() {
        val result = sample(id = 5, type = MediaType.Image)
        assertEquals(MediaKey(5, MediaType.Image), result.key)
    }

    @Test
    fun theSameIdInTwoLibrariesIsTwoKeys() {
        assertNotEquals(
            MediaKey(5, MediaType.Image),
            MediaKey(5, MediaType.Video),
        )
    }

    @Test
    fun aNewResultDefaultsToPending() {
        assertEquals(ReviewStatus.Pending, sample(id = 1).reviewStatus)
    }

    @Test
    fun aBlankUriIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(id = 1, uri = "  ")
        }
    }

    @Test
    fun aNonPositiveIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(id = 0)
        }
    }

    @Test
    fun aNonFiniteConfidenceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(id = 1, confidence = Float.NaN)
        }
    }

    @Test
    fun aBlankModelVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            sample(id = 1, modelVersion = " ")
        }
    }
}
