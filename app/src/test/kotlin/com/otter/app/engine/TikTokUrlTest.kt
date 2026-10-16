package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok link recognition, kept free of Android and of the network so the URL-shape rules are
 * pinned down independently of whether a particular short link still resolves on a given day.
 *
 * The shapes below are the ones that actually reached the app broken: a canonical `@user/video/<id>`
 * link (the one case the old code already handled), the forwarded `share/video/<id>` link the app's
 * own share sheet and most phones' TikTok app produce, and the `vm.tiktok.com` short link copied
 * from the TikTok "Copy link" action. All three used to be handed to the engine unchanged; only the
 * first ever worked.
 */
class TikTokUrlTest {

    @Test
    fun canonicalVideoLinkIsRecognisedAndLeftAlone() {
        val url = "https://www.tiktok.com/@otterapp/video/7123456789012345678"
        assertTrue(TikTokUrl.isMediaUrl(url))
        val parsed = TikTokUrl.parse(url)!!
        assertEquals(TikTokUrl.Kind.VIDEO, parsed.kind)
        assertEquals("otterapp", parsed.author)
        assertEquals("7123456789012345678", parsed.itemId)
        assertFalse(parsed.isShortLink)
        assertEquals(url, TikTokUrl.engineUrl(url))
    }

    @Test
    fun forwardedShareLinkIsRewrittenToTheHandleForm() {
        // This is the shape a forwarded TikTok share actually produces, and the shape the pinned
        // extractor (`_VALID_URL`) does not match at all - the direct cause of every TikTok video
        // failing from the share sheet before this fix.
        val shared = "https://www.tiktok.com/share/video/7123456789012345678/?is_copy_url=1&lang=en"
        val parsed = TikTokUrl.parse(shared)!!
        assertEquals(TikTokUrl.Kind.VIDEO, parsed.kind)
        assertEquals("7123456789012345678", parsed.itemId)
        assertTrue("no handle on a bare share link", parsed.author.isEmpty())
        // With no author known yet, the embed form is the one both old and new extractors accept.
        assertEquals("https://www.tiktok.com/embed/7123456789012345678", TikTokUrl.engineUrl(shared))
    }

    @Test
    fun aShortLinkCarriesNoIdAndMustBeFollowed() {
        val short = "https://vm.tiktok.com/ZMabcdEFG/"
        assertTrue(TikTokUrl.hostMatches(short))
        assertTrue(TikTokUrl.isShortLink(short))
        val parsed = TikTokUrl.parse(short)!!
        assertEquals("", parsed.itemId)
        // Nothing to rewrite yet: the resolver has to hit the network first.
        assertEquals(short, TikTokUrl.engineUrl(short))
    }

    @Test
    fun photoModeShareLinkIsNotTreatedAsAVideo() {
        val sharedPhoto = "https://www.tiktok.com/share/photo/7123456789012345678/"
        val parsed = TikTokUrl.parse(sharedPhoto)!!
        assertEquals(TikTokUrl.Kind.PHOTO, parsed.kind)
        assertTrue("the app's own photo parser owns this link", TikTokUrl.isMediaUrl(sharedPhoto))
        // The photo path is a page read by id, not a video extraction; rewriting it would only add a
        // second way to get it wrong, so the URL travels unchanged to TikTokPhotoParser.
        assertEquals(sharedPhoto, TikTokUrl.engineUrl(sharedPhoto))
    }

    @Test
    fun embedLinkIsAlreadyInTheShapeTheEngineWants() {
        val embed = "https://www.tiktok.com/embed/7123456789012345678"
        val parsed = TikTokUrl.parse(embed)!!
        assertEquals(TikTokUrl.Kind.VIDEO, parsed.kind)
        assertEquals("7123456789012345678", parsed.itemId)
        assertEquals(embed, TikTokUrl.engineUrl(embed))
    }

    @Test
    fun profileAndTagLinksAreNotMediaLinks() {
        assertFalse(TikTokUrl.isMediaUrl("https://www.tiktok.com/@otterapp"))
        assertFalse(TikTokUrl.isMediaUrl("https://www.tiktok.com/tag/funny"))
        assertFalse(TikTokUrl.isMediaUrl("https://www.tiktok.com/music/Some-Song-712345"))
        assertEquals(TikTokUrl.Kind.USER, TikTokUrl.parse("https://www.tiktok.com/@otterapp")!!.kind)
        assertEquals(TikTokUrl.Kind.TAG, TikTokUrl.parse("https://www.tiktok.com/tag/funny")!!.kind)
    }

    @Test
    fun unrelatedHostsAreNeverMatched() {
        assertFalse(TikTokUrl.hostMatches("https://example.org/video/123456789012"))
        assertFalse(TikTokUrl.hostMatches("https://nottiktok.com/@user/video/123456789012"))
        assertFalse(TikTokUrl.isMediaUrl("https://instagram.com/p/abc123/"))
    }

    @Test
    fun trackingParametersAreStrippedForACanonicalCacheKey() {
        val decorated = "https://www.tiktok.com/@otterapp/video/7123456789012345678?is_copy_url=1&lang=en"
        assertEquals(
            "https://www.tiktok.com/@otterapp/video/7123456789012345678",
            TikTokUrl.stripTracking(decorated)
        )
    }

    @Test
    fun itemIdHelperMatchesParse() {
        assertEquals(
            "7123456789012345678",
            TikTokUrl.itemId("https://www.tiktok.com/@otterapp/video/7123456789012345678")
        )
        assertEquals("", TikTokUrl.itemId("https://www.tiktok.com/@otterapp"))
        assertEquals("", TikTokUrl.itemId("https://example.org/not-tiktok"))
    }

    @Test
    fun queryCarriedIdsAreReadToo() {
        val shared = "https://www.tiktok.com/share/item/video?item_id=7123456789012345678"
        assertEquals("7123456789012345678", TikTokUrl.itemId(shared))
    }

    @Test
    fun mediaIdHelperPrefersYoutubeThenFallsBackToTikTok() {
        assertEquals(
            "dQw4w9WgXcQ",
            MediaUrlNormalizer.mediaId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "7123456789012345678",
            MediaUrlNormalizer.mediaId("https://www.tiktok.com/@otterapp/video/7123456789012345678")
        )
        assertEquals("", MediaUrlNormalizer.mediaId("https://example.org/video.mp4"))
    }
}
