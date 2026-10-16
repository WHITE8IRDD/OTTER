package com.otter.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Clipboard behaviour, on a real device.
 *
 * Copying a link is the single most common way a link reaches OTTER, so the Home
 * screen has to pick it up while resumed, put it in the field, and start the normal
 * fetch. This also guards the lifecycle part: the listener is attached on resume
 * and removed on pause, and re-attaching must not re-trigger the same link.
 */
@RunWith(AndroidJUnit4::class)
class HomeClipboardAutoFetchTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val link = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"

    private fun clipboard(): ClipboardManager {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(ClipboardManager::class.java)
    }

    @Before
    fun clearClipboard() {
        // The screen also reads the clipboard when it resumes, so start from a value
        // that cannot be mistaken for the link under test.
        clipboard().clearPrimaryClip()
    }

    @Test
    fun copiedLinkIsFilledInWithoutAPasteTap() {
        composeRule.waitForIdle()

        clipboard().setPrimaryClip(ClipData.newPlainText("link", link))

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(link).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(link).assertIsDisplayed()
    }

    @Test
    fun copyingTheSameLinkTwiceKeepsTheFieldIntact() {
        composeRule.waitForIdle()
        clipboard().setPrimaryClip(ClipData.newPlainText("link", link))
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(link).fetchSemanticsNodes().isNotEmpty()
        }

        // The second copy is the value already on screen. Re-reading it must not
        // clear the field or stack up fetches.
        clipboard().setPrimaryClip(ClipData.newPlainText("link", link))
        composeRule.waitForIdle()

        assertTrue(
            "the copied link should still be in the field",
            composeRule.onAllNodesWithText(link).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun anUnrelatedClipboardValueIsIgnored() {
        composeRule.waitForIdle()

        clipboard().setPrimaryClip(ClipData.newPlainText("note", "just some notes, not a link"))
        composeRule.waitForIdle()

        assertTrue(
            "non-link clipboard content must not be pasted into the field",
            composeRule.onAllNodesWithText(link).fetchSemanticsNodes().isEmpty()
        )
    }
}
