package com.useinoski.gallerycleanup.ui.navigation

import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryDestinationTest {
    @Test
    fun onboardingComesBeforeAnyPermissionScreen() {
        val denied = LibraryAccess(
            images = PermissionState.Denied,
            videos = PermissionState.Denied,
            audio = PermissionState.Denied,
        )
        assertEquals(GalleryDestination.Onboarding, galleryDestination(showOnboarding = true, access = denied))
        assertEquals(GalleryDestination.Onboarding, galleryDestination(showOnboarding = true, access = null))
    }

    @Test
    fun noVisualGrantStaysOnThePermissionScreen() {
        val denied = LibraryAccess(
            images = PermissionState.PermanentlyDenied,
            videos = PermissionState.Denied,
            audio = PermissionState.Full,
        )
        val destination = galleryDestination(showOnboarding = false, access = denied)
        assertTrue(destination is GalleryDestination.Permission)
    }

    @Test
    fun partialVisualGrantOpensHome() {
        val partial = LibraryAccess(
            images = PermissionState.Partial,
            videos = PermissionState.Partial,
            audio = PermissionState.Denied,
        )
        val destination = galleryDestination(showOnboarding = false, access = partial)
        assertTrue(destination is GalleryDestination.Home)
    }

    @Test
    fun missingSnapshotIsLoadingRatherThanAPermissionPrompt() {
        assertEquals(GalleryDestination.Loading, galleryDestination(showOnboarding = false, access = null))
    }
}
