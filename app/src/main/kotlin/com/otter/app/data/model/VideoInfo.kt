package com.otter.app.data.model

data class VideoInfo(
    val id: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumbnailUrl: String?,
    val originalUrl: String,
    val formats: List<VideoFormat>,
    val description: String?,
    val extractor: String = "",
    val isPlaylist: Boolean = false,
    val uploadDate: String = "",
    val viewCount: Long = 0L,
    val likeCount: Long = 0L,
    val channel: String = "",
    val playlistTitle: String = "",
    val playlistCount: Int = 0,
    val availability: String = "",
    val tags: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    /** Photo slides of a carousel / gallery post, in payload order. */
    val carouselImages: List<String> = emptyList(),
    /**
     * Video slides of the same post, in payload order. They travel through the
     * video download path; the photos above through the image path.
     */
    val carouselVideos: List<String> = emptyList(),
    /** Poster frame per entry of [carouselVideos] (blank when unknown). */
    val carouselVideoPosters: List<String> = emptyList(),
    /** Slides the source declared; larger than photos+videos means a partial read. */
    val carouselExpectedCount: Int = 0,
    val isCarousel: Boolean = false,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList()
) {
    val isYouTube: Boolean get() = extractor.lowercase().contains("youtube")
    val isTwitter: Boolean get() = extractor.lowercase().contains("twitter") || extractor.lowercase().contains("x.com")
    val isInstagram: Boolean get() = extractor.lowercase().contains("instagram")
    val isTikTok: Boolean get() = extractor.lowercase().contains("tiktok")
    val isFacebook: Boolean get() = extractor.lowercase().contains("facebook") || extractor.lowercase().contains("fb")
    val isReddit: Boolean get() = extractor.lowercase().contains("reddit")
    val isTwitch: Boolean get() = extractor.lowercase().contains("twitch")
    val isVimeo: Boolean get() = extractor.lowercase().contains("vimeo")
    val isSoundCloud: Boolean get() = extractor.lowercase().contains("soundcloud")
    val isBandcamp: Boolean get() = extractor.lowercase().contains("bandcamp")

    val platformName: String
        get() = when {
            isYouTube -> "YouTube"
            isTwitter -> "Twitter/X"
            isInstagram -> "Instagram"
            isTikTok -> "TikTok"
            isFacebook -> "Facebook"
            isReddit -> "Reddit"
            isTwitch -> "Twitch"
            isVimeo -> "Vimeo"
            isSoundCloud -> "SoundCloud"
            isBandcamp -> "Bandcamp"
            else -> extractor.ifEmpty { "Generic" }
        }

    /** Photos + videos the picker can offer. */
    val carouselMediaCount: Int get() = carouselImages.size + carouselVideos.size

    /** True when the source declared more slides than could be read. */
    val isCarouselPartial: Boolean
        get() = carouselMediaCount > 0 && carouselExpectedCount > carouselMediaCount

    companion object {
        val SUPPORTED_PLATFORMS = listOf(
            "YouTube", "Facebook", "Instagram", "Twitter/X", "TikTok"
        )
    }
}
