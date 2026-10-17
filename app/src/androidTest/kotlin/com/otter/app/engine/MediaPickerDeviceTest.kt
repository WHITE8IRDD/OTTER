package com.otter.app.engine

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.otter.app.data.model.CarouselItem
import com.otter.app.share.QuickDownloadUiState
import com.otter.app.share.QuickDownloadWindow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPickerDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun show(items: List<CarouselItem>, onDismiss: () -> Unit = {}, onToggle: (Int) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                QuickDownloadWindow(
                    QuickDownloadUiState.Ready("Synthetic post", "Instagram", items, items.indices.toSet(), false, items.size, false),
                    onToggle, {}, {}, {}, {}, {}, onDismiss
                )
            }
        }
    }

    @Test fun mixedPostHasTwoSectionsAndDownloadAction() {
        show(listOf(CarouselItem("", false), CarouselItem("", true), CarouselItem("", false), CarouselItem("", true), CarouselItem("", false)))
        compose.onNodeWithText("Pictures (3)").assertExists()
        compose.onNodeWithTag("quick-media-grid").performScrollToNode(hasText("Videos (2)"))
        compose.onNodeWithText("Videos (2)").assertIsDisplayed()
        compose.onNodeWithText("Download Selected (5)").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun picturesOnlyDoesNotShowEmptyVideosSection() {
        show(listOf(CarouselItem("", false)))
        compose.onNodeWithText("Pictures (1)").assertIsDisplayed()
        compose.onNodeWithText("Videos (0)").assertDoesNotExist()
    }

    @Test fun tappingInsideCardDoesNotDismissButBackdropDoes() {
        var dismissed = 0
        show(listOf(CarouselItem("", false)), onDismiss = { dismissed++ })
        compose.onNodeWithText("Synthetic post").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onNodeWithTag("quick-download-backdrop").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun groupingKeepsOriginalSlideIndexWhenToggling() {
        var toggled = -1
        show(listOf(CarouselItem("", true), CarouselItem("", false))) { toggled = it }
        // Pictures is shown first but still represents item 2 of the original post.
        compose.onNodeWithText("2").performClick()
        compose.runOnIdle { assertEquals(1, toggled) }
    }
}
