package com.useinoski.gallerycleanup.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.useinoski.gallerycleanup.data.ScanCategory
import com.useinoski.gallerycleanup.model.MediaKey
import com.useinoski.gallerycleanup.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Debug unit tests, same place as the app's Compose tests. The Compose host
 * activity comes from the debug test manifest, so a release unit test cannot
 * launch it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var bitmap: Bitmap

    @Before
    fun setUp() {
        bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
    }

    @Test
    fun sensitiveItemsAreBlurredAndPlainItemsAreNot() {
        show(
            category = ScanCategory.Screenshot,
            items = listOf(
                item(id = 1, uri = "content://sensitive", sensitive = true),
                item(id = 2, uri = "content://plain", sensitive = false),
            ),
            sdkInt = 34,
        )
        compose.onNodeWithTag("review-item-Image-1").assert(privacy(ThumbnailPrivacy.Blurred))
        compose.onNodeWithTag("review-item-Image-2").assert(privacy(ThumbnailPrivacy.Clear))
        compose.onNodeWithText("Screenshots · 2").assertIsDisplayed()
    }

    @Test
    fun sensitiveItemsOnApi30UseTheObscuredFallback() {
        show(
            category = ScanCategory.Sensitive,
            items = listOf(
                item(id = 1, uri = "content://sensitive", sensitive = true),
                item(id = 2, uri = "content://plain", sensitive = false),
            ),
            sdkInt = 30,
        )
        compose.onNodeWithTag("review-item-Image-1").assert(privacy(ThumbnailPrivacy.Obscured))
        compose.onNodeWithTag("review-item-Image-2").assert(privacy(ThumbnailPrivacy.Clear))
    }

    @Test
    fun aPlainItemWithNoThumbnailShowsTheBrokenImageIcon() {
        show(
            category = ScanCategory.Screenshot,
            items = listOf(
                item(id = 3, uri = "content://plain-missing", sensitive = false),
                item(id = 4, uri = "content://sensitive-missing", sensitive = true),
                item(id = 5, uri = "content://plain-ok", sensitive = false),
            ),
            sdkInt = 34,
            loader = ThumbnailLoader { uri, _ ->
                if (uri.endsWith("ok")) bitmap else null
            },
        )
        compose.onNodeWithTag("review-item-Image-3").assert(privacy(ThumbnailPrivacy.Clear))
        compose.onNodeWithContentDescription("Image unavailable").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Image unavailable").assertCountEquals(1)
        compose.onNodeWithTag("review-item-Image-4").assert(privacy(ThumbnailPrivacy.Hidden))
        compose.onNodeWithContentDescription("Hidden photo").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Hidden photo").assertCountEquals(1)
        compose.onNodeWithTag("review-item-Image-5").assert(privacy(ThumbnailPrivacy.Clear))
    }

    @Test
    fun aSensitiveItemWithNoThumbnailStaysAPlaceholder() {
        show(
            category = ScanCategory.Sensitive,
            items = listOf(item(id = 4, uri = "content://missing", sensitive = true)),
            sdkInt = 34,
            loader = ThumbnailLoader { _, _ -> null },
        )
        compose.onNodeWithTag("review-item-Image-4").assert(privacy(ThumbnailPrivacy.Hidden))
        compose.onNodeWithContentDescription("Hidden photo").assertIsDisplayed()
    }

    @Test
    fun revealingASensitivePhotoClearsTheBlurUntilTheScreenIsOpenedAgain() {
        val sensitive = item(id = 7, uri = "content://sensitive", sensitive = true)
        val shown = mutableStateOf(true)
        compose.setContent {
            if (shown.value) {
                MaterialTheme {
                    ReviewScreen(
                        category = ScanCategory.Sensitive.toReviewCategory(),
                        state = ReviewUiState.Items(listOf(sensitive)),
                        onBack = {},
                        thumbnails = ThumbnailLoader { _, _ -> bitmap },
                        sdkInt = 34,
                    )
                }
            }
        }
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Blurred))
        compose.onNodeWithTag("review-item-Image-7").performClick()
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Clear))
        // Leaving the composition drops the reveal set. It is not saved.
        shown.value = false
        compose.waitForIdle()
        shown.value = true
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Blurred))
    }

    @Test
    fun systemBackCallsOnBack() {
        var backed = false
        lateinit var dispatcher: OnBackPressedDispatcher
        compose.setContent {
            dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            ReviewRoute(
                category = ScanCategory.Screenshot,
                onBack = { backed = true },
            )
        }
        compose.waitForIdle()
        dispatcher.onBackPressed()
        assertTrue(backed)
    }

    @Test
    fun anEmptyCategoryShowsZeroAndBackLeaves() {
        var left = false
        compose.setContent {
            MaterialTheme {
                ReviewScreen(
                    category = ScanCategory.People.toReviewCategory(),
                    state = ReviewUiState.Empty,
                    onBack = { left = true },
                    thumbnails = ThumbnailLoader { _, _ -> bitmap },
                )
            }
        }
        compose.onNodeWithText("People · 0").assertIsDisplayed()
        compose.onNodeWithText("Nothing waiting in this category.").assertIsDisplayed()
        compose.onAllNodesWithTag("review-item-Image-1").assertCountEquals(0)
        compose.onNodeWithText("Back").performClick()
        assertTrue(left)
    }

    @Test
    fun loadingHasNoCountYet() {
        compose.setContent {
            MaterialTheme {
                ReviewScreen(
                    category = ScanCategory.Sensitive.toReviewCategory(),
                    state = ReviewUiState.Loading,
                    onBack = {},
                    thumbnails = ThumbnailLoader { _, _ -> bitmap },
                )
            }
        }
        compose.onNodeWithText("Nudity").assertIsDisplayed()
        compose.onNodeWithText("Loading…").assertIsDisplayed()
        compose.onAllNodesWithText("Nudity · 0").assertCountEquals(0)
    }

    @Test
    fun longPressSelectsWithoutRevealingAndTapTogglesTheCheck() {
        val sensitive = item(id = 7, uri = "content://sensitive", sensitive = true)
        val selected = mutableStateOf(emptySet<String>())
        compose.setContent {
            MaterialTheme {
                ReviewScreen(
                    category = ScanCategory.Sensitive.toReviewCategory(),
                    state = ReviewUiState.Items(listOf(sensitive)),
                    onBack = {},
                    thumbnails = ThumbnailLoader { _, _ -> bitmap },
                    sdkInt = 34,
                    selectedKeys = selected.value,
                    onStartSelection = { cell -> selected.value = selected.value + cell.stableKey },
                    onToggleSelection = { cell ->
                        selected.value = if (cell.stableKey in selected.value) {
                            selected.value - cell.stableKey
                        } else {
                            selected.value + cell.stableKey
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("review-item-Image-7").performClick()
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Clear))
        compose.onNodeWithTag("review-item-Image-7").performTouchInput { longClick() }
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Blurred))
        compose.onNodeWithTag("review-item-Image-7").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        compose.onNodeWithTag("review-check-Image-7", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Select all").assertIsDisplayed()
        compose.onNodeWithText("Clear").assertIsDisplayed()
        compose.onNodeWithText("Keep").assertIsDisplayed()
        compose.onNodeWithTag("review-item-Image-7").performClick()
        compose.onAllNodesWithTag("review-check-Image-7", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("review-item-Image-7").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected),
        )
        compose.onNodeWithTag("review-item-Image-7").assert(privacy(ThumbnailPrivacy.Clear))
        compose.onNodeWithText("Nudity · 1").assertIsDisplayed()
    }

    @Test
    fun keptSnackbarOffersUndo() {
        var undone = false
        compose.setContent {
            MaterialTheme {
                ReviewScreen(
                    category = ScanCategory.Screenshot.toReviewCategory(),
                    state = ReviewUiState.Empty,
                    onBack = {},
                    thumbnails = ThumbnailLoader { _, _ -> bitmap },
                    keepNotice = ReviewNotice.Kept(
                        listOf(MediaKey(MediaType.Image, 1), MediaKey(MediaType.Image, 2)),
                    ),
                    onUndoKeep = { undone = true },
                )
            }
        }
        compose.onNodeWithText("2 kept").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertTrue(undone)
    }

    @Test
    fun reviewRouteBlocksScreenshotsUntilItLeaves() {
        val shown = mutableStateOf(true)
        lateinit var window: Window
        compose.setContent {
            window = (LocalActivity.current ?: LocalContext.current.findActivity()).window
            if (shown.value) {
                ReviewRoute(
                    category = ScanCategory.Screenshot,
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        shown.value = false
        compose.waitForIdle()
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun pixelateReplacesASingleBrightPixelWithABlock() {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        source.setPixel(0, 0, Color.WHITE)
        val obscured = pixelate(source, cells = 4)
        var light = 0
        for (y in 0 until obscured.height) {
            for (x in 0 until obscured.width) {
                if (obscured.getPixel(x, y) != Color.BLACK) light += 1
            }
        }
        assertTrue("a single white pixel should spread into a block, was $light", light > 1)
        assertTrue(obscured.width >= source.width)
        assertTrue(obscured.height >= source.height)
    }

    private fun show(
        category: ScanCategory,
        items: List<ReviewItem>,
        sdkInt: Int,
        loader: ThumbnailLoader = ThumbnailLoader { _, _ -> bitmap },
    ) {
        compose.setContent {
            MaterialTheme {
                ReviewScreen(
                    category = category.toReviewCategory(),
                    state = ReviewUiState.Items(items),
                    onBack = {},
                    thumbnails = loader,
                    sdkInt = sdkInt,
                )
            }
        }
    }

    private fun item(id: Long, uri: String, sensitive: Boolean) = ReviewItem(
        id = id,
        mediaType = MediaType.Image,
        contentUri = uri,
        sensitive = sensitive,
    )

    private fun privacy(expected: ThumbnailPrivacy) = SemanticsMatcher.expectValue(ThumbnailPrivacyKey, expected)
}

private fun Context.findActivity(): Activity {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    error("ReviewRoute is not hosted by an Activity")
}
