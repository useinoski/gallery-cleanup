package com.useinoski.gallerycleanup.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableLibraryTest {
    @Test
    fun partialPhotoAccessIsScannableAndAudioWaitsForTheOptIn() {
        val library = ReadableLibrary(
            sdkInt = 34,
            granted = {
                setOf(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED)
            },
            audioEnabled = { false },
        )
        val readable = library.current()
        assertTrue(readable.images)
        assertTrue(readable.videos)
        assertFalse(readable.audio)
    }

    @Test
    fun audioPermissionWithoutTheOptInIsNotScanned() {
        var optedIn = false
        val library = ReadableLibrary(
            sdkInt = 34,
            granted = {
                setOf(
                    MediaPermissions.READ_MEDIA_IMAGES,
                    MediaPermissions.READ_MEDIA_AUDIO,
                )
            },
            audioEnabled = { optedIn },
        )
        assertFalse(library.current().audio)
        assertFalse(library.current().videos)
        assertTrue(library.current().images)
        optedIn = true
        assertTrue(library.current().audio)
    }

    @Test
    fun android11StorageGrantStillHidesAudioUntilTheOptIn() {
        val library = ReadableLibrary(
            sdkInt = 30,
            granted = { setOf(MediaPermissions.READ_EXTERNAL_STORAGE) },
            audioEnabled = { false },
        )
        val readable = library.current()
        assertTrue(readable.images)
        assertTrue(readable.videos)
        assertFalse(readable.audio)
    }

    @Test
    fun aRevokedGrantIsRereadInsteadOfCached() {
        val granted = mutableSetOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VIDEO)
        val library = ReadableLibrary(sdkInt = 34, granted = { granted }, audioEnabled = { false })
        assertTrue(library.current().any())
        granted.clear()
        assertFalse(library.current().any())
    }
}
