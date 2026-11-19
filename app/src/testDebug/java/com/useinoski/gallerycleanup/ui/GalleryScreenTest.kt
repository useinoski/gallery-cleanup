package com.useinoski.gallerycleanup.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.PermissionState
import com.useinoski.gallerycleanup.ui.home.HomeScreen
import com.useinoski.gallerycleanup.ui.onboarding.OnboardingScreen
import com.useinoski.gallerycleanup.ui.permission.PermissionGate
import com.useinoski.gallerycleanup.ui.theme.GalleryTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun onboardingShowsThePrivacyPromiseAndDoesNotAskForAccess() {
        var continued = false
        compose.setContent {
            GalleryTheme {
                OnboardingScreen(onContinue = { continued = true })
            }
        }
        compose.onNodeWithText("Your photos never leave your phone. No account, no uploads.").assertIsDisplayed()
        compose.onAllNodesWithText("Allow photos and videos").assertCountEquals(0)
        compose.onNodeWithText("Continue").performClick()
        assertTrue(continued)
    }

    @Test
    fun partialAccessOffersSelectMoreAndAllowAll() {
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Partial,
                        videos = PermissionState.Partial,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = 2, videos = 0, music = null, otherAudio = null),
                    audioScanningEnabled = false,
                    loading = false,
                    failed = false,
                    sdkInt = 34,
                    onSelectMore = {},
                    onAllowAll = {},
                    onAllowImages = {},
                    onAllowVideos = {},
                    onTurnOnAudio = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Only the photos and videos you selected are visible. These counts leave out the rest of the library.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Select more").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow all").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn on audio scanning").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun permanentlyDeniedPermissionOffersSettings() {
        compose.setContent {
            GalleryTheme {
                PermissionGate(
                    access = LibraryAccess(
                        images = PermissionState.PermanentlyDenied,
                        videos = PermissionState.PermanentlyDenied,
                        audio = PermissionState.Denied,
                    ),
                    onAllowPhotos = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Open Settings").assertIsDisplayed()
        compose.onAllNodesWithText("Allow photos and videos").assertCountEquals(0)
        compose.onNodeWithText("Photo and video access is blocked. Gallery Cleanup cannot count anything until you allow it in Settings. Nothing is uploaded either way.")
            .assertIsDisplayed()
    }

    @Test
    fun enabledAudioShowsMusicAndOtherCounts() {
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Full,
                        videos = PermissionState.Full,
                        audio = PermissionState.Full,
                    ),
                    counts = MediaCounts(images = 4, videos = 1, music = 30, otherAudio = 6),
                    audioScanningEnabled = true,
                    loading = false,
                    failed = false,
                    sdkInt = 34,
                    onSelectMore = {},
                    onAllowAll = {},
                    onAllowImages = {},
                    onAllowVideos = {},
                    onTurnOnAudio = {},
                    onOpenSettings = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("30 music · 6 other").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Turn on audio scanning").assertCountEquals(0)
    }
}
