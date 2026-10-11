package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Instagram carousel bugs from the report:
 * the picker showed a single tile ("1/1 selected") because carousel slides were
 * lost while parsing, and previews/downloads failed because the signed query
 * string of each CDN URL was dropped, which makes Instagram's CDN answer 403.
 *
 * The payloads mirror the real shapes: the current web payload
 * (`xdt_api__v1__media__shortcode__web_info`), the legacy GraphQL sidecar and a
 * carousel whose slides are only partially readable.
 */
class InstagramMediaParserTest {

    private val postUrl =
        "https://www.instagram.com/p/DeEo7SjjLYz/?img_index=2&mdxt=MTB3NWI3aGFiYWg3eQ=="

    private val payloadSixSlides = """{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"require":[["RelayPrefetchedStreamCache","next",[],[{"__bbox":{"result":{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{"pk":"4000502365245191731","code":"DeEo7SjjLYz","media_type":8,"carousel_media":[{"pk":"4000502365245191701","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191702","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191703","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191704","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide4-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide4.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191705","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide5-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide5.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191706","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide6-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide6.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}]}]}}}}}]]]}}]]]}"""

    private val payloadThreeSlides = """{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"require":[["RelayPrefetchedStreamCache","next",[],[{"__bbox":{"result":{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{"code":"DeEo7SjjLYz","media_type":8,"carousel_media":[{"pk":"4000502365245191701","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191702","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191703","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}]}]}}}}}]]]}}]]]}"""

    private fun pageWithPayload(payload: String, extra: String = ""): String = """
        <!DOCTYPE html><html><head>
        <meta property="og:image" content="https://scontent-frt3-1.cdninstagram.com/v/t51.2885-15/slide1.jpg?oe=67AA0001&amp;_nc_ht=scontent-frt3-1.cdninstagram.com">
        </head><body>
        <script type="application/json" data-sjs>$payload</script>
        $extra
        </body></html>
    """.trimIndent()

    @Test
    fun readsEveryCarouselSlideInPayloadOrderWithSignedQueryStrings() {
        val result = InstagramMediaParser.parseHtml(pageWithPayload(payloadSixSlides), postUrl)

        assertEquals(6, result.imageUrls.size)
        assertEquals(6, result.expectedCount)
        assertTrue(result.isComplete)
        assertTrue(result.imageUrls.all { it.contains("?stp=") && it.contains("oe=") })
        // Slide order follows the payload, not the order URLs appear in the HTML.
        assertTrue(result.imageUrls[0].contains("slide1.jpg"))
        assertTrue(result.imageUrls[5].contains("slide6.jpg"))
        // The largest candidate of each slide wins.
        assertTrue(result.imageUrls.none { it.contains("-640.jpg") })
        assertEquals("DeEo7SjjLYz", result.shortcode)
    }

    @Test
    fun keepsLegacyGraphqlSidecarOrder() {
        val json = """{"graphql":{"shortcode_media":{
            "display_url":"https://scontent-x.cdninstagram.com/v/t51.2885-15/one.jpg?oe=9",
            "edge_sidecar_to_children":{"edges":[
              {"node":{"display_url":"https://scontent-x.cdninstagram.com/v/t51.2885-15/one.jpg?oe=1"}},
              {"node":{"display_url":"https://scontent-x.cdninstagram.com/v/t51.2885-15/two.jpg?oe=2"}},
              {"node":{"display_url":"https://scontent-x.cdninstagram.com/v/t51.2885-15/three.jpg?oe=3"}}]}}}}"""

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(3, result.imageUrls.size)
        assertEquals(3, result.expectedCount)
        assertTrue(result.imageUrls[0].endsWith("one.jpg?oe=1"))
        assertTrue(result.imageUrls[2].endsWith("three.jpg?oe=3"))
    }

    @Test
    fun decodesJsEscapesSoCdnUrlsStayValid() {
        val json = """{"items":[{"media_type":1,"image_versions2":{"candidates":[
            {"width":1080,"url":"https://scontent.cdninstagram.com\/v\/t51.2885-15\/escaped.jpg?stp=dst\u0026oe=67BB"}]}}]}"""

        val result = InstagramMediaParser.parseJson(json)

        assertEquals(1, result.imageUrls.size)
        assertTrue(result.imageUrls[0].contains("&oe=67BB"))
        assertTrue(!result.imageUrls[0].contains("\\u0026"))
        assertTrue(!result.imageUrls[0].contains("\\/"))
    }

    @Test
    fun doesNotDuplicateSlidesAcrossRepeatedPayloads() {
        // Instagram repeats the same payload in several script blocks.
        val page = pageWithPayload(payloadSixSlides, extra = pageWithPayload(payloadSixSlides))

        val result = InstagramMediaParser.parseHtml(page, postUrl)

        assertEquals(6, result.imageUrls.size)
        assertEquals(6, result.expectedCount)
    }

    @Test
    fun fallsBackToOpenGraphImageAndKeepsItsSignature() {
        val html = """
            <html><head>
            <meta property="og:image" content="https://scontent-frt3-1.cdninstagram.com/v/t51.2885-15/single.jpg?oe=67AA&amp;_nc_ht=scontent-frt3-1.cdninstagram.com">
            </head><body>no payload</body></html>
        """.trimIndent()

        val result = InstagramMediaParser.parseHtml(html, postUrl)

        assertEquals(1, result.imageUrls.size)
        assertTrue(result.imageUrls[0].contains("?oe=67AA&_nc_ht="))
    }

    @Test
    fun reportsPartialReadInsteadOfCallingItAOnePhotoPost() {
        // Eight declared slides, only three of them readable.
        val partial = """{"__bbox":{"result":{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{"code":"DeEo7SjjLYz","media_type":8,"carousel_media":[{"pk":"4000502365245191701","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191702","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"pk":"4000502365245191703","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}},{"media_type":2,"video_versions":[]},{"media_type":2,"video_versions":[]},{"media_type":2,"video_versions":[]},{"media_type":2,"video_versions":[]},{"media_type":2,"video_versions":[]}]}]}}}}}"""
        val page = "<html><body><script type=\"application/json\" data-sjs>$partial</script></body></html>"

        val result = InstagramMediaParser.parseHtml(page, postUrl)

        assertEquals(3, result.imageUrls.size)
        assertEquals(8, result.expectedCount)
        assertFalse(result.isComplete)
        assertTrue(result.note ?: "", (result.note ?: "").contains("8"))
    }

    @Test
    fun treatsPayloadWithDeclaredSlideCountAsCompleteWhenAllSlidesRead() {
        val result = InstagramMediaParser.parseHtml(pageWithPayload(payloadThreeSlides), postUrl)

        assertEquals(3, result.imageUrls.size)
        assertEquals(3, result.expectedCount)
        assertTrue(result.isComplete)
    }

    @Test
    fun detectsLoginWallPages() {
        assertTrue(InstagramMediaParser.looksLikeLoginWall("""<form id="loginForm">Log in</form>"""))
        assertFalse(InstagramMediaParser.looksLikeLoginWall(pageWithPayload(payloadThreeSlides)))
    }

    @Test
    fun ignoresProfilePicturesAndScriptAssets() {
        val html = """
            <html><body>
            <img src="https://scontent.cdninstagram.com/v/t51.2885-19/profile.jpg?oe=1">
            <img src="https://static.cdninstagram.com/rsrc.php/v3/yb/r/abc.png">
            <img src="https://scontent.cdninstagram.com/v/t51.2885-15/real.jpg?oe=2">
            </body></html>
        """.trimIndent()

        val result = InstagramMediaParser.parseHtml(html, postUrl)

        assertEquals(1, result.imageUrls.size)
        assertTrue(result.imageUrls[0].contains("real.jpg"))
    }

    @Test
    fun derivesShortcodeAndMediaIdFromTheSharedUrl() {
        assertEquals("DeEo7SjjLYz", InstagramMediaParser.shortcodeFromUrl(postUrl))
        assertEquals(4000502365245191731L, InstagramMediaParser.mediaIdFromShortcode("DeEo7SjjLYz"))
    }


    // ------------------------------------------------- crawler / server-rendered shapes

    /** Googlebot-style page: slides wrapped in `node`, the total declared up front. */
    private val payloadCrawlerCarousel = """{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"result":{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{"node":{"pk":"4000502365245191731","code":"DeEo7SjjLYz","media_type":8,"carousel_media_count":20,"carousel_media":[{"node":{"pk":"3985268573165356001","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356002","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356003","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356004","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide4-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide4.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356005","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide5-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide5.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356006","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide6-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide6.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356007","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide7-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide7.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356008","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide8-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide8.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356009","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide9-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide9.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356010","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide10-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide10.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356011","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide11-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide11.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356012","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide12-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide12.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356013","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide13-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide13.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356014","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide14-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide14.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356015","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide15-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide15.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356016","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide16-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide16.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356017","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide17-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide17.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356018","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide18-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide18.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356019","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide19-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide19.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}},{"node":{"pk":"3985268573165356020","media_type":1,"image_versions2":{"candidates":[{"width":640,"height":800,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide20-640.jpg?stp=dst-jpg&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"},{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide20.jpg?stp=dst-jpg_e35&_nc_ht=instagram.fabc1-1.fna.fbcdn.net&oe=67AA0001"}]}}}]}}]}}}}}]]]}"""

    /** A page that carries another carousel (a "more posts" preview) before the post. */
    private val payloadWithRelatedCarousel = """{"data":{"suggested_posts":[{"pk":"3999999999999999999","code":"RelatedXyz","media_type":8,"carousel_media_count":3,"carousel_media":[{"node":{"pk":"39999999001","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/related1.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"39999999002","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/related2.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"39999999003","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/related3.jpg?stp=dst-jpg&oe=67AA0001"}]}}}]}],"xdt_api__v1__media__shortcode__web_info":{"items":[{"pk":"4000502365245191731","code":"DeEo7SjjLYz","media_type":8,"carousel_media_count":5,"carousel_media":[{"node":{"pk":"40005023001","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide1.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"40005023002","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide2.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"40005023003","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide3.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"40005023004","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide4.jpg?stp=dst-jpg&oe=67AA0001"}]}}},{"node":{"pk":"40005023005","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/slide5.jpg?stp=dst-jpg&oe=67AA0001"}]}}}]}]}}}"""

    private val payloadSinglePhoto = """{"data":{"xdt_api__v1__media__shortcode__web_info":{"items":[{"pk":"4000502365245191731","code":"DeEo7SjjLYz","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/main.jpg?stp=dst-jpg_e35&oe=67AA0009"}]}}]},"suggested_posts":[{"pk":"1234567890123456789","media_type":1,"image_versions2":{"candidates":[{"width":1080,"height":1080,"url":"https://instagram.fabc1-1.fna.fbcdn.net/v/t51.2885-15/suggested1.jpg?stp=dst-jpg&oe=67CC0001"}]}}]}}"""

    @Test
    fun readsCrawlerPayloadWithNodeWrappedSlides() {
        val result = InstagramMediaParser.parseHtml(pageWithPayload(payloadCrawlerCarousel), postUrl)

        assertEquals(20, result.imageUrls.size)
        assertEquals(20, result.expectedCount)
        assertTrue(result.isComplete)
        assertTrue(result.imageUrls[0].contains("slide1.jpg"))
        assertTrue(result.imageUrls[19].contains("slide20.jpg"))
        assertTrue(result.imageUrls.none { it.contains("-640.jpg") })
        assertTrue(result.imageUrls.all { it.contains("?stp=") })
    }

    @Test
    fun prefersTheRequestedPostOverOtherCarouselsOnThePage() {
        val result = InstagramMediaParser.parseHtml(pageWithPayload(payloadWithRelatedCarousel), postUrl)

        assertEquals(5, result.imageUrls.size)
        assertEquals(5, result.expectedCount)
        assertTrue("related post pictures leaked into the picker: ${result.imageUrls}",
            result.imageUrls.none { it.contains("related") })
        assertTrue(result.imageUrls[0].contains("slide1.jpg"))
    }

    @Test
    fun singlePhotoPostIsNotMixedWithSuggestedPictures() {
        val result = InstagramMediaParser.parseHtml(pageWithPayload(payloadSinglePhoto), postUrl)

        assertEquals(1, result.imageUrls.size)
        assertEquals(1, result.expectedCount)
        assertTrue(result.imageUrls[0].contains("main.jpg"))
    }

    @Test
    fun findsCarouselUrlsInsideEscapedInlineJavaScript() {
        val html = """
            <!DOCTYPE html><html><body><script>
            var data = {"one":"https:\/\/scontent-frt3-1.cdninstagram.com\/v\/t51.2885-15\/one.jpg?oe=67AA0001&_nc_ht=scontent-frt3-1.cdninstagram.com",
            "two":"https:\/\/scontent-frt3-1.cdninstagram.com\/v\/t51.2885-15\/two.jpg?oe=67AA0002"};
            </script></body></html>
        """.trimIndent()

        val result = InstagramMediaParser.parseHtml(html, postUrl)

        assertEquals(2, result.imageUrls.size)
        assertTrue(result.imageUrls.all { it.contains("?oe=") && !it.contains("\\/") })
    }
}
