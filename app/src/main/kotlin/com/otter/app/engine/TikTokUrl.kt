package com.otter.app.engine

import java.net.URL
import java.util.Locale

/**
 * Recognition of the TikTok links the engine can actually extract, and the URL shape that works.
 *
 * Why this exists: the pinned extractor only knows `https://www.tiktok.com/@user/video/<id>` and
 * `/embed/<id>`. What TikTok hands out in 2026 is a short link (`https://vm.tiktok.com/ZM6x…/`,
 * `https://vt.tiktok.com/…`), and following it lands on `https://www.tiktok.com/share/video/<id>/`
 * — a form the pinned build does not match, so the link falls through to the generic page
 * extractor, which finds a script-heavy share page and no media. Every TikTok video shared from the
 * app therefore failed the same way, whatever the engine version, while Facebook, Instagram and
 * ordinary pages were untouched. Newer upstream builds match `/share/video/` and `tiktokv.com`
 * (yt-dlp commits "Support share URLs", "Fix formats extraction", 2026-08-18), which is the other
 * half of the fix — see [YtDlpUpdater].
 *
 * So: recognise the link, take the id out of it, and hand the engine a URL it matches. `/embed/<id>`
 * is used when no author handle is known because it is accepted by both the old and the new
 * `_VALID_URL`, and the id alone is what the extractor works from.
 *
 * Pure (no Android, no network) so the rules are unit tested; the network half is in
 * [TikTokLinkResolver].
 */
object TikTokUrl {

    /** Which kind of TikTok page a URL names. `PHOTO` is the gallery post the app parses itself. */
    enum class Kind { VIDEO, PHOTO, USER, TAG, SOUND, OTHER }

    data class Parsed(
        val kind: Kind,
        /** `@handle` without the `@`, blank when the link does not carry one. */
        val author: String,
        /** The aweme id, blank when the link does not carry one. */
        val itemId: String,
        /** True for the `vm.tiktok.com` / `vt.tiktok.com` / `/t/…` links that must be followed. */
        val isShortLink: Boolean
    )

    /** Only the hosts this app claims support for; Douyin is a different site and a different extractor. */
    private val hosts = setOf("tiktok.com", "tiktokv.com")

    private val idPattern = Regex("""/(?:video|photo|embed|v)/(\d{6,24})(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
    private val authorPattern = Regex("""/@([\w.\-]{2,40})(?:[/?#]|$)""")
    private val shortHosts = setOf("vm.tiktok.com", "vt.tiktok.com", "vm.tiktokv.com", "vt.tiktokv.com")
    /** Share links sometimes carry only a query id: `?item_id=`, `?mid=`, `?object_id=`. */
    private val queryIdPattern = Regex("""(?:item_id|object_id|mid)=(\d{6,24})""")

    fun hostMatches(url: String?): Boolean {
        val host = MediaUrlNormalizer.host(url?.trim().orEmpty()) ?: return false
        return host in shortHosts || hosts.any { host == it || host.endsWith(".$it") }
    }

    /** A link that names one TikTok post (video or photo), as opposed to a profile or a tag page. */
    fun isMediaUrl(url: String?): Boolean {
        val parsed = parse(url) ?: return false
        return parsed.kind == Kind.VIDEO || parsed.kind == Kind.PHOTO
    }

    /** True when the link has to be followed (or looked up) before it names anything. */
    fun isShortLink(url: String?): Boolean = parse(url)?.isShortLink == true

    fun parse(url: String?): Parsed? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty() || !hostMatches(trimmed)) return null
        val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val host = parsed.host.lowercase(Locale.ROOT)
        val path = parsed.path
        val author = authorPattern.find(path)?.groupValues?.getOrNull(1).orEmpty()
        val fromPath = idPattern.find(path)?.groupValues?.getOrNull(1)
        val fromQuery = parsed.query?.let { queryIdPattern.find(it)?.groupValues?.getOrNull(1) }
        val itemId = (fromPath ?: fromQuery).orEmpty()
        val isShort = host in shortHosts || Regex("^/t/", RegexOption.IGNORE_CASE).containsMatchIn(path)
        val lower = path.lowercase(Locale.ROOT)
        val kind = when {
            Regex("/photo/\\d+").containsMatchIn(lower) -> Kind.PHOTO
            Regex("/(video|embed|v)/\\d+").containsMatchIn(lower) -> Kind.VIDEO
            itemId.isNotEmpty() -> Kind.VIDEO
            isShort -> Kind.VIDEO
            author.isNotEmpty() -> Kind.USER
            lower.contains("/tag/") -> Kind.TAG
            lower.contains("/music/") -> Kind.SOUND
            else -> Kind.OTHER
        }
        return Parsed(kind, author, itemId, isShort)
    }

    /**
     * The URL to hand the engine for this link, without touching the network.
     *
     * Returns the input unchanged when nothing better can be said about it, so a caller can layer
     * [TikTokLinkResolver] on top (which resolves short links and finds the author) and still use
     * this as the last step.
     */
    fun engineUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        val parsed = parse(trimmed) ?: return trimmed
        if (parsed.isShortLink && parsed.itemId.isEmpty()) return trimmed
        if (parsed.itemId.isEmpty()) return trimmed
        val segment = if (parsed.kind == Kind.PHOTO) "photo" else "video"
        return if (parsed.author.isNotEmpty()) {
            "https://www.tiktok.com/@${parsed.author}/$segment/${parsed.itemId}"
        } else if (parsed.kind == Kind.PHOTO) {
            // The app's own photo parser reads a share page by id, and rewriting a photo link the
            // app handles itself would only add a way to get it wrong.
            trimmed
        } else {
            "https://www.tiktok.com/embed/${parsed.itemId}"
        }
    }

    /** The id of a TikTok post, from a canonical or a share URL. Blank when the link has none. */
    fun itemId(url: String?): String = parse(url)?.itemId.orEmpty()

    /**
     * Strips the tracking junk TikTok adds (`?is_copy_url=1&lang=en&u_code=…`) so the cache key, the
     * log line and the engine all see one shape for one post.
     */
    fun stripTracking(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (!hostMatches(trimmed)) return trimmed
        val base = runCatching { URL(trimmed) }.getOrNull() ?: return trimmed
        return "${base.protocol}://${base.host}${base.path}".ifBlank { trimmed }
    }
}
