package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download formats Instagram has to keep working, each pinned on its own:
 *
 *  1. single-image post
 *  2. single-video post
 *  3. image-only carousel
 *  4. video-only carousel
 *  5. mixed image-and-video carousel
 *
 * Each shape must yield *every* slide of the post (not just the first), in the order
 * the post lists them, with posters for the clips and a slide count that lets the
 * picker tell "the whole post" from "a partial read".
 */
class InstagramFormatMatrixTest {

    private fun webPayload(
        slides: String,
        declared: Int,
        caption: String = "caption text"
    ): String = """
        {
          "items": [
            {
              "id": "42",
              "carousel_media_count": $declared,
              "carousel_media": [$slides],
              "caption": { "text": "$caption" }
            }
          ]
        }
    """.trimIndent()

    private fun photo(url: String): String = """
        {
          "media_type": 1,
          "image_versions2": { "candidates": [ { "url": "$url", "width": 1440 } ] }
        }
    """.trimIndent()

    private fun video(url: String, poster: String): String = """
        {
          "media_type": 2,
          "image_versions2": { "candidates": [ { "url": "$poster", "width": 1080 } ] },
          "video_versions": [ { "url": "$url", "width": 720 } ]
        }
    """.trimIndent()

    @Test
    fun singleImagePostOffersThatOnePicture() {
        val url = "https://scontent.cdninstagram.com/v/only.jpg?sig=a"
        val result = InstagramMediaParser.parseJson(
            webPayload(slides = photo(url), declared = 1)
        )

        assertEquals(listOf(url), result.imageUrls)
        assertTrue(result.videoUrls.isEmpty())
        assertEquals(1, result.mediaCount)
        assertEquals(1, result.expectedCount)
        assertTrue(result.isComplete)
        assertFalse(result.orderedSlides().single().isVideo)
    }

    @Test
    fun singleVideoPostOffersThatOneClipWithItsPoster() {
        val clip = "https://scontent.cdninstagram.com/v/only.mp4?sig=v"
        val poster = "https://scontent.cdninstagram.com/v/only-poster.jpg?sig=v"
        val result = InstagramMediaParser.parseJson(
            webPayload(slides = video(clip, poster), declared = 1)
        )

        assertEquals(listOf(clip), result.videoUrls)
        assertEquals(listOf(poster), result.videoPosters)
        assertTrue(result.imageUrls.isEmpty())
        assertTrue(result.isComplete)
        assertTrue(result.primaryIsVideo)
    }

    @Test
    fun imageOnlyCarouselListsEveryPictureInOrder() {
        val a = "https://scontent.cdninstagram.com/v/a.jpg?sig=a"
        val b = "https://scontent.cdninstagram.com/v/b.jpg?sig=b"
        val c = "https://scontent.cdninstagram.com/v/c.jpg?sig=c"
        val result = InstagramMediaParser.parseJson(
            webPayload(slides = listOf(photo(a), photo(b), photo(c)).joinToString(","), declared = 3)
        )

        assertEquals(listOf(a, b, c), result.imageUrls)
        assertTrue(result.videoUrls.isEmpty())
        assertEquals(3, result.mediaCount)
        assertTrue(result.isComplete)
        assertEquals(listOf(a, b, c), result.orderedSlides().map { it.url })
        assertFalse(result.primaryIsVideo)
    }

    @Test
    fun videoOnlyCarouselListsEveryClipWithItsPoster() {
        val v1 = "https://scontent.cdninstagram.com/v/v1.mp4?sig=1"
        val v2 = "https://scontent.cdninstagram.com/v/v2.mp4?sig=2"
        val p1 = "https://scontent.cdninstagram.com/v/p1.jpg?sig=1"
        val p2 = "https://scontent.cdninstagram.com/v/p2.jpg?sig=2"
        val result = InstagramMediaParser.parseJson(
            webPayload(
                slides = listOf(video(v1, p1), video(v2, p2)).joinToString(","),
                declared = 2
            )
        )

        assertEquals(listOf(v1, v2), result.videoUrls)
        assertEquals(listOf(p1, p2), result.videoPosters)
        assertTrue(result.imageUrls.isEmpty())
        assertEquals(2, result.mediaCount)
        assertTrue(result.isComplete)
        assertTrue(result.primaryIsVideo)
    }

    @Test
    fun mixedCarouselKeepsPostOrderAcrossBothKinds() {
        val p1 = "https://scontent.cdninstagram.com/v/m1.jpg?sig=1"
        val v2 = "https://scontent.cdninstagram.com/v/m2.mp4?sig=2"
        val poster2 = "https://scontent.cdninstagram.com/v/m2-poster.jpg?sig=2"
        val p3 = "https://scontent.cdninstagram.com/v/m3.jpg?sig=3"
        val v4 = "https://scontent.cdninstagram.com/v/m4.mp4?sig=4"
        val poster4 = "https://scontent.cdninstagram.com/v/m4-poster.jpg?sig=4"
        val result = InstagramMediaParser.parseJson(
            webPayload(
                slides = listOf(photo(p1), video(v2, poster2), photo(p3), video(v4, poster4))
                    .joinToString(","),
                declared = 4
            )
        )

        assertEquals(4, result.mediaCount)
        assertTrue(result.isComplete)
        // The picker and the saved file names follow the post: picture, clip, picture, clip.
        assertEquals(
            listOf(p1, v2, p3, v4),
            result.orderedSlides().map { it.url }
        )
        assertEquals(
            listOf(false, true, false, true),
            result.orderedSlides().map { it.isVideo }
        )
        // Posters are previews of the clips and are never offered as extra pictures.
        assertEquals(2, result.imageUrls.size)
        assertEquals(listOf(poster2, poster4), result.videoPosters)
    }

    @Test
    fun missingSlidesAreReportedNotHidden() {
        val a = "https://scontent.cdninstagram.com/v/a.jpg?sig=a"
        val result = InstagramMediaParser.parseJson(
            webPayload(slides = photo(a), declared = 5)
        )

        assertTrue(result.isPartial)
        assertFalse(result.isComplete)
        assertEquals(5, result.expectedCount)
        assertEquals(1, result.mediaCount)
        // The readable slide is still offered; the count tells the picker to warn.
        assertEquals(listOf(a), result.orderedSlides().map { it.url })
    }
}
