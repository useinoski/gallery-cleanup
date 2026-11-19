package app.hearth.ui.navigation

import app.hearth.model.LibraryAccess
import app.hearth.model.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HearthDestinationTest {
    @Test
    fun onboardingComesBeforeAnyPermissionScreen() {
        val denied = LibraryAccess(
            images = PermissionState.Denied,
            videos = PermissionState.Denied,
            audio = PermissionState.Denied,
        )
        assertEquals(HearthDestination.Onboarding, hearthDestination(showOnboarding = true, access = denied))
        assertEquals(HearthDestination.Onboarding, hearthDestination(showOnboarding = true, access = null))
    }

    @Test
    fun noVisualGrantStaysOnThePermissionScreen() {
        val denied = LibraryAccess(
            images = PermissionState.PermanentlyDenied,
            videos = PermissionState.Denied,
            audio = PermissionState.Full,
        )
        val destination = hearthDestination(showOnboarding = false, access = denied)
        assertTrue(destination is HearthDestination.Permission)
    }

    @Test
    fun partialVisualGrantOpensHome() {
        val partial = LibraryAccess(
            images = PermissionState.Partial,
            videos = PermissionState.Partial,
            audio = PermissionState.Denied,
        )
        val destination = hearthDestination(showOnboarding = false, access = partial)
        assertTrue(destination is HearthDestination.Home)
    }

    @Test
    fun missingSnapshotIsLoadingRatherThanAPermissionPrompt() {
        assertEquals(HearthDestination.Loading, hearthDestination(showOnboarding = false, access = null))
    }
}
