package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlNormalizerTest {
    @Test
    fun youtubeShortsIsNormalizedToWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=AbCdEf12345",
            MediaUrlNormalizer.normalizeYouTubeShortsUrl(
                "https://www.youtube.com/shorts/AbCdEf12345?si=share-token"
            )
        )
    }

    @Test
    fun normalYoutubeWatchUrlIsNotChanged() {
        val watchUrl = "https://m.youtube.com/watch?v=AbCdEf12345&t=12"
        assertEquals(watchUrl, MediaUrlNormalizer.normalizeYouTubeShortsUrl(watchUrl))
    }

    @Test
    fun urlParametersCannotSpoofYoutubeHostDetection() {
        val unrelated = "https://example.org/watch?redirect=youtube.com/shorts/AbCdEf12345"
        assertEquals(unrelated, MediaUrlNormalizer.normalizeYouTubeShortsUrl(unrelated))
        assertFalse(MediaUrlNormalizer.hostMatches(unrelated, "youtube.com"))
        assertFalse(MediaUrlNormalizer.hostMatches("ftp://youtube.com/shorts/AbCdEf12345", "youtube.com"))
    }

    /**
     * The jar is the user's session. Attaching it to a request sends that session to whoever owns
     * the host, so the only defensible rule is: it leaves the app when the switch in Settings says
     * so, and never over a non-HTTP scheme. This used to be a function that returned `true` for
     * every URL whatever the setting said, while its comment described a per-host policy no code
     * implemented.
     */
    @Test
    fun savedCookiesFollowTheSwitchAndTheScheme() {
        val http = "https://www.youtube.com/watch?v=AbCdEf12345"

        assertTrue(MediaUrlNormalizer.shouldAttachSavedCookies(http, settingEnabled = true))
        assertFalse(MediaUrlNormalizer.shouldAttachSavedCookies(http, settingEnabled = false))
        assertFalse(
            "a file or jar URL must never be handed a session",
            MediaUrlNormalizer.shouldAttachSavedCookies("file:///sdcard/Download/x.mp4", settingEnabled = true)
        )
        assertTrue(MediaUrlNormalizer.shouldAttachSavedCookies("http://example.org/v.mp4", settingEnabled = true))
    }

    @Test
    fun theAdvertisedHostsAreTheOnesTheCodeKnows() {
        // The list is exposed as data so the settings screen, the docs and the extractor arguments
        // cannot each keep their own copy of "which sites does OTTER support".
        val hosts = MediaUrlNormalizer.supportedHosts

        listOf("youtube.com", "instagram.com", "tiktok.com", "facebook.com", "x.com", "reddit.com")
            .forEach { assertTrue("missing $it", hosts.contains(it)) }
        assertTrue(MediaUrlNormalizer.hostMatches("https://m.youtube.com/watch?v=x", "youtube.com"))
        assertFalse(MediaUrlNormalizer.hostMatches("https://youtube.com.evil.example/", "youtube.com"))
    }

    @Test
    fun directImageDetectionUsesPathAndIgnoresSignedQuery() {
        assertTrue(MediaUrlNormalizer.isDirectImageUrl("https://cdn.example.org/photo.webp?token=a%2Fb&expires=123"))
        assertFalse(MediaUrlNormalizer.isDirectImageUrl("https://cdn.example.org/video.mp4?preview=photo.jpg"))
        assertFalse(MediaUrlNormalizer.isDirectImageUrl("file:///tmp/photo.jpg"))
        assertEquals("webp", MediaUrlNormalizer.imageExtensionFromUrl("https://cdn.example.org/photo.WEBP?token=x"))
    }
}
