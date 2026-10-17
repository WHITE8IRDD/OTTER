package com.otter.app.engine

import org.junit.Assert.*
import org.junit.Test

class PlatformExtractionPolicyTest {
    @Test fun tiktokFormatsAndDownloadsUseOneIdentityForShortAndFullLinks() {
        val expected = PlatformExtractionPolicy.options("https://www.tiktok.com/@owner/video/123")
        for (url in listOf("https://vm.tiktok.com/id/", "https://vt.tiktok.com/id/", "https://www.tiktok.com/t/id/")) {
            assertEquals(expected, PlatformExtractionPolicy.options(url))
        }
        assertTrue(expected.contains("--user-agent" to MediaUrlNormalizer.TIKTOK_USER_AGENT))
    }
    @Test fun youtubeUsesMaintainedEngineClientDefaultsForShortsAndWatchLinks() {
        for (url in listOf("https://youtube.com/shorts/BaW_jenozKc", "https://youtube.com/watch?v=BaW_jenozKc")) {
            assertTrue(PlatformExtractionPolicy.options(url).none { it.first == "--extractor-args" })
        }
    }
    @Test fun hostSpoofCannotReceivePlatformHeaders() {
        assertTrue(PlatformExtractionPolicy.options("https://tiktok.com.evil.test/a").isEmpty())
    }
}
