package com.useinoski.gallerycleanup.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.useinoski.gallerycleanup.data.CategoryCounts
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.media.MediaCounts
import com.useinoski.gallerycleanup.model.LibraryAccess
import com.useinoski.gallerycleanup.model.MediaType
import com.useinoski.gallerycleanup.model.PermissionState
import com.useinoski.gallerycleanup.model.ScanPhase
import com.useinoski.gallerycleanup.model.ScanProgress
import com.useinoski.gallerycleanup.model.ScanRun
import com.useinoski.gallerycleanup.model.TypeProgress
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
                    scan = ScanProgress.from(ScanRun.IDLE),
                    onStartScan = {},
                    onCancelScan = {},
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
                    scan = ScanProgress.from(ScanRun.IDLE),
                    onStartScan = {},
                    onCancelScan = {},
                )
            }
        }
        compose.onNodeWithText("30 music · 6 other").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Turn on audio scanning").assertCountEquals(0)
    }

    @Test
    fun runningScanShowsProgressPerTypeAndCancel() {
        var cancelled = false
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Full,
                        videos = PermissionState.Full,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = 40, videos = 2, music = null, otherAudio = null),
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
                    scan = ScanProgress(
                        phase = ScanPhase.Running,
                        byType = listOf(
                            TypeProgress(MediaType.Image, scanned = 4, total = 10),
                            TypeProgress(MediaType.Video, scanned = 0, total = 2),
                        ),
                        detail = null,
                    ),
                    onStartScan = {},
                    onCancelScan = { cancelled = true },
                )
            }
        }
        compose.onNodeWithText("4 of 12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Photos · 4 of 10").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Videos · 0 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel scan").performScrollTo().performClick()
        assertTrue(cancelled)
    }

    @Test
    fun idleScanStartsAndAFinishedScanOffersAnotherPass() {
        var started = 0
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Full,
                        videos = PermissionState.Denied,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = 1, videos = null, music = null, otherAudio = null),
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
                    scan = ScanProgress.from(ScanRun.IDLE),
                    onStartScan = { started += 1 },
                    onCancelScan = {},
                )
            }
        }
        compose.onNodeWithText("Start scan").performScrollTo().performClick()
        assertTrue(started == 1)
        compose.onAllNodesWithText("Cancel scan").assertCountEquals(0)
        compose.onAllNodesWithText("Nudity: 0").assertCountEquals(0)
    }

    @Test
    fun aFinishedScanShowsOneCountPerFlaggedCategory() {
        val flagged = CategoryCounts(
            pending = ScanCategory.entries.associateWith { category ->
                when (category) {
                    ScanCategory.Sensitive -> 2
                    ScanCategory.People -> 5
                    ScanCategory.Video -> 1
                    else -> 0
                }
            },
        )
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Full,
                        videos = PermissionState.Full,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = 20, videos = 4, music = null, otherAudio = null),
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
                    scan = ScanProgress(phase = ScanPhase.Completed, byType = emptyList(), detail = null),
                    onStartScan = {},
                    onCancelScan = {},
                    flaggedCounts = flagged,
                )
            }
        }
        compose.onNodeWithText("Nudity: 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("People: 5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Videos: 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Suggestive: 0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Screenshots: 0").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Unflagged").assertCountEquals(0)
    }

    @Test
    fun accessRevokedTellsTheUserToGrantAccessAgain() {
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Denied,
                        videos = PermissionState.Denied,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = null, videos = null, music = null, otherAudio = null),
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
                    scan = ScanProgress(
                        phase = ScanPhase.AccessRevoked,
                        byType = emptyList(),
                        detail = "Media access was removed — grant access to continue scanning",
                    ),
                    onStartScan = {},
                    onCancelScan = {},
                )
            }
        }
        compose.onNodeWithText("Media access was removed — grant access to continue scanning")
            .assertIsDisplayed()
    }

    @Test
    fun selectedPhotosDoesNotShowTheRevokedMessage() {
        compose.setContent {
            GalleryTheme {
                HomeScreen(
                    access = LibraryAccess(
                        images = PermissionState.Partial,
                        videos = PermissionState.Partial,
                        audio = PermissionState.Denied,
                    ),
                    counts = MediaCounts(images = 3, videos = 1, music = null, otherAudio = null),
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
                    scan = ScanProgress(phase = ScanPhase.Completed, byType = emptyList(), detail = null),
                    onStartScan = {},
                    onCancelScan = {},
                )
            }
        }
        compose.onNodeWithText("Scan finished. Nothing was deleted.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Media access was removed — grant access to continue scanning")
            .assertCountEquals(0)
        compose.onNodeWithText("Only the photos and videos you selected are visible. These counts leave out the rest of the library.")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
