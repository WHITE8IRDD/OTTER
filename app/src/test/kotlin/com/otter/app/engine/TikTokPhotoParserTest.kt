package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokPhotoParserTest {
    private val first = "https://p16-sign-va.tiktokcdn.com/tos-maliva-p-0068/image1.jpeg?x-expires=10&x-signature=one"
    private val second = "https://p16-sign-va.tiktokcdn.com/tos-maliva-p-0068/image2.jpeg?x-expires=20&x-signature=two"

    @Test
    fun universalHydrationPayloadListsEveryPhotoAndPreservesSignedUrls() {
        val json = """
            {
              "__DEFAULT_SCOPE__": {
                "webapp.video-detail": {
                  "itemInfo": {
                    "itemStruct": {
                      "id": "7123456789012345678",
                      "desc": "A photo post #travel",
                      "authorInfo": { "uniqueId": "otter_user" },
                      "imagePost": {
                        "images": [
                          { "imageURL": { "urlList": ["$first"] } },
                          { "imageURL": { "urlList": ["$second"] } }
                        ]
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val result = TikTokPhotoParser.parseJson(
            json,
            "https://www.tiktok.com/@otter_user/photo/7123456789012345678"
        )

        assertEquals(listOf(first, second), result.imageUrls)
        assertEquals(2, result.expectedCount)
        assertTrue(result.isComplete)
        assertEquals("A photo post #travel", result.title)
        assertEquals("otter_user", result.uploader)
    }

    @Test
    fun parserDoesNotOfferVideoCoverOrSuggestedPostThumbnailsAsSlides() {
        val json = """
            {
              "itemInfo": {
                "itemStruct": {
                  "id": "7123456789012345678",
                  "video": { "cover": { "urlList": ["https://p16-sign-va.tiktokcdn.com/cover.jpeg"] } },
                  "imagePost": { "images": [
                    { "displayImage": { "urlList": ["$first"] } }
                  ] }
                }
              },
              "related": [
                { "thumbnail": "https://p16-sign-va.tiktokcdn.com/suggested.jpeg" }
              ]
            }
        """.trimIndent()

        val result = TikTokPhotoParser.parseJson(json)

        assertEquals(listOf(first), result.imageUrls)
        assertFalse(result.imageUrls.contains("https://p16-sign-va.tiktokcdn.com/cover.jpeg"))
        assertFalse(result.imageUrls.contains("https://p16-sign-va.tiktokcdn.com/suggested.jpeg"))
    }

    @Test
    fun pageScriptIsParsedAndMissingImagesAreReportedAsPartial() {
        val json = """
            {
              "itemStruct": {
                "id": "7123456789012345678",
                "desc": "Partial photo post",
                "imagePost": { "images": [
                  { "imageURL": { "urlList": ["$first"] } },
                  { "imageURL": { "urlList": ["https://not-a-tiktok-cdn.example/image.jpeg"] } }
                ] }
              }
            }
        """.trimIndent()
        val html = """
            <html><body><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">$json</script></body></html>
        """.trimIndent()

        val result = TikTokPhotoParser.parseHtml(
            html,
            "https://www.tiktok.com/@otter_user/photo/7123456789012345678"
        )

        assertEquals(listOf(first), result.imageUrls)
        assertEquals(2, result.expectedCount)
        assertTrue(result.isPartial)
    }

    @Test
    fun onlyTiktokPhotoPermalinksAreTreatedAsPhotoMode() {
        assertTrue(TikTokPhotoParser.isPhotoModeUrl("https://www.tiktok.com/@someone/photo/7123456789012345678"))
        assertFalse(TikTokPhotoParser.isPhotoModeUrl("https://www.tiktok.com/@someone/video/7123456789012345678"))
        assertFalse(TikTokPhotoParser.isPhotoModeUrl("https://example.com/@someone/photo/7123456789012345678"))
    }

    @Test
    fun forwardedPhotoLinksMayDropTheUserSegmentButStillArePhotoMode() {
        // Web shares and forwards can carry /photo/<id> without the @user prefix.
        assertTrue(TikTokPhotoParser.isPhotoModeUrl("https://www.tiktok.com/photo/7123456789012345678?is_from_webapp=1"))
        assertTrue(TikTokPhotoParser.isPhotoModeUrl("https://www.tiktok.com/t/ZMabcdef/photo/7123456789012345678"))
        // A video permalink is never photo mode, whatever else the path carries.
        assertFalse(TikTokPhotoParser.isPhotoModeUrl("https://www.tiktok.com/video/7123456789012345678"))
    }
}
