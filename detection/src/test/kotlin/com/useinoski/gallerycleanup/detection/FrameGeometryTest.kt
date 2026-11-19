package com.useinoski.gallerycleanup.detection

import com.useinoski.gallerycleanup.detection.image.centerSquareRect
import com.useinoski.gallerycleanup.detection.image.sizeCappedToLongSide
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGeometryTest {
    @Test
    fun centerSquareDropsTheLongSide() {
        val landscape = centerSquareRect(4000, 3000)
        assertEquals(500, landscape.left)
        assertEquals(0, landscape.top)
        assertEquals(3000, landscape.side)

        val portrait = centerSquareRect(3000, 4000)
        assertEquals(0, portrait.left)
        assertEquals(500, portrait.top)
        assertEquals(3000, portrait.side)
    }

    @Test
    fun centerSquareOfAnOddFrameRoundsTowardTheTopLeft() {
        val rect = centerSquareRect(5, 3)
        assertEquals(1, rect.left)
        assertEquals(0, rect.top)
        assertEquals(3, rect.side)
    }

    @Test
    fun alreadySquareIsUnchanged() {
        val rect = centerSquareRect(384, 384)
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(384, rect.side)
    }

    @Test
    fun longSideCapKeepsAspectRatio() {
        val landscape = sizeCappedToLongSide(4000, 3000, 1280)
        assertEquals(1280, landscape.width)
        assertEquals(960, landscape.height)
        val portrait = sizeCappedToLongSide(3000, 4000, 1280)
        assertEquals(960, portrait.width)
        assertEquals(1280, portrait.height)
    }

    @Test
    fun smallFramesAreNotUpscaled() {
        val size = sizeCappedToLongSide(800, 600, 1280)
        assertEquals(800, size.width)
        assertEquals(600, size.height)
    }
}
