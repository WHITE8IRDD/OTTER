package com.otter.app.engine

import java.net.URL
import java.util.Locale

/**
 * Recognition of the Instagram URLs the app can actually extract media from.
 *
 * Three call sites used to ask this question with three slightly different string tests, and the
 * disagreement is what broke single-video posts: the scraper, the reel exclusion in the fetch
 * pipeline and the share-sheet routing each accepted a different subset. Instagram shares a
 * one-video post as `/reel/<code>/` (or `/share/reel/<code>/` when it is forwarded from the app),
 * so every test that only accepted `/p/` handed reels to the engine's `instagram` extractor, which
 * answers a logged-out request with a login wall, and the post failed in both download workflows.
 *
 * This object is the single source of truth, and it is Android-free so the rules are unit tested
 * against the URL shapes the share sheet really produces.
 */
object InstagramUrl {

    /** A URL that names one media item, as opposed to a profile, tag or story. */
    enum class Kind { POST, REEL, CHANNEL, TAG, OTHER }

    data class Parsed(val kind: Kind, val shortcode: String, val canonicalUrl: String)

    /**
     * `/p/` and `/tv/` are picture-or-video posts, `/reel/` and `/reels/` are reels, and every
     * form also exists behind `/share/` when the link is forwarded. Instagram itself redirects
     * `/share/...` to the plain form, so the shortcode after it belongs to this post.
     */
    private val pathRegex = Regex(
        """^/(?:share/)?(p|reel|reels|tv)/([A-Za-z0-9_-]{4,})(?:[/?#]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val channelRegex = Regex("""^/(?:share/)?([A-Za-z0-9._]{1,30})/?$""")
    private val tagRegex = Regex("""^/(?:share/)?explore/tags/([^/?#]+)""", RegexOption.IGNORE_CASE)

    fun hostMatches(url: String): Boolean =
        MediaUrlNormalizer.hostMatches(url, "instagram.com") ||
            MediaUrlNormalizer.hostMatches(url, "instagr.am")

    /** The post kind and shortcode of an Instagram URL, or null when it is not a media link. */
    fun parse(url: String?): Parsed? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || !hostMatches(trimmed)) return null
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val path = parsed.path.ifEmpty { "/" }
        val match = pathRegex.find(path) ?: return null
        val kind = when (match.groupValues[1].lowercase(Locale.ROOT)) {
            "p", "tv" -> Kind.POST
            else -> Kind.REEL
        }
        val shortcode = match.groupValues[2]
        if (shortcode.isBlank()) return null
        return Parsed(kind = kind, shortcode = shortcode, canonicalUrl = canonicalUrl(kind, shortcode))
    }

    fun kind(url: String?): Kind = parse(url)?.kind ?: run {
        val path = runCatching { URL(url.orEmpty()).path }.getOrNull().orEmpty()
        when {
            tagRegex.containsMatchIn(path) -> Kind.TAG
            channelRegex.matches(path) -> Kind.CHANNEL
            else -> Kind.OTHER
        }
    }

    /** True for the URLs whose page carries one post (single item, sidecar or reel). */
    fun isMediaUrl(url: String?): Boolean = parse(url) != null

    /**
     * The URL to request. The kind is kept because it changes what the page renders: a reel URL
     * produces a `<video>` player, while asking for `/p/<reel shortcode>/ can come back as the
     * share redirect page with no media in it.
     */
    fun canonicalUrl(kind: Kind, shortcode: String): String {
        val segment = if (kind == Kind.REEL) "reel" else "p"
        return "https://www.instagram.com/$segment/$shortcode/"
    }

    fun canonicalUrl(url: String?): String? = parse(url)?.canonicalUrl
}
