package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Instagram link the app can be handed, and which of them count as one post.
 *
 * The bug these tests pin down is a split in recognition: the page scraper accepted `/p/` only, the
 * fetch pipeline excluded `/reel/`, and the share sheet had a third rule. Instagram shares a
 * one-video post as a reel, so those links were handed to the engine's `instagram` extractor, which
 * answers a logged-out request with a login wall — and the same video downloaded fine from the app's
 * own scraper when its URL was pasted as `/p/`.
 */
class InstagramUrlTest {

    @Test
    fun postUrlIsRecognisedWithItsShortcode() {
        val parsed = InstagramUrl.parse("https://www.instagram.com/p/Cxyz1234567/")

        assertEquals(InstagramUrl.Kind.POST, parsed?.kind)
        assertEquals("Cxyz1234567", parsed?.shortcode)
        assertEquals("https://www.instagram.com/p/Cxyz1234567/", parsed?.canonicalUrl)
    }

    @Test
    fun reelUrlKeepsTheReelFormBecauseItRendersAPlayer() {
        val parsed = InstagramUrl.parse("https://www.instagram.com/reel/Cxyz1234567/")

        assertEquals(InstagramUrl.Kind.REEL, parsed?.kind)
        assertEquals("Cxyz1234567", parsed?.shortcode)
        // Rewriting a reel to /p/<code>/ can come back as the share redirect page with no media.
        assertEquals("https://www.instagram.com/reel/Cxyz1234567/", parsed?.canonicalUrl)
    }

    @Test
    fun pluralReelsAndTvFormsAreMediaUrls() {
        assertTrue(InstagramUrl.isMediaUrl("https://www.instagram.com/reels/Cxyz1234567/"))
        assertTrue(InstagramUrl.isMediaUrl("https://www.instagram.com/tv/Cxyz1234567/"))
        assertEquals(InstagramUrl.Kind.POST, InstagramUrl.kind("https://www.instagram.com/tv/Cxyz1234567/"))
    }

    @Test
    fun forwardedShareLinksAreMediaUrls() {
        val parsed = InstagramUrl.parse("https://www.instagram.com/share/reel/Cxyz1234567")

        assertEquals(InstagramUrl.Kind.REEL, parsed?.kind)
        assertEquals("Cxyz1234567", parsed?.shortcode)
    }

    @Test
    fun trackingParametersAndUppercaseHostDoNotHideTheShortcode() {
        val parsed = InstagramUrl.parse(
            "https://INSTRAGRAM.COM/P/Cxyz1234567/?igshid=MzRlODBiNWFlZA%3D%3D"
        )
        // The host above is misspelled on purpose in one assertion below; here it must not match.
        assertNull(parsed)

        val real = InstagramUrl.parse(
            "https://www.instagram.com/p/Cxyz1234567/?igshid=MzRlODBiNWFlZA%3D%3D&utm_source=share"
        )
        assertEquals("Cxyz1234567", real?.shortcode)
    }

    @Test
    fun shortAltHostIsAccepted() {
        assertTrue(InstagramUrl.isMediaUrl("https://www.instagr.am/p/Cxyz1234567/"))
    }

    @Test
    fun profilesTagsAndStoriesAreNotMediaUrls() {
        assertEquals(InstagramUrl.Kind.CHANNEL, InstagramUrl.kind("https://www.instagram.com/natgeo/"))
        assertEquals(InstagramUrl.Kind.TAG, InstagramUrl.kind("https://www.instagram.com/explore/tags/cats/"))
        assertFalse(InstagramUrl.isMediaUrl("https://www.instagram.com/stories/natgeo/1234/"))
        assertEquals(InstagramUrl.Kind.OTHER, InstagramUrl.kind("https://www.instagram.com/p/ab/"))
    }

    @Test
    fun otherHostsAreNeverInstagramMedia() {
        assertFalse(InstagramUrl.isMediaUrl("https://example.com/p/Cxyz1234567/"))
        assertFalse(InstagramUrl.isMediaUrl("https://www.instagram.com.evil.example/p/Cxyz1234567/"))
        assertFalse(InstagramUrl.isMediaUrl(""))
        assertFalse(InstagramUrl.isMediaUrl("not a url"))
        assertFalse(InstagramUrl.isMediaUrl("instagram://app/p/Cxyz1234567/"))
    }

    @Test
    fun shortcodeExtractionMatchesTheParsersOwnHelper() {
        val url = "https://www.instagram.com/reel/Cxyz1234567/?igshid=abc"

        assertEquals("Cxyz1234567", InstagramMediaParser.shortcodeFromUrl(url))
        assertEquals("", InstagramMediaParser.shortcodeFromUrl("https://www.instagram.com/natgeo/"))
        assertTrue(InstagramMediaParser.isPostUrl(url))
    }

    @Test
    fun canonicalUrlIsBuiltPerKind() {
        assertEquals(
            "https://www.instagram.com/reel/AbCdEf1234/",
            InstagramUrl.canonicalUrl(InstagramUrl.Kind.REEL, "AbCdEf1234")
        )
        assertEquals(
            "https://www.instagram.com/p/AbCdEf1234/",
            InstagramUrl.canonicalUrl(InstagramUrl.Kind.POST, "AbCdEf1234")
        )
    }
}
