package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a post from the payload the app actually gets without a session.
 *
 * These fixtures are shaped like Instagram's page payload, not like the parser: the
 * sidecar is typed with `__typename` and carries no `media_type`, and a video slide
 * offers its video as `video_url`. Fixtures built to match the parser instead of the
 * source are what let the regression through in the first place.
 *
 * The suggested posts that surround the real one on a post page are included, because
 * a post that is not recognised as a sidecar falls back to a blind scan of the page
 * and those are what the scan picks up.
 */
class InstagramPagePayloadTest {

    private val photo1 =
        "https://scontent-cdg2-1.cdninstagram.com/v/t51.2885-15/11111111_2222222_3333_n.jpg?stp=dst-jpg_e35&_nc_cat=1"
    private val photo3 =
        "https://scontent-cdg2-1.cdninstagram.com/v/t51.2885-15/77777777_8888888_9999_n.jpg?stp=dst-jpg_e35&_nc_cat=1"
    private val video2 =
        "https://scontent-cdg2-1.cdninstagram.com/v/t66.30100-16/44444444_5555555_6666_n.mp4?efg=eyJ2ZW5jb2RlX3RhZyI6MX0&_nc_cat=1"
    private val poster2 =
        "https://scontent-cdg2-1.cdninstagram.com/v/t51.2885-15/44444444_5555555_6666_n.jpg?stp=dst-jpg_e35&_nc_cat=1"

    private val suggestedA =
        "https://scontent-cdg2-1.cdninstagram.com/v/t51.2885-15/aaaaaaaa_bbbbbbbb_n.jpg?_nc_cat=1"
    private val suggestedB =
        "https://scontent-cdg2-1.cdninstagram.com/v/t51.2885-15/cccccccc_dddddddd_n.jpg?_nc_cat=1"

    /** A post page: the real post typed `GraphSidecar`, wrapped in suggested posts. */
    private fun pagePayload(
        typename: String = "GraphSidecar",
        videoTypename: String = "GraphVideo",
        withIsVideo: Boolean = true
    ): String = """
        {
          "suggested": [
            { "__typename": "GraphImage", "id": "s1", "display_url": "$suggestedA" },
            { "__typename": "GraphImage", "id": "s2", "display_url": "$suggestedB" }
          ],
          "shortcode_media": {
            "__typename": "$typename",
            "id": "1234567890",
            "edge_sidecar_to_children": {
              "edges": [
                { "node": {
                    "__typename": "GraphImage",
                    "id": "p1",
                    "display_url": "$photo1",
                    "display_resources": [ { "src": "$photo1", "config_width": 1440 } ]
                } },
                { "node": {
                    "__typename": "$videoTypename",
                    "id": "v2",
                    ${if (withIsVideo) "\"is_video\": true," else ""}
                    "display_url": "$poster2",
                    "display_resources": [ { "src": "$poster2", "config_width": 1080 } ],
                    "video_url": "$video2"
                } },
                { "node": {
                    "__typename": "GraphImage",
                    "id": "p3",
                    "display_url": "$photo3",
                    "display_resources": [ { "src": "$photo3", "config_width": 1440 } ]
                } }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun pageSidecarWithoutMediaTypeIsRecognised() {
        val result = InstagramMediaParser.parseJson(pagePayload())

        assertEquals(listOf(photo1, photo3), result.imageUrls)
        assertEquals(listOf(video2), result.videoUrls)
    }

    @Test
    fun suggestedPostsOnTheSamePageAreNotMixedIntoThePost() {
        val result = InstagramMediaParser.parseJson(pagePayload())

        assertFalse(result.imageUrls.contains(suggestedA))
        assertFalse(result.imageUrls.contains(suggestedB))
    }

    @Test
    fun videoSlideKeepsItsPosterForThePicker() {
        val result = InstagramMediaParser.parseJson(pagePayload())

        assertEquals(listOf(poster2), result.videoPosters)
    }

    @Test
    fun mixedPostCountsPhotosAndVideosTogether() {
        val result = InstagramMediaParser.parseJson(pagePayload())

        assertEquals(3, result.mediaCount)
        assertTrue(result.isComplete)
        assertFalse(result.isPartial)
    }

    @Test
    fun xdtTypenamesAreUnderstood() {
        val result = InstagramMediaParser.parseJson(
            pagePayload(
                typename = "XDTGraphSidecar",
                videoTypename = "XDTGraphVideo",
                withIsVideo = false
            )
        )

        assertEquals(listOf(photo1, photo3), result.imageUrls)
        assertEquals(listOf(video2), result.videoUrls)
    }

    /**
     * `__typename` alone has to be enough: a slide typed as a video must never be
     * demoted to a picture because `is_video` is missing.
     */
    @Test
    fun videoSlideIsTypedByTypenameAlone() {
        val result = InstagramMediaParser.parseJson(pagePayload(withIsVideo = false))

        assertEquals(listOf(video2), result.videoUrls)
        assertEquals(2, result.imageUrls.size)
        assertFalse(result.imageUrls.contains(poster2))
    }

    @Test
    fun singleVideoPostReadsAsOneVideoSlide() {
        val json = """
            {
              "shortcode_media": {
                "__typename": "GraphVideo",
                "id": "9",
                "is_video": true,
                "display_url": "$poster2",
                "video_url": "$video2"
              }
            }
        """.trimIndent()

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(listOf(video2), result.videoUrls)
        assertEquals(1, result.mediaCount)
    }
}
