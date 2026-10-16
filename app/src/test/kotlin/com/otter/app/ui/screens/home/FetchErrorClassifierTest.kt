package com.otter.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The error text a user sees has to name the real cause.
 *
 * Every message in [these] cases is copied from a failure that actually came back
 * from the live site while testing on a device, so the mapping stays honest as the
 * extractor wording changes.
 */
class FetchErrorClassifierTest {

    private val youtubeBotCheck =
        "ERROR: [youtube] aqz-KE-bpKQ: Sign in to confirm you're not a bot. Use --cookies-from-browser " +
            "or --cookies for the authentication. See https://github.com/yt-dlp/yt-dlp/wiki/FAQ"

    private val tiktokBlocked =
        "ERROR: [TikTok] 7693455561546829087: Video not available, status code 0; please report this " +
            "issue on https://github.com/yt-dlp/yt-dlp/issues"

    @Test
    fun `bot challenge is not reported as a login or age restriction`() {
        val kind = FetchErrorClassifier.classify(
            youtubeBotCheck,
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        )
        // The old mapping matched "Sign in" and told the user to add cookies for an
        // age restriction the video never had.
        assertEquals(FetchErrorClassifier.Kind.BOT_CHALLENGE, kind)
    }

    @Test
    fun `bot challenge with a curly apostrophe is still detected`() {
        val kind = FetchErrorClassifier.classify(
            "ERROR: [youtube] abc: Sign in to confirm you\u2019re not a bot.",
            "https://www.youtube.com/watch?v=abc"
        )
        assertEquals(FetchErrorClassifier.Kind.BOT_CHALLENGE, kind)
    }

    @Test
    fun `unusual traffic wording is a bot challenge`() {
        assertEquals(
            FetchErrorClassifier.Kind.BOT_CHALLENGE,
            FetchErrorClassifier.classify(
                "We have detected unusual traffic from your computer network",
                "https://www.youtube.com/watch?v=abc"
            )
        )
    }

    @Test
    fun `a real age restriction still asks for cookies`() {
        assertEquals(
            FetchErrorClassifier.Kind.AUTH_REQUIRED,
            FetchErrorClassifier.classify(
                "ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate.",
                "https://www.youtube.com/watch?v=abc"
            )
        )
    }

    @Test
    fun `tiktok status code 0 is a source restriction, not a missing video`() {
        val kind = FetchErrorClassifier.classify(
            tiktokBlocked,
            "https://www.tiktok.com/@tiktok_host1/video/7693455561546829087"
        )
        assertEquals(FetchErrorClassifier.Kind.SOURCE_RESTRICTED, kind)
    }

    @Test
    fun `genuinely missing videos are still reported as unavailable`() {
        assertEquals(
            FetchErrorClassifier.Kind.VIDEO_UNAVAILABLE,
            FetchErrorClassifier.classify(
                "ERROR: [youtube] abc: Video unavailable",
                "https://www.youtube.com/watch?v=abc"
            )
        )
    }

    @Test
    fun `network failures keep their own messages`() {
        assertEquals(
            FetchErrorClassifier.Kind.NETWORK_TIMEOUT,
            FetchErrorClassifier.classify(
                "ERROR: unable to download video data: <urlopen error timed out>",
                "https://example.com/video"
            )
        )
        assertEquals(
            FetchErrorClassifier.Kind.NETWORK_TIMEOUT,
            FetchErrorClassifier.classify(
                "java.net.SocketTimeoutException: Read timed out",
                "https://example.com/video"
            )
        )
        assertEquals(
            FetchErrorClassifier.Kind.NO_INTERNET,
            FetchErrorClassifier.classify(
                "java.net.UnknownHostException: Unable to resolve host \"example.com\"",
                "https://example.com/video"
            )
        )
    }

    @Test
    fun `a DNS failure worded the way python reports it stays generic`() {
        // Documented behaviour: only the host-resolution wording above is treated as
        // "no internet"; anything else falls through to the generic message rather
        // than claiming a specific cause the app has not established.
        assertEquals(
            FetchErrorClassifier.Kind.GENERIC,
            FetchErrorClassifier.classify(
                "<urlopen error [Errno -3] Temporary failure in name resolution>",
                "https://example.com/video"
            )
        )
    }

    @Test
    fun `platform detection drives the platform specific messages`() {
        assertEquals(FetchErrorClassifier.PLATFORM_TIKTOK, FetchErrorClassifier.detectPlatform("https://www.tiktok.com/@a/video/1"))
        assertEquals(FetchErrorClassifier.PLATFORM_INSTAGRAM, FetchErrorClassifier.detectPlatform("https://www.instagram.com/p/x/"))
        assertEquals(FetchErrorClassifier.PLATFORM_FACEBOOK, FetchErrorClassifier.detectPlatform("https://www.facebook.com/reel/1"))
        assertEquals(FetchErrorClassifier.PLATFORM_YOUTUBE, FetchErrorClassifier.detectPlatform("https://youtu.be/aqz-KE-bpKQ"))
        assertEquals(FetchErrorClassifier.PLATFORM_OTHER, FetchErrorClassifier.detectPlatform("https://example.com/a.mp3"))
    }

    @Test
    fun `per platform login errors are unchanged`() {
        assertEquals(
            FetchErrorClassifier.Kind.INSTAGRAM_LOGIN,
            FetchErrorClassifier.classify(
                "ERROR: [Instagram] x: Please login to view this post",
                "https://www.instagram.com/p/x/"
            )
        )
        assertEquals(
            FetchErrorClassifier.Kind.TWITTER_RATE_LIMIT,
            FetchErrorClassifier.classify(
                "ERROR: [Twitter] rate limit exceeded",
                "https://x.com/a/status/1"
            )
        )
    }

    /**
     * Three causes that used to be reported as a generic "couldn't process this link", each of which
     * has a different fix: free space, an engine that is older than the site, or a page with nothing
     * in it to save.
     */
    @Test
    fun `a full device is named as the cause`() {
        listOf(
            "ERROR: unable to write data: [Errno 28] No space left on device",
            "java.io.IOException: not enough free space",
            "enospc while writing to /storage/emulated/0/Download"
        ).forEach { message ->
            assertEquals(
                "for: $message",
                FetchErrorClassifier.Kind.INSUFFICIENT_STORAGE,
                FetchErrorClassifier.classify(message, "https://www.youtube.com/watch?v=x")
            )
        }
    }

    @Test
    fun `a page with nothing downloadable is not a network problem`() {
        assertEquals(
            FetchErrorClassifier.Kind.NO_DOWNLOADABLE_MEDIA,
            FetchErrorClassifier.classify(
                "ERROR: [Instagram] ABCd1234: no video formats found; the post is an image",
                FetchErrorClassifier.PLATFORM_INSTAGRAM
            )
        )
    }

    @Test
    fun `an extractor that cannot read the page is reported as such`() {
        assertEquals(
            FetchErrorClassifier.Kind.EXTRACTION_FAILED,
            FetchErrorClassifier.classify(
                "ERROR: [facebook] Unable to extract video url; please report this issue",
                FetchErrorClassifier.PLATFORM_FACEBOOK
            )
        )
    }

    @Test
    fun `unknown failures stay generic`() {
        assertEquals(
            FetchErrorClassifier.Kind.GENERIC,
            FetchErrorClassifier.classify("something unexpected happened", "https://example.com/x")
        )
    }
}
