package com.useinoski.gallerycleanup.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStateTest {
    @Test
    fun onlyFullAndPartialCanRead() {
        assertTrue(PermissionState.Full.canRead)
        assertTrue(PermissionState.Partial.canRead)
        assertFalse(PermissionState.Denied.canRead)
        assertFalse(PermissionState.PermanentlyDenied.canRead)
    }

    @Test
    fun partialVisualAccessIsNotTreatedAsTheWholeLibrary() {
        val access = LibraryAccess(
            images = PermissionState.Partial,
            videos = PermissionState.Full,
            audio = PermissionState.Denied,
        )
        assertTrue(access.anyVisual)
        assertTrue(access.visualIsPartial)
    }

    @Test
    fun mediaItemKeepsARelativePathAndAStringUri() {
        val item = MediaItem(
            id = 7,
            uri = "content://media/external/images/media/7",
            type = MediaType.Image,
            dateAdded = 1_700_000_000,
            size = 2048,
            path = "DCIM/Camera/IMG_0007.jpg",
            mimeType = "image/jpeg",
        )
        assertEquals(MediaType.Image, item.type)
        assertNull(item.copy(path = null).path)
    }
}
