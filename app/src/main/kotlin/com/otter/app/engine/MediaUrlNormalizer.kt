package com.otter.app.engine

import java.net.URL
import java.util.Locale

/** URL helpers shared by extraction and download code. */
object MediaUrlNormalizer {
    private val youtubeVideoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "tif", "tiff", "svg")

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
}
