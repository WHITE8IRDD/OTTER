package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A carousel slide is a photo **or** a video. Both have to be read, in payload order,
 * and the slide count has to cover both, so the picker can offer each group and warn
 * when the post declares more slides than could be read.
 *
 * Shapes covered: the web `items[].carousel_media` payload, the GraphQL
 * `edge_sidecar_to_children` payload, the crawler page (inline JSON), partial reads
 * and a plain single-photo post.
 */
class InstagramMixedCarouselTest {

    private val photo1 = "https://scontent.cdninstagram.com/v/photo1.jpg?sig=one&oe=1"
    private val photo3 = "https://scontent.cdninstagram.com/v/photo3.jpg?sig=three"
    private val video2 = "https://scontent.cdninstagram.com/v/video2.mp4?sig=two"
    private val videoPoster = "https://scontent.cdninstagram.com/v/poster2.jpg?sig=two"

    private fun webPayload(declared: Int = 3): String = """
        {
          "items": [
            {
              "id": "123",
              "carousel_media_count": $declared,
              "carousel_media": [
                {
                  "media_type": 1,
                  "image_versions2": { "candidates": [
                    { "url": "$photo1", "width": 1440 }
                  ] }
                },
                {
                  "media_type": 2,
                  "image_versions2": { "candidates": [
                    { "url": "$videoPoster", "width": 1080 }
                  ] },
                  "video_versions": [
                    { "url": "$video2", "width": 720 }
                  ]
                },
                {
                  "media_type": 1,
                  "image_versions2": { "candidates": [
                    { "url": "$photo3", "width": 1440 }
                  ] }
                }
              ],
              "caption": { "text": "sunset walk \u0026 coffee #beach #vibes" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun webPayloadSplitsPhotoAndVideoSlidesAndKeepsTheirOrder() {
        val result = InstagramMediaParser.parseJson(webPayload())

        assertEquals(listOf(photo1, photo3), result.imageUrls)
        assertEquals(listOf(video2), result.videoUrls)
        assertEquals(listOf(videoPoster), result.videoPosters)
        assertEquals(3, result.mediaCount)
        assertEquals(3, result.expectedCount)
        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
    }

    @Test
    fun videoSlideKeepsItsSignedQueryString() {
        val result = InstagramMediaParser.parseJson(webPayload())

        assertTrue(result.videoUrls.first().contains("?sig=two"))
        assertTrue(result.imageUrls.first().contains("?sig=one"))
    }

    @Test
    fun captionIsReadAndHashtagsComeFromIt() {
        val result = InstagramMediaParser.parseJson(webPayload())

        assertTrue(result.caption.contains("#beach"))
        assertEquals(listOf("#beach", "#vibes"), HashtagExtractor.extract(result.caption))
        assertEquals("sunset walk & coffee", HashtagExtractor.strip(result.caption))
    }

    @Test
    fun declaredSlideCountMakesAShortReadPartial() {
        val result = InstagramMediaParser.parseJson(webPayload(declared = 8))

        assertEquals(8, result.expectedCount)
        assertEquals(3, result.mediaCount)
        assertTrue(result.isPartial)
        assertFalse(result.isComplete)
    }

    @Test
    fun graphQlPayloadUsesIsVideoAndVideoUrl() {
        val json = """
            {
              "data": {
                "shortcode_media": {
                  "media_type": 8,
                  "edge_sidecar_to_children": {
                    "edges": [
                      { "node": {
                          "is_video": false,
                          "display_url": "https://scontent.cdninstagram.com/v/g1.jpg?a=1"
                      } },
                      { "node": {
                          "is_video": true,
                          "display_url": "https://scontent.cdninstagram.com/v/g2.jpg?a=2",
                          "video_url": "https://scontent.cdninstagram.com/v/g2.mp4?a=3"
                      } }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(listOf("https://scontent.cdninstagram.com/v/g1.jpg?a=1"), result.imageUrls)
        assertEquals(listOf("https://scontent.cdninstagram.com/v/g2.mp4?a=3"), result.videoUrls)
        assertEquals(listOf("https://scontent.cdninstagram.com/v/g2.jpg?a=2"), result.videoPosters)
        assertEquals(2, result.mediaCount)
        assertTrue(result.isComplete)
    }

    @Test
    fun crawlerPageIsParsedAndReportsThePartialRead() {
        val html = """
            <html><head><title>Instagram</title></head><body>
            <script type="application/json">${webPayload(declared = 5)}</script>
            </body></html>
        """.trimIndent()

        val result = InstagramMediaParser.parseHtml(html, "https://www.instagram.com/p/DeEo7SjjLYz/")

        assertEquals(2, result.imageUrls.size)
        assertEquals(1, result.videoUrls.size)
        assertEquals(5, result.expectedCount)
        assertTrue(result.isPartial)
        assertTrue(result.note?.contains("only 3 were readable") == true)
        assertEquals("DeEo7SjjLYz", result.shortcode)
    }

    @Test
    fun singlePhotoPostHasNoVideoSlides() {
        val json = """
            { "items": [ {
                "media_type": 1,
                "image_versions2": { "candidates": [
                  { "url": "https://scontent.cdninstagram.com/v/only.jpg?sig=x", "width": 1080 }
                ] }
            } ] }
        """.trimIndent()

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(listOf("https://scontent.cdninstagram.com/v/only.jpg?sig=x"), result.imageUrls)
        assertTrue(result.videoUrls.isEmpty())
        assertFalse(result.isPartial)
    }

    @Test
    fun singleVideoPostIsAVideoSlideAndNotAlsoAPhoto() {
        val json = """
            { "items": [ {
                "media_type": 2,
                "image_versions2": { "candidates": [
                  { "url": "$videoPoster", "width": 1080 }
                ] },
                "video_versions": [ { "url": "$video2", "width": 720 } ]
            } ] }
        """.trimIndent()

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(listOf(video2), result.videoUrls)
        // The poster frame must not also be offered as a photo of the post.
        assertTrue(result.imageUrls.isEmpty())
    }

    @Test
    fun profilePicturesAreNeverSlides() {
        val json = """
            { "items": [ {
                "media_type": 1,
                "image_versions2": { "candidates": [
                  { "url": "https://scontent.cdninstagram.com/v/t51.2885-19/profile.jpg", "width": 150 }
                ] }
            } ] }
        """.trimIndent()

        assertTrue(InstagramMediaParser.parseJson(json).imageUrls.isEmpty())
    }
}
