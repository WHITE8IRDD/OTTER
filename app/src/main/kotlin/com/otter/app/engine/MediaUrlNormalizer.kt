package com.otter.app.engine

import java.net.URL
import java.util.Locale

/** URL helpers shared by extraction and download code. */
object MediaUrlNormalizer {
    const val TIKTOK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.144 Mobile Safari/537.36"

    private val youtubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "tif", "tiff", "svg")
    private val publicExtractorDomains = setOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "instagram.com", "instagr.am", "tiktok.com", "tiktokv.com",
        "facebook.com", "fb.com", "fb.watch", "twitter.com", "x.com", "t.co",
        "reddit.com", "redd.it", "twitch.tv", "vimeo.com", "dailymotion.com", "dai.ly",
        "soundcloud.com", "bilibili.com", "pinterest.com", "pin.it", "snapchat.com",
        "streamable.com", "bandcamp.com"
    )

    /**
     * Shorts are ordinary YouTube videos, but the watch URL is more consistently
     * understood by extractors and download clients (including older Android builds).
     * Tracking query parameters on shared Shorts links are intentionally discarded.
     */
    fun normalizeYouTubeShortsUrl(url: String): String {
        val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return url
        if (parsed.protocol != "http" && parsed.protocol != "https") return url

        val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return url

        val path = parsed.path.trimEnd('/')
        val match = Regex("^/shorts/([^/]+)(?:/.*)?$", RegexOption.IGNORE_CASE).matchEntire(path) ?: return url
        val videoId = match.groupValues[1]
        if (!youtubeVideoId.matches(videoId)) return url

        return "https://www.youtube.com/watch?v=$videoId"
    }

    /**
     * The 11-character video id of a watch, Shorts or youtu.be URL, blank for anything
     * else. Used to name the media yt-dlp produces, so the file a download wrote can be
     * told apart from the other files already sitting in the download folder.
     */
    fun youtubeVideoId(url: String): String {
        val parsed = runCatching { URL(url.trim()) }.getOrNull() ?: return ""
        val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            return parsed.path.trim('/').substringBefore('/').takeIf { youtubeVideoId.matches(it) }.orEmpty()
        }
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return ""
        Regex("^/shorts/([^/]+)", RegexOption.IGNORE_CASE).find(parsed.path)?.let { match ->
            return match.groupValues[1].takeIf { youtubeVideoId.matches(it) }.orEmpty()
        }
        val value = parsed.query?.split('&')
            ?.firstOrNull { it.startsWith("v=") }
            ?.removePrefix("v=")
        return value?.takeIf { youtubeVideoId.matches(it) }.orEmpty()
    }

    /** True for a direct image URL, including URLs with signed/query parameters. */
    fun isDirectImageUrl(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        if ((parsed.protocol != "http" && parsed.protocol != "https") || parsed.host.isBlank()) return false
        val extension = parsed.path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    fun imageExtensionFromUrl(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        if ((parsed.protocol != "http" && parsed.protocol != "https") || parsed.host.isBlank()) return null
        val extension = parsed.path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return extension.takeIf { it in imageExtensions }
    }

    fun host(url: String): String? = runCatching {
        URL(url).takeIf { (it.protocol == "http" || it.protocol == "https") && it.host.isNotBlank() }
            ?.host?.lowercase(Locale.ROOT)?.trimEnd('.')
    }.getOrNull()

    fun hostMatches(url: String, domain: String): Boolean {
        val host = host(url) ?: return false
        val expected = domain.lowercase(Locale.ROOT).trimStart('.')
        return host == expected || host.endsWith(".$expected")
    }

    /**
     * The user's saved cookie jar, applied or not.
     *
     * It used to be attached to every request whatever Settings said: `shouldUseOptionalCookies`
     * returned `true` and its KDoc described a per-host policy no code implemented. The jar is the
     * user's session, so the only defensible default is the one the switch shows - nothing leaves
     * the app until cookies are turned on. The app's own page parsers (Instagram, TikTok photo
     * mode) never read the jar at all, which is what keeps a public post reading as a public post.
     */
    fun shouldAttachSavedCookies(url: String, settingEnabled: Boolean): Boolean =
        settingEnabled && runCatching { URL(url) }.getOrNull()
            ?.let { it.protocol == "http" || it.protocol == "https" } == true

    /** The hosts OTTER advertises support for, as data, so the docs and the code cannot drift. */
    val supportedHosts: Set<String> get() = publicExtractorDomains
}
