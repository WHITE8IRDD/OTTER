package com.otter.app.engine

import java.net.URL
import java.util.Locale

/**
 * What a media URL *is*, and how far up a result list it belongs.
 *
 * The complaint this exists for: pasting a video-page link produced dozens of page pictures and no
 * video, because the fallback scraper only knew how to find `img`/`og:image` and the picker shows
 * whatever it is handed. Ranking by file extension or pixel size alone is guesswork, so the score
 * below is built from the signals a page actually provides — which tag carried the URL, whether the
 * page calls itself a video, whether the URL's own path matches the page slug — plus cheap,
 * documented heuristics for the leftovers.
 *
 * Android-free on purpose: these rules are the part that has to stay correct as platforms change,
 * and they are unit tested here rather than by hand on a phone.
 */
object MediaClassifier {

    /** Where a candidate was found. Ordered by how strongly it points at "the media of this page". */
    enum class Source {
        /** `og:video`, `twitter:player:stream`, `music:song` — the page's declared media. */
        DECLARED,

        /** JSON-LD `contentUrl` / `embedUrl` / `VideoObject`. */
        STRUCTURED,

        /** A `<video>`/`<source>` element in the markup. */
        VIDEO_TAG,

        /** `<link rel=alternate type=application/x-mpegURL …>` and similar manifest links. */
        MANIFEST_LINK,

        /** A carousel/attachment item of the same post. */
        CAROUSEL,

        /** An `img`/`srcset`/`og:image` picture. */
        IMAGE_TAG,

        /** A bare URL found in the page text with no surrounding context. */
        RAW
    }

    enum class MediaKind { PRIMARY_VIDEO, VIDEO, MANIFEST, AUDIO, GALLERY_IMAGE, IMAGE, THUMBNAIL, PREVIEW, UNUSABLE }

    data class Candidate(
        val url: String,
        val kind: MediaKind,
        val source: Source,
        val width: Int = 0,
        val height: Int = 0,
        val posterUrl: String = "",
        /** Set when the page declared this URL as its own media (`og:video` and friends). */
        val declaredByPage: Boolean = false
    )

    private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "flv", "ts")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "weba")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "heic", "heif")
    private val manifestExtensions = setOf("m3u8", "mpd", "f4m", "ism", "m3u")

    /** Paths that are UI furniture, not media, whatever the extension says. */
    private val decorativePathParts = listOf(
        "/icons/", "/icon/", "/avatar/", "/avatars/", "/emoji/", "/emoticon/", "/sprite",
        "/logo", "/badge", "/button", "/buttons/", "/static/", "/rsrc.php", "/assets/img/ui",
        "profile_pic", "/favicons/", "/favicon", "spacer", "blank.", "pixel", "beacon",
        "placeholder", "skeleton", "shimmer", "/1x1/", "loading", "/watermark-"
    )

    /** Name fragments that mark a *reduced* copy of a real media file. */
    private val thumbnailNameParts = listOf(
        "-thumb", "_thumb", "thumbnail", "-preview", "_preview", "-small", "_small",
        "-tiny", "_tiny", "-mini", "-teaser", "-avatar", "-poster-1x", "/sq", "small_", "thumb_"
    )

    /** Resolutions that are never the media a user pasted a link for. */
    private val tinyImageEdge = 96

    fun extension(url: String): String = runCatching {
        URL(url).path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase(Locale.ROOT)
    }.getOrDefault("")

    fun isImageUrl(url: String): Boolean = extension(url) in imageExtensions

    fun isVideoUrl(url: String): Boolean = extension(url) in videoExtensions

    fun isAudioUrl(url: String): Boolean = extension(url) in audioExtensions

    /** A streaming manifest: it has to go through the engine, never through a plain GET. */
    fun isManifestUrl(url: String): Boolean = extension(url) in manifestExtensions ||
        runCatching { URL(url).query.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
            .let { it.contains("format=m3u8") || it.contains("fmt=mpd") || it.contains("type=m3u8") }

    /**
     * True when a plain GET writes a complete, playable file. Manifests are excluded on purpose:
     * downloading one with `HttpURLConnection` produces a playlist of text, which is exactly the
     * kind of "success" that turns into an unplayable file later.
     */
    fun isDirectlyDownloadable(url: String): Boolean =
        isVideoUrl(url) || isAudioUrl(url) || isImageUrl(url)

    /** A URL that carries no media a user would want: icons, avatars, tracking pixels, placeholders. */
    fun isDecorative(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (decorativePathParts.any { lower.contains(it) }) return true
        val extension = extension(url)
        // SVG and gif without a size hint are almost always UI art or a loader animation, but a
        // .gif in a media CDN is real content, so only the flat icons path is filtered by type.
        if (extension == "svg" && !lower.contains("media") && !lower.contains("photo")) return true
        return false
    }

    fun looksLikeThumbnail(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (thumbnailNameParts.any { lower.contains(it) }) return true
        // Instagram/CDN size suffixes: …/140x140/…, …w240/…, -220x220.
        val sizeMatch = Regex("""[/_-](\d{1,4})x(\d{1,4})(?:[/.]|$)""").find(lower)
        return sizeMatch?.let { match ->
            val longest = maxOf(
                match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0,
                match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            )
            longest in 1..tinyImageEdge
        } ?: false
    }

    /** The kind of one URL, given where it was found. */
    fun kindOf(url: String, source: Source, width: Int = 0, height: Int = 0): MediaKind {
        if (isDecorative(url)) return MediaKind.UNUSABLE
        val isSmall = width in 1..tinyImageEdge || height in 1..tinyImageEdge || looksLikeThumbnail(url)
        return when {
            isManifestUrl(url) -> if (source == Source.MANIFEST_LINK || source == Source.VIDEO_TAG ||
                source == Source.DECLARED || source == Source.STRUCTURED
            ) MediaKind.MANIFEST else MediaKind.VIDEO

            isVideoUrl(url) -> MediaKind.VIDEO
            isAudioUrl(url) -> MediaKind.AUDIO
            isSmall && source != Source.CAROUSEL -> MediaKind.THUMBNAIL
            source == Source.CAROUSEL && isImageUrl(url) -> MediaKind.GALLERY_IMAGE
            isImageUrl(url) -> if (source == Source.DECLARED || source == Source.STRUCTURED) {
                MediaKind.PREVIEW
            } else {
                MediaKind.IMAGE
            }

            else -> MediaKind.UNUSABLE
        }
    }

    /**
     * Sort order the picker uses: the video the link points at, then other videos, then the
     * attachments of the same post, then loose pictures, then thumbnails. Ties keep the order the
     * page listed them in, because a carousel's own order is meaningful.
     */
    private val kindWeight = mapOf(
        MediaKind.PRIMARY_VIDEO to 1000,
        MediaKind.VIDEO to 900,
        MediaKind.MANIFEST to 850,
        MediaKind.AUDIO to 700,
        MediaKind.GALLERY_IMAGE to 500,
        MediaKind.PREVIEW to 300,
        MediaKind.IMAGE to 200,
        MediaKind.THUMBNAIL to 100,
        MediaKind.UNUSABLE to -1
    )

    private val sourceWeight = mapOf(
        Source.DECLARED to 60,
        Source.STRUCTURED to 50,
        Source.CAROUSEL to 40,
        Source.VIDEO_TAG to 30,
        Source.MANIFEST_LINK to 28,
        Source.IMAGE_TAG to 10,
        Source.RAW to 0
    )

    fun score(candidate: Candidate, pageUrl: String = ""): Int {
        var score = kindWeight[candidate.kind] ?: 0
        score += sourceWeight[candidate.source] ?: 0
        if (candidate.declaredByPage) score += 120
        if (candidate.width > 0 || candidate.height > 0) {
            val longest = maxOf(candidate.width, candidate.height)
            score += when {
                longest >= 1920 -> 40
                longest >= 1080 -> 30
                longest >= 640 -> 20
                longest >= 320 -> 10
                else -> -30
            }
        }
        // A media file whose path repeats a token of the page slug is very likely this page's own.
        val pageTokens = tokens(pageUrl)
        if (pageTokens.isNotEmpty()) {
            val mediaTokens = tokens(candidate.url)
            score += 25 * mediaTokens.count { it in pageTokens }.coerceAtMost(2)
        }
        return score
    }

    private fun tokens(url: String): Set<String> = runCatching {
        URL(url).path.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Classifies and orders candidates, dropping the unusable ones. The first video that the page
     * declared (or the first video at all when none was declared) is promoted to
     * [MediaKind.PRIMARY_VIDEO] so it cannot be buried under the page's pictures.
     */
    fun rank(candidates: List<Candidate>, pageUrl: String = ""): List<Candidate> {
        val usable = candidates
            .filter { it.kind != MediaKind.UNUSABLE }
            .distinctBy { identityOf(it.url) }
        val promoted = promotePrimaryVideo(usable)
        // Sorting a numbered list keeps the page's own order for equal scores, which is what a
        // carousel's slide order is.
        return promoted.mapIndexed { index, candidate -> Triple(score(candidate, pageUrl), index, candidate) }
            .sortedWith { left, right ->
                val byScore = right.first.compareTo(left.first)
                if (byScore != 0) byScore else left.second.compareTo(right.second)
            }
            .map { it.third }
    }

    /** Identity of a media URL: host + path. Query strings differ per delivery edge and per signature. */
    fun identityOf(url: String): String {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return url.substringBefore('?')
        return "${parsed.host.lowercase(Locale.ROOT)}${parsed.path}"
    }

    /**
     * First video the page declared as its own becomes the primary result. A manifest is never
     * promoted, because [MediaKind.PRIMARY_VIDEO] also means "a plain GET of this URL is the video"
     * and a manifest needs the engine to resolve its segments.
     */
    private fun promotePrimaryVideo(candidates: List<Candidate>): List<Candidate> {
        val declared = candidates.indexOfFirst {
            it.kind == MediaKind.VIDEO && (it.declaredByPage || it.source == Source.DECLARED || it.source == Source.STRUCTURED)
        }
        val primaryIndex = declared.takeIf { it >= 0 }
            ?: candidates.indexOfFirst { it.kind == MediaKind.VIDEO }
        if (primaryIndex < 0) return candidates
        return candidates.mapIndexed { index, candidate ->
            if (index == primaryIndex) candidate.copy(kind = MediaKind.PRIMARY_VIDEO) else candidate
        }
    }

    /**
     * Whether a result list is about a video, which decides both the picker's section order and the
     * wording used for it. Pictures must not be presented as if the video had been found.
     */
    fun isVideoPrimary(candidates: List<Candidate>): Boolean =
        candidates.firstOrNull()?.let { it.kind == MediaKind.PRIMARY_VIDEO || it.kind == MediaKind.VIDEO } == true
}
