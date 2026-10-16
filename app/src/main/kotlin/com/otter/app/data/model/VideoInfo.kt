package com.otter.app.data.model

/**
 * One selectable item of a gallery or carousel post, in the order the source listed it.
 *
 * The picker and the saved file names both work on this list rather than on "the photos" and
 * "the videos" separately, because a post that shows a picture, a video and two more pictures is
 * one ordered set of items and not two groups. [posterUrl] is the still frame of a video item,
 * which is what lets a video be previewed before it is chosen.
 */
data class CarouselItem(
    val url: String,
    val isVideo: Boolean,
    val posterUrl: String = "",
    /** "16:9 1080p", "MP4", … when the source said so; shown as the item's format line. */
    val detail: String = ""
)

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
    /**
     * The post's items in the source's own order, typed photo or video. Empty when the read could
     * not preserve the order, in which case [carouselMedia] falls back to the grouped lists.
     */
    val carouselItems: List<CarouselItem> = emptyList(),
    /**
     * True when the media this URL points at is a video, so the picker leads with the video the
     * user came for instead of with the page's pictures.
     */
    val carouselPrimaryIsVideo: Boolean = false,
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

    /**
     * Everything the picker may offer, in one ordered list. When the source's order was kept it is
     * used verbatim; otherwise pictures and videos are grouped, with the primary kind first.
     */
    val carouselMedia: List<CarouselItem>
        get() {
            if (carouselItems.isNotEmpty()) return carouselItems
            val photos = carouselImages.map { CarouselItem(it, isVideo = false) }
            val clips = carouselVideos.mapIndexed { index, url ->
                CarouselItem(url, isVideo = true, posterUrl = carouselVideoPosters.getOrNull(index).orEmpty())
            }
            return if (carouselPrimaryIsVideo) clips + photos else photos + clips
        }

    /** Preview to show for an item: its own frame for a video, the file for a picture. */
    fun previewFor(index: Int): String {
        val item = carouselMedia.getOrNull(index) ?: return ""
        return if (item.isVideo) item.posterUrl.ifBlank { item.url } else item.url
    }

    /** How many of the offered items are videos. */
    val carouselVideoItemCount: Int get() = carouselMedia.count { it.isVideo }

    /**
     * True when the read covers everything the source declared. A partial read is never reused from
     * the result cache: the missing slides may arrive on the next attempt.
     */
    val isCarouselComplete: Boolean
        get() = carouselExpectedCount == 0 || carouselMediaCount >= carouselExpectedCount

    /** True when the source declared more slides than could be read. */
    val isCarouselPartial: Boolean
        get() = carouselMediaCount > 0 && carouselExpectedCount > carouselMediaCount

    companion object {
        val SUPPORTED_PLATFORMS = listOf(
            "YouTube", "Facebook", "Instagram", "Twitter/X", "TikTok"
        )
    }
}
