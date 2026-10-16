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

    @Test
    fun publicSocialExtractorsDoNotReceiveSavedCookies() {
        val publicUrls = listOf(
            "https://www.youtube.com/watch?v=AbCdEf12345",
            "https://www.instagram.com/p/AbCdEf/",
            "https://www.tiktok.com/@user/video/123456",
            "https://www.facebook.com/watch/?v=123456",
            "https://x.com/user/status/123",
            "https://www.reddit.com/r/example/comments/123"
        )

        publicUrls.forEach { assertFalse(MediaUrlNormalizer.shouldUseOptionalCookies(it)) }
        assertTrue(MediaUrlNormalizer.shouldUseOptionalCookies("https://example.org/video"))
    }

    @Test
    fun directImageDetectionUsesPathAndIgnoresSignedQuery() {
        assertTrue(MediaUrlNormalizer.isDirectImageUrl("https://cdn.example.org/photo.webp?token=a%2Fb&expires=123"))
        assertFalse(MediaUrlNormalizer.isDirectImageUrl("https://cdn.example.org/video.mp4?preview=photo.jpg"))
        assertFalse(MediaUrlNormalizer.isDirectImageUrl("file:///tmp/photo.jpg"))
        assertEquals("webp", MediaUrlNormalizer.imageExtensionFromUrl("https://cdn.example.org/photo.WEBP?token=x"))
    }
}
