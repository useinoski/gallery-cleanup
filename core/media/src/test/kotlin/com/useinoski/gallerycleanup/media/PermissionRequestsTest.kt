package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestsTest {
    private val none = LibraryAccess(
        images = PermissionState.Denied,
        videos = PermissionState.Denied,
        audio = PermissionState.Denied,
    )

    @Test
    fun photoRequestOnAndroid14IncludesPartialPermissionAndNotAudio() {
        val permissions = permissionsToRequest(34, none, AccessRequest.PhotosAndVideos)
        assertEquals(
            listOf(
                MediaPermissions.READ_MEDIA_IMAGES,
                MediaPermissions.READ_MEDIA_VIDEO,
                MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            permissions,
        )
        assertFalse(permissions.contains(MediaPermissions.READ_MEDIA_AUDIO))
    }

    @Test
    fun photoRequestOnAndroid13DoesNotAskForPartialAccess() {
        val permissions = permissionsToRequest(33, none, AccessRequest.PhotosAndVideos)
        assertEquals(
            listOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VIDEO),
            permissions,
        )
    }

    @Test
    fun photoRequestOnAndroid12IsTheLegacyStoragePermission() {
        assertEquals(
            listOf(MediaPermissions.READ_EXTERNAL_STORAGE),
            permissionsToRequest(31, none, AccessRequest.PhotosAndVideos),
        )
    }

    @Test
    fun selectMoreAsksOnlyForWhatIsNotAlreadyFull() {
        val access = LibraryAccess(
            images = PermissionState.Full,
            videos = PermissionState.Partial,
            audio = PermissionState.Denied,
        )
        assertEquals(
            listOf(
                MediaPermissions.READ_MEDIA_VIDEO,
                MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
            permissionsToRequest(34, access, AccessRequest.SelectMore),
        )
    }

    @Test
    fun selectMoreIsEmptyWhenBothVisualGrantsAreFull() {
        val access = LibraryAccess(
            images = PermissionState.Full,
            videos = PermissionState.Full,
            audio = PermissionState.Denied,
        )
        assertTrue(permissionsToRequest(36, access, AccessRequest.SelectMore).isEmpty())
    }

    @Test
    fun audioRequestIsOnlyTheAudioPermission() {
        val permissions = permissionsToRequest(34, none, AccessRequest.Audio)
        assertEquals(listOf(MediaPermissions.READ_MEDIA_AUDIO), permissions)
    }

    @Test
    fun audioRequestOnAndroid12DoesNotRaiseASecondStorageDialog() {
        assertTrue(permissionsToRequest(32, none, AccessRequest.Audio).isEmpty())
    }

    @Test
    fun turningAudioOnAsksPermissionOnlyFromAndroid13() {
        val ask = audioScanRequest(33, none)
        assertEquals(
            AudioScanRequest.AskPermission(listOf(MediaPermissions.READ_MEDIA_AUDIO)),
            ask,
        )
        assertEquals(AudioScanRequest.NeedPhotoAccessFirst, audioScanRequest(30, none))
    }

    @Test
    fun audioThatIsAlreadyGrantedJustOptsIn() {
        val access = none.copy(audio = PermissionState.Full)
        assertEquals(AudioScanRequest.AlreadyAllowed, audioScanRequest(32, access))
        assertEquals(AudioScanRequest.AlreadyAllowed, audioScanRequest(34, access))
    }

    @Test
    fun permanentlyDeniedAudioOpensSettings() {
        val access = none.copy(audio = PermissionState.PermanentlyDenied)
        assertEquals(AudioScanRequest.OpenSettings, audioScanRequest(34, access))
    }
}
