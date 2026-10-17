package com.otter.app.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramDetectionRegressionTest {
    private val cdn = "https://scontent.cdninstagram.com/"
    private fun page(json: String, extra: String = "") = "<script type=\"application/json\">$json</script>$extra"

    @Test fun singleVideoHtmlDoesNotReintroduceItsPosterOrPlayOverlay() {
        val result = InstagramMediaParser.parseHtml(page("""{"shortcode_media":{
            "shortcode":"Parent","__typename":"GraphVideo","display_url":"${cdn}poster.jpg",
            "video_url":"${cdn}clip.mp4"}}
        """, "<img src=\"${cdn}play-button.jpg\"><img src=\"${cdn}poster.jpg\">"), "https://instagram.com/p/Parent/")
        assertEquals(listOf("${cdn}clip.mp4"), result.videoUrls)
        assertTrue(result.imageUrls.isEmpty())
        assertEquals("${cdn}poster.jpg", result.orderedSlides().single().posterUrl)
    }

    @Test fun childShortcodesAreNotForeignPostsAndAllFiveSlidesKeepTheirTypes() {
        val children = (0..4).joinToString(",") { i ->
            if (i % 2 == 0) """{"node":{"shortcode":"Child$i","display_url":"${cdn}$i.jpg","is_video":false}}"""
            else """{"node":{"shortcode":"Child$i","display_url":"${cdn}$i.jpg","video_url":"${cdn}$i.mp4"}}"""
        }
        val result = InstagramMediaParser.parseJson("""{"shortcode_media":{"shortcode":"Parent",
            "edge_sidecar_to_children":{"edges":[$children]}}} """, shortcode = "Parent")
        assertEquals(5, result.mediaCount)
        assertEquals(listOf(false, true, false, true, false), result.orderedSlides().map { it.isVideo })
        assertEquals(3, result.imageUrls.size)
        assertEquals(2, result.videoUrls.size)
    }

    @Test fun missingPlayableVideoRemainsMissingNotAPictureOrACompleteAlbum() {
        val result = InstagramMediaParser.parseJson("""{"items":[{"carousel_media":[
            {"media_type":1,"display_url":"${cdn}photo.jpg"},
            {"media_type":2,"display_url":"${cdn}poster.jpg"}]}]}""")
        assertEquals(listOf("${cdn}photo.jpg"), result.imageUrls)
        assertEquals(2, result.expectedCount)
        assertTrue(result.isPartial)
    }

    @Test fun unresolvedSingleVideoDoesNotBecomeItsImageVersions() {
        val result = InstagramMediaParser.parseHtml(page("""{"shortcode_media":{"media_type":2,
            "image_versions2":{"candidates":[{"url":"${cdn}poster.jpg"}]}}}"""))
        assertEquals(0, result.mediaCount)
    }

    @Test fun oneImageUsesLargestResourceNotAllRenditions() {
        val result = InstagramMediaParser.parseJson("""{"items":[{"media_type":1,"display_url":"${cdn}preview.jpg",
            "display_resources":[{"src":"${cdn}small.jpg","config_width":320},
            {"src":"${cdn}large.jpg?sig=kept","config_width":1440}]}]}""")
        assertEquals(listOf("${cdn}large.jpg?sig=kept"), result.imageUrls)
        assertEquals(1, result.orderedSlides().size)
    }

    @Test fun alternativeVideoVersionsCountAsOneItem() {
        val result = InstagramMediaParser.parseJson("""{"items":[{"media_type":2,
            "video_versions":[{"url":"${cdn}low.mp4","width":320},{"url":"${cdn}high.mp4","width":1080}],
            "video_url":"${cdn}other.mp4","display_url":"${cdn}poster.jpg"}]}""")
        assertEquals(listOf("${cdn}high.mp4"), result.videoUrls)
        assertEquals(1, result.mediaCount)
    }

    @Test fun nakedCdnStringsAndOpenGraphPreviewsAreNotTypedPostMedia() {
        val result = InstagramMediaParser.parseHtml("<meta property=\"og:image\" content=\"${cdn}preview.jpg\"><img src=\"${cdn}play.jpg\">")
        assertEquals(0, result.mediaCount)
    }

    @Test fun aForeignPostIsNotSelectedEvenWhenItsCodeOnlyDiffersInCase() {
        val result = InstagramMediaParser.parseJson("""{"items":[{"shortcode":"parent","media_type":1,"display_url":"${cdn}foreign.jpg"}]}""", shortcode = "Parent")
        assertEquals(0, result.mediaCount)
    }

    @Test fun lookalikeCdnHostIsRejected() {
        assertNull(InstagramMediaParser.normalizeUrl("https://evilcdninstagram.com/a.jpg"))
    }

    @Test fun engineGalleryDoesNotUseThumbnailOrFlatVideoPageAsAnImage() {
        val gallery = EngineGalleryParser.parse(JSONObject("""{"_type":"playlist","entries":[
            {"_type":"url","url":"https://instagram.com/p/child/","thumbnail":"${cdn}poster.jpg"},
            {"url":"${cdn}photo.jpg","ext":"jpg"},
            {"url":"${cdn}clip.mp4","ext":"mp4","thumbnail":"${cdn}poster.jpg"}
        ]}"""))
        assertEquals(3, gallery.expectedCount)
        assertEquals(listOf(false, true), gallery.slides.map { it.isVideo })
    }
}
