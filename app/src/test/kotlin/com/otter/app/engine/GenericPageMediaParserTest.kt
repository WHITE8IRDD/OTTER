package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericPageMediaParserTest {
    @Test
    fun extractsOpenGraphImagesAndKeepsSignedQueryParameters() {
        val html = """
            <html>
              <head>
                <title>Summer &amp; friends</title>
                <meta property="og:image" content="/cover.jpg?token=one&amp;sig=two">
              </head>
              <body>
                <img width="1" height="1" src="/tracking/pixel.gif">
                <img srcset="/small.jpg 320w, /large.jpg 1200w">
              </body>
            </html>
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/posts/one", html)

        assertEquals("Summer & friends", result.title)
        assertEquals(
            listOf(
                "https://example.org/cover.jpg?token=one&sig=two",
                "https://example.org/large.jpg"
            ),
            result.imageUrls
        )
    }

    @Test
    fun extractsJsonLdImageArraysAndDecodesEscapedAmpersands() {
        val escapedAmpersand = "\\" + "u0026"
        val html = """
            <script type="application/ld+json">
              {"@type":"ImageGallery","image":["/first.png","https://images.example/second.webp?sig=abc${escapedAmpersand}v=2"]}
            </script>
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/gallery", html)

        assertEquals(2, result.imageUrls.size)
        assertEquals("https://example.org/first.png", result.imageUrls[0])
        assertTrue(result.imageUrls[1].contains("?sig=abc&v=2"))
    }

    /**
     * A page that carries a video has to answer with the video.
     *
     * These cases are the whole reason the fallback scraper reads more than `og:image`: pasting a
     * video-page link used to produce a grid of page pictures and no download at all, and the picker
     * showed whatever the scraper happened to return.
     */
    @Test
    fun aDeclaredVideoBecomesThePrimaryResultAndKeepsItsSignedQuery() {
        val html = """
            <meta property="og:image" content="https://cdn.example.org/cover.jpg?sig=poster">
            <meta property="og:video" content="/media/summer.mp4?sig=one&amp;oe=1">
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/watch/summer", html)

        assertEquals(
            listOf("https://example.org/media/summer.mp4?sig=one&oe=1"),
            result.videoUrls
        )
        assertEquals(listOf("https://cdn.example.org/cover.jpg?sig=poster"), result.videoPosters)
        assertTrue("the page said it has a video", result.hasVideoSignals)
        // The poster is still a picture of the page, offered as well.
        assertTrue(result.imageUrls.contains("https://cdn.example.org/cover.jpg?sig=poster"))
    }

    @Test
    fun aVideoElementIsFoundAndItsPosterIsResolvedAgainstThePage() {
        val html = """
            <figure>
              <video src="/files/clip.webm" poster="/thumbs/clip.jpg" controls></video>
            </figure>
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://media.example.org/p/1", html)

        assertEquals(listOf("https://media.example.org/files/clip.webm"), result.videoUrls)
        assertEquals(listOf("https://media.example.org/thumbs/clip.jpg"), result.videoPosters)
        assertTrue(result.hasVideoSignals)
    }

    @Test
    fun aSourceElementInsideAVideoTagIsTheSameVideo() {
        val html = """
            <video controls>
              <source src="/hls/master.m3u8" type="application/x-mpegURL">
              <source src="/files/clip-720.mp4" type="video/mp4">
            </video>
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/v/9", html)

        assertEquals(2, result.videoUrls.size)
        assertTrue(result.videoUrls.contains("https://example.org/files/clip-720.mp4"))
        assertTrue(
            "a manifest is a result too, but it has to go through the engine rather than a plain GET",
            result.videoUrls.contains("https://example.org/hls/master.m3u8")
        )
    }

    @Test
    fun anAlternateManifestLinkIsThePageVideo() {
        val html = """
            <link rel="alternate" type="application/x-mpegURL" href="/live/master.m3u8">
            <link rel="preload" as="image" href="/pre/poster.jpg">
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/live/1", html)

        assertEquals(listOf("https://example.org/live/master.m3u8"), result.videoUrls)
        assertEquals(listOf("https://example.org/pre/poster.jpg"), result.imageUrls)
    }

    @Test
    fun aPlayerEmbedIsReportedAsAVideoThatCannotBeRead() {
        // `og:video` sometimes names an embed page. Returning only the page pictures in that case is
        // what made a video page look like a photo page; now the caller sees the signal and says so.
        val html = """
            <meta property="og:image" content="https://example.org/still.jpg">
            <meta property="og:video" content="https://player.example.org/embed/12345">
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/watch/12345", html)

        assertTrue(result.videoUrls.isEmpty())
        assertTrue(result.hasVideoSignals)
    }

    @Test
    fun aPhotoOnlyPageDoesNotClaimAVideo() {
        val html = """
            <meta property="og:image" content="https://example.org/a.jpg">
            <img src="https://example.org/b.jpg">
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/album", html)

        assertEquals(2, result.imageUrls.size)
        assertTrue(result.videoUrls.isEmpty())
        assertFalse(result.hasVideoSignals)
    }

    @Test
    fun rejectsNonHttpAndInlineImageCandidates() {
        val html = """
            <meta property="og:image" content="javascript:alert(1)">
            <img src="data:image/png;base64,AAAA">
            <img src="//cdn.example.org/ok.avif?key=1">
        """.trimIndent()

        val result = GenericPageMediaParser.parse("https://example.org/page", html)

        assertEquals(listOf("https://cdn.example.org/ok.avif?key=1"), result.imageUrls)
    }
}
