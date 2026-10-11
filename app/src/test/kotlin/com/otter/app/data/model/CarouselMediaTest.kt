package com.otter.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker counter and the partial-read warning both work on photos + videos. */
class CarouselMediaTest {

    private fun post(
        photos: Int = 0,
        videos: Int = 0,
        expected: Int = 0
    ) = VideoInfo(
        id = "1",
        title = "Post",
        uploader = "",
        duration = 0L,
        thumbnailUrl = null,
        originalUrl = "https://www.instagram.com/p/abc/",
        formats = emptyList(),
        description = "caption #one #two",
        carouselImages = (1..photos).map { "https://cdn.example/photo$it.jpg" },
        carouselVideos = (1..videos).map { "https://cdn.example/video$it.mp4" },
        carouselExpectedCount = expected
    )

    @Test
    fun slideCountCoversPhotosAndVideosTogether() {
        val info = post(photos = 4, videos = 2, expected = 6)

        assertEquals(6, info.carouselMediaCount)
        assertFalse(info.isCarouselPartial)
    }

    @Test
    fun fewerSlidesThanDeclaredIsAPartialRead() {
        val info = post(photos = 3, videos = 1, expected = 8)

        assertEquals(4, info.carouselMediaCount)
        assertTrue(info.isCarouselPartial)
    }

    @Test
    fun anUnknownDeclaredCountIsNeverPartial() {
        val info = post(photos = 2, videos = 1, expected = 0)

        assertEquals(3, info.carouselMediaCount)
        assertFalse(info.isCarouselPartial)
    }

    @Test
    fun videoOnlyCarouselCountsItsSlides() {
        val info = post(videos = 3, expected = 3)

        assertEquals(3, info.carouselMediaCount)
        assertTrue(info.carouselImages.isEmpty())
        assertFalse(info.isCarouselPartial)
    }
}
