package com.otter.app.engine

import com.otter.app.data.model.CarouselItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Quick Download window opens on a URL the share sheet produced and nothing else,
 * so the route decision — gallery of files versus one engine-only clip — is pinned
 * here against exactly those URL shapes.
 */
class QuickDownloadRoutingTest {

    @Test
    fun instagramPostsOfEveryShareShapeAreGalleryRoutes() {
        val urls = listOf(
            "https://www.instagram.com/p/AbC123xYz_-/",
            "https://www.instagram.com/reel/AbC123xYz_-/",
            "https://www.instagram.com/reels/AbC123xYz_-/",
            "https://www.instagram.com/tv/AbC123xYz_-/",
            "https://www.instagram.com/share/reel/AbC123xYz_-/",
            "https://www.instagram.com/share/p/AbC123xYz_-/",
            "https://instagr.am/p/AbC123xYz_-/"
        )
        for (url in urls) {
            assertEquals(url, QuickDownloadRouting.Route.INSTAGRAM_GALLERY, QuickDownloadRouting.route(url))
            assertTrue(url, QuickDownloadRouting.isGallery(QuickDownloadRouting.route(url)))
        }
    }

    @Test
    fun instagramProfilesAndTagsAreNotTreatedAsPosts() {
        val profile = "https://www.instagram.com/otter_daily/"
        val tag = "https://www.instagram.com/explore/tags/otters/"
        assertEquals(QuickDownloadRouting.Route.ENGINE_VIDEO, QuickDownloadRouting.route(profile))
        assertEquals(QuickDownloadRouting.Route.ENGINE_VIDEO, QuickDownloadRouting.route(tag))
    }

    @Test
    fun tiktokPhotoModeIsAGalleryAndTiktokVideosGoToTheEngine() {
        val photo = "https://www.tiktok.com/@otter_user/photo/7123456789012345678"
        val photoNoUser = "https://www.tiktok.com/photo/7123456789012345678?is_from_webapp=1"
        val video = "https://www.tiktok.com/@otter_user/video/7123456789012345678"

        assertEquals(QuickDownloadRouting.Route.TIKTOK_PHOTO, QuickDownloadRouting.route(photo))
        assertEquals(QuickDownloadRouting.Route.TIKTOK_PHOTO, QuickDownloadRouting.route(photoNoUser))
        assertEquals(QuickDownloadRouting.Route.TIKTOK_VIDEO, QuickDownloadRouting.route(video))
        assertTrue(QuickDownloadRouting.isGallery(QuickDownloadRouting.route(photo)))
        assertFalse(QuickDownloadRouting.isGallery(QuickDownloadRouting.route(video)))
    }

    @Test
    fun directImageUrlsAreSingleFileGalleries() {
        val image = "https://example.com/media/pic_01.jpg?token=abc&width=1080"
        assertEquals(QuickDownloadRouting.Route.DIRECT_IMAGE, QuickDownloadRouting.route(image))
        assertTrue(QuickDownloadRouting.isGallery(QuickDownloadRouting.route(image)))
    }

    @Test
    fun youtubeLongFormShortsAndOtherSitesAreEngineRoutes() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://vimeo.com/123456789",
            "https://www.dailymotion.com/video/x8abcd"
        )
        for (url in urls) {
            assertEquals(url, QuickDownloadRouting.Route.ENGINE_VIDEO, QuickDownloadRouting.route(url))
            assertFalse(url, QuickDownloadRouting.isGallery(QuickDownloadRouting.route(url)))
        }
    }

    @Test
    fun batchSpecKeepsPickedFilesInPostOrderWithTheirKinds() {
        val items = listOf(
            CarouselItem("https://cdn.example/1.jpg", isVideo = false),
            CarouselItem("https://cdn.example/2.mp4", isVideo = true, posterUrl = "https://cdn.example/2p.jpg"),
            CarouselItem("https://cdn.example/3.jpg", isVideo = false)
        )
        // The user picked the clip and the last picture, out of order.
        val spec = QuickDownloadRouting.MediaBatchSpec.fromSelection(
            items = items,
            selected = setOf(2, 1),
            sourceUrl = "https://www.instagram.com/p/AbC123xYz_-/",
            title = "sunset walk",
            description = "sunset walk & coffee #beach",
            hashtags = listOf("#beach")
        )

        assertEquals(2, spec!!.size)
        // Sorted by index: the clip is saved as 2 and the picture as 3.
        assertEquals(listOf("https://cdn.example/2.mp4", "https://cdn.example/3.jpg"), spec.mediaUrls)
        assertEquals(
            listOf(QuickDownloadRouting.MediaBatchSpec.KIND_VIDEO, QuickDownloadRouting.MediaBatchSpec.KIND_IMAGE),
            spec.mediaKinds
        )
        assertEquals(listOf(true, false), spec.toSlides().map { it.isVideo })
        assertEquals("https://cdn.example/2p.jpg", spec.thumbnailUrl)
    }

    @Test
    fun emptySelectionMakesNoBatch() {
        assertNull(
            QuickDownloadRouting.MediaBatchSpec.fromSelection(
                items = listOf(CarouselItem("https://cdn.example/1.jpg", isVideo = false)),
                selected = emptySet(),
                sourceUrl = "https://example.com",
                title = "t"
            )
        )
    }
}
