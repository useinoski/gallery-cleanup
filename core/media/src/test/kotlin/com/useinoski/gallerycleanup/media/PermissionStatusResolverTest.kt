package com.useinoski.gallerycleanup.media

import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStatusResolverTest {
    @Test
    fun android12_storageGrantCoversImagesVideoAndAudio() {
        val access = resolve(sdk = 32, granted = setOf(MediaPermissions.READ_EXTERNAL_STORAGE))
        assertEquals(PermissionState.Full, access.images)
        assertEquals(PermissionState.Full, access.videos)
        assertEquals(PermissionState.Full, access.audio)
    }

    @Test
    fun android12_neverAskedIsDeniedRatherThanPermanent() {
        val access = resolve(sdk = 31, granted = emptySet(), asked = emptySet())
        assertEquals(PermissionState.Denied, access.images)
        assertEquals(PermissionState.Denied, access.audio)
    }

    @Test
    fun android12_secondDenialWithoutRationaleIsPermanent() {
        val access = resolve(
            sdk = 30,
            granted = emptySet(),
            asked = setOf(MediaPermissions.READ_EXTERNAL_STORAGE),
            rationale = emptySet(),
        )
        assertEquals(PermissionState.PermanentlyDenied, access.images)
        assertEquals(PermissionState.PermanentlyDenied, access.videos)
        assertEquals(PermissionState.PermanentlyDenied, access.audio)
    }

    @Test
    fun android12_rationaleStillMeansTheDialogCanBeShown() {
        val access = resolve(
            sdk = 32,
            granted = emptySet(),
            asked = setOf(MediaPermissions.READ_EXTERNAL_STORAGE),
            rationale = setOf(MediaPermissions.READ_EXTERNAL_STORAGE),
        )
        assertEquals(PermissionState.Denied, access.images)
    }

    @Test
    fun android13_grantsAreIndependentAndPartialDoesNotExist() {
        val access = resolve(
            sdk = 33,
            granted = setOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED),
            asked = setOf(MediaPermissions.READ_MEDIA_VIDEO, MediaPermissions.READ_MEDIA_AUDIO),
            rationale = setOf(MediaPermissions.READ_MEDIA_VIDEO),
        )
        assertEquals(PermissionState.Full, access.images)
        assertEquals(PermissionState.Denied, access.videos)
        assertEquals(PermissionState.PermanentlyDenied, access.audio)
    }

    @Test
    fun android14_selectedPhotosArePartialEvenWhenRationaleIsFalse() {
        val access = resolve(
            sdk = 34,
            granted = setOf(MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED),
            asked = setOf(MediaPermissions.READ_MEDIA_IMAGES, MediaPermissions.READ_MEDIA_VIDEO),
            rationale = emptySet(),
        )
        assertEquals(PermissionState.Partial, access.images)
        assertEquals(PermissionState.Partial, access.videos)
        assertEquals(PermissionState.Denied, access.audio)
        assertTrue(access.visualIsPartial)
    }

    @Test
    fun android14_fullImagesAndSelectedVideosStayDistinct() {
        val access = resolve(
            sdk = 34,
            granted = setOf(
                MediaPermissions.READ_MEDIA_IMAGES,
                MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED,
            ),
        )
        assertEquals(PermissionState.Full, access.images)
        assertEquals(PermissionState.Partial, access.videos)
    }

    @Test
    fun android14_fullGrantWinsOverTheSelectedPermission() {
        val access = resolve(
            sdk = 36,
            granted = setOf(
                MediaPermissions.READ_MEDIA_IMAGES,
                MediaPermissions.READ_MEDIA_VIDEO,
                MediaPermissions.READ_MEDIA_VISUAL_USER_SELECTED,
                MediaPermissions.READ_MEDIA_AUDIO,
            ),
        )
        assertEquals(PermissionState.Full, access.images)
        assertEquals(PermissionState.Full, access.videos)
        assertEquals(PermissionState.Full, access.audio)
        assertFalse(access.visualIsPartial)
    }

    @Test
    fun android14_deniedVisualWithNoSelectionCanBePermanent() {
        val access = resolve(
            sdk = 35,
            granted = emptySet(),
            asked = setOf(
                MediaPermissions.READ_MEDIA_IMAGES,
                MediaPermissions.READ_MEDIA_VIDEO,
                MediaPermissions.READ_MEDIA_AUDIO,
            ),
        )
        assertEquals(PermissionState.PermanentlyDenied, access.images)
        assertEquals(PermissionState.PermanentlyDenied, access.videos)
        assertEquals(PermissionState.PermanentlyDenied, access.audio)
    }

    private fun resolve(
        sdk: Int,
        granted: Set<String>,
        asked: Set<String> = granted,
        rationale: Set<String> = emptySet(),
    ): LibraryAccess {
        return PermissionStatusResolver.resolve(
            sdkInt = sdk,
            granted = granted,
            shouldShowRationale = { permission -> permission in rationale },
            wasAsked = { permission -> permission in asked },
        )
    }
}
