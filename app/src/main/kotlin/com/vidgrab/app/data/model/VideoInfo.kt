package com.vidgrab.app.data.model

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
    val carouselImages: List<String> = emptyList(),
    val isCarousel: Boolean = false,
    val subtitleTracks: List<SubtitleTrack> = emptyList()
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

    companion object {
        val SUPPORTED_PLATFORMS = listOf(
            "YouTube", "Facebook", "Instagram", "TikTok", "Twitter/X",
            "Reddit", "Twitch", "Vimeo", "Dailymotion", "Snapchat",
            "Pinterest", "LinkedIn", "SoundCloud", "Bandcamp", "Bilibili",
            "Streamable", "Imgur", "9GAG", "VK", "Telegram",
            "Rumble", "Odysee", "PeerTube", "Mastodon", "Tumblr",
            "GettyImages", "NicoNico", "Coub", "GIPHY", "TED",
            "BBC", "CNN", "Fox News", "NBC", "CBS",
            "Bloomberg", "The Guardian", "Vice", "Vox", "Wired",
            "and 1000+ more via 𝘈𝘝® engine"
        )
    }
}
