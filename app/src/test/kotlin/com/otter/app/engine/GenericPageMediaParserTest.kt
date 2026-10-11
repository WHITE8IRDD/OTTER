package com.otter.app.engine

import org.junit.Assert.assertEquals
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
