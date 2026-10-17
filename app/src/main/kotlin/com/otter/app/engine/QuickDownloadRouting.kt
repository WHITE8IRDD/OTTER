package com.otter.app.engine

import com.otter.app.data.model.CarouselItem

/**
 * How the Quick Download window must treat a shared link, and what it sends to the
 * download service once the user picked files.
 *
 * The share sheet hands the app one URL and no other context, so the window has to
 * decide from the URL alone whether the post can be listed as files (Instagram posts
 * and TikTok photo-mode posts are galleries of signed CDN URLs) or is one video that
 * only the engine can extract (YouTube, TikTok videos, Vimeo, …). Keeping the rules
 * here — Android-free — is what lets the decision be unit tested against the URL
 * shapes the share sheets really produce.
 */
object QuickDownloadRouting {

    /** What the quick window should present for a shared link. */
    enum class Route {
        /** Instagram post / reel / tv: the window lists every slide of the post. */
        INSTAGRAM_GALLERY,

        /** TikTok photo-mode post: the window lists the post's still images. */
        TIKTOK_PHOTO,

        /** The URL is one image file: the window lists that single picture. */
        DIRECT_IMAGE,

        /** TikTok video post: one clip, downloaded through the engine. */
        TIKTOK_VIDEO,

        /** Everything else the engine owns: YouTube, Shorts, Vimeo, other sites. */
        ENGINE_VIDEO
    }

    /** True when the route's media is listed file-by-file and downloaded directly. */
    fun isGallery(route: Route): Boolean = when (route) {
        Route.INSTAGRAM_GALLERY, Route.TIKTOK_PHOTO, Route.DIRECT_IMAGE -> true
        Route.TIKTOK_VIDEO, Route.ENGINE_VIDEO -> false
    }

    fun route(url: String?): Route {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return Route.ENGINE_VIDEO
        // A URL that names one image file is that image, whatever host serves it.
        if (MediaUrlNormalizer.isDirectImageUrl(trimmed)) return Route.DIRECT_IMAGE
        if (InstagramUrl.isMediaUrl(trimmed)) return Route.INSTAGRAM_GALLERY
        val isTikTok = MediaUrlNormalizer.hostMatches(trimmed, "tiktok.com") ||
            MediaUrlNormalizer.hostMatches(trimmed, "tiktokv.com")
        if (isTikTok) {
            return if (TikTokPhotoParser.isPhotoModeUrl(trimmed)) Route.TIKTOK_PHOTO
            else Route.TIKTOK_VIDEO
        }
        return Route.ENGINE_VIDEO
    }

    /**
     * One download job of picked files: the window's selection turned into the
     * exact payload `DownloadService` needs. Photos and clips travel together —
     * both are plain GETs against the CDN URLs the post handed out.
     */
    data class MediaBatchSpec(
        /** Page the files came from, used as the Referer and as the History row's URL. */
        val sourceUrl: String,
        val title: String,
        val mediaUrls: List<String>,
        /** Per file: 0 = picture, 1 = video. Parallel to [mediaUrls]. */
        val mediaKinds: List<Int>,
        val uploader: String = "",
        val description: String = "",
        val hashtags: List<String> = emptyList(),
        val thumbnailUrl: String = ""
    ) {
        init {
            require(mediaUrls.size == mediaKinds.size) { "urls and kinds must be parallel lists" }
        }

        val size: Int get() = mediaUrls.size

        companion object {
            const val KIND_IMAGE = 0
            const val KIND_VIDEO = 1

            /** The picked indexes of [items], in post order, as one batch. */
            fun fromSelection(
                items: List<CarouselItem>,
                selected: Set<Int>,
                sourceUrl: String,
                title: String,
                uploader: String = "",
                description: String = "",
                hashtags: List<String> = emptyList(),
                thumbnailUrl: String = ""
            ): MediaBatchSpec? {
                val picked = selected.sorted().mapNotNull { index -> items.getOrNull(index) }
                if (picked.isEmpty()) return null
                return MediaBatchSpec(
                    sourceUrl = sourceUrl,
                    title = title,
                    mediaUrls = picked.map { it.url },
                    mediaKinds = picked.map { if (it.isVideo) KIND_VIDEO else KIND_IMAGE },
                    uploader = uploader,
                    description = description,
                    hashtags = hashtags,
                    thumbnailUrl = thumbnailUrl.ifBlank {
                        picked.firstOrNull { it.posterUrl.isNotBlank() }?.posterUrl.orEmpty()
                    }
                )
            }
        }

        fun toSlides(): List<MediaSlide> =
            mediaUrls.mapIndexed { index, url ->
                MediaSlide(url = url, isVideo = mediaKinds.getOrNull(index) == KIND_VIDEO)
            }
    }
}
