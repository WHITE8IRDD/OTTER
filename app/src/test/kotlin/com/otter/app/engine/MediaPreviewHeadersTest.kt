package com.otter.app.engine

import org.junit.Assert.*
import org.junit.Test

class MediaPreviewHeadersTest {
    @Test fun instagramPostersUsePublicReferer() {
        assertEquals("https://www.instagram.com/", MediaPreviewHeaders.forUrl("https://scontent.cdninstagram.com/preview.jpg")["Referer"])
    }
    @Test fun unrelatedOrLookalikeHostsReceiveNoPlatformHeaders() {
        for (url in listOf("https://example.com/p.jpg", "https://fbcdn.net.evil.test/p.jpg")) {
            assertTrue(MediaPreviewHeaders.forUrl(url).isEmpty())
        }
    }
}
