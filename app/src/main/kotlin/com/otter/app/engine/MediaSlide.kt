package com.otter.app.engine

/**
 * One slide of a gallery / carousel post: the URL the CDN handed out (still carrying
 * its signed query string) and whether that slide is a video.
 */
data class MediaSlide(
    val url: String,
    val isVideo: Boolean
)
