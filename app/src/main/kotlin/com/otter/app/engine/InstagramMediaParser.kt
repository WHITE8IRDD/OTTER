package com.otter.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/**
 * Android-free parsing for Instagram posts (pages, embed pages and JSON APIs).
 *
 * The scraper kept failing at the point where a downloaded page or JSON payload
 * has to be turned into an *ordered* list of *complete* (signed) CDN URLs, so the
 * logic lives here where it can be unit tested and verified against the live site
 * from a plain JVM.
 *
 * Rules that matter for correctness:
 *  - A carousel slide is either a **photo** or a **video**; both are read, in the
 *    order the payload reports them (never the order a regex happens to find them in).
 *    Video slides are returned separately (with their poster frame) so the app can
 *    send them through the video download path and the photos through the image path.
 *  - URLs are kept *with* their signed query string; removing it makes Instagram's
 *    CDN answer 403, which is what produced blank previews and empty downloads.
 *  - Duplicates are detected on host+path (query strings differ per delivery edge).
 *  - The number of slides the payload declares is reported so callers can tell the
 *    difference between "single photo post" and "carousel we failed to read".
 */
object InstagramMediaParser {

    /** Instagram refuses to render more than 20 slides in one post. */
    private const val MAX_SLIDES = 20
    private const val MAX_URLS = 40
    /** A post page carries payloads for suggested posts too; only the first few matter. */
    private const val MAX_JSON_BLOBS = 12

    /** Fields that identify which post a payload node belongs to. */
    private val foreignIdFields = listOf("code", "shortcode", "media_code", "pk", "id")

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    private val videoExtensions = setOf("mp4", "m4v", "mov", "webm")

    private val scriptJson = Regex(
        """(?is)<script[^>]*type=["']application/json["'][^>]*>(.*?)</script\s*>"""
    )
    private val sharedDataJs = Regex(
        """(?is)window\._sharedData\s*=\s*(\{.*?\})\s*;\s*</script>"""
    )
    private val additionalDataJs = Regex(
        """(?is)window\.__additionalDataLoaded\s*\(\s*['"][^'"]*['"]\s*,\s*(\{.*?\})\s*\)\s*;"""
    )
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
    private val htmlEntity = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    private val htmlEntities = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "colon" to ":",
        "sol" to "/", "equals" to "=", "quest" to "?", "percnt" to "%", "plus" to "+"
    )

    enum class Source { HTML_PAYLOAD, JSON_API, EMBED, WEBVIEW, DIRECT }

    /**
     * One slide of a post in the order the payload listed it. Keeping the order (rather than
     * "photos, then videos") is what lets the picker and the saved file names follow the post:
     * a sidecar of three pictures, a video and two more pictures is not two separate groups.
     */
    data class Slide(val url: String, val isVideo: Boolean, val posterUrl: String = "")

    data class Result(
        val imageUrls: List<String>,
        /** Playable URLs of the video slides of the same post, in payload order. */
        val videoUrls: List<String> = emptyList(),
        /**
         * Poster frame for each entry of [videoUrls] (same indexes, blank when the
         * payload carried none) so the picker can still show a preview.
         */
        val videoPosters: List<String> = emptyList(),
        /** The post's slides in payload order, when the read preserved the order. */
        val slides: List<Slide> = emptyList(),
        /** Number of slides the payload declared, or 0 when unknown. */
        val expectedCount: Int = 0,
        val source: Source = Source.JSON_API,
        val shortcode: String = "",
        /** Post caption when the payload carried one (used for description/hashtags). */
        val caption: String = "",
        /** Short human readable explanation when the payload was empty or partial. */
        val note: String? = null
    ) {
        /** Photos + videos actually readable. */
        val mediaCount: Int get() = imageUrls.size + videoUrls.size

        /**
         * True when the post's own media starts with a video, which is the single-video post the
         * picker used to bury under the page's pictures.
         */
        val primaryIsVideo: Boolean
            get() = slides.firstOrNull()?.isVideo
                ?: (imageUrls.isEmpty() && videoUrls.isNotEmpty())

        /** Ordered media, falling back to "photos then videos" when the order was lost. */
        fun orderedSlides(): List<Slide> = when {
            slides.isNotEmpty() -> slides
            else -> imageUrls.map { Slide(it, isVideo = false) } +
                videoUrls.mapIndexed { index, url ->
                    Slide(url, isVideo = true, posterUrl = videoPosters.getOrNull(index).orEmpty())
                }
        }

        val isComplete: Boolean
            get() = expectedCount == 0 || mediaCount >= expectedCount

        /** Something was read, but fewer slides than the post declares. */
        val isPartial: Boolean
            get() = mediaCount > 0 && !isComplete

        /** Poster for a video slide, or blank when there is none. */
        fun posterFor(index: Int): String = videoPosters.getOrNull(index).orEmpty()
    }

    // ---------------------------------------------------------------- URL helpers

    fun shortcodeFromUrl(url: String): String = InstagramUrl.parse(url)?.shortcode.orEmpty()

    /**
     * True when [url] points at one post — `/p/`, `/reel/`, `/tv/` or the `/share/…` form the
     * share sheet produces. Recognition used to be three different `url.contains("/p/")` tests,
     * each of which excluded reels, and a reel is how Instagram shares a one-video post.
     */
    fun isPostUrl(url: String?): Boolean = InstagramUrl.isMediaUrl(url)

    /**
     * A payload node that names a *different* post than the one asked for. Instagram ships the
     * suggested-post rail inside the same document, so any walk that ignores this reads those
     * pictures as if they belonged to the link the user pasted.
     */
    fun isForeignPost(node: JSONObject, shortcode: String): Boolean {
        if (shortcode.isBlank()) return false
        for (field in foreignIdFields) {
            val value = node.optString(field, "")
            if (value.isEmpty()) continue
            if (value == shortcode) return false
            // `id` is "<media_id>_<shard>" on the web API; it cannot be compared to a shortcode,
            // so only the code-like fields decide.
            if (field == "pk" || field == "id") continue
            return true
        }
        return false
    }

    /** Decode a base64-url shortcode into Instagram's numeric media id. */
    fun mediaIdFromShortcode(shortcode: String): Long {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (c in shortcode) {
            val value = alphabet.indexOf(c)
            if (value < 0) return 0L
            id = id * 64 + value
        }
        return id
    }

    /** Identity of a media URL: the CDN path is unique per photo, the query is not. */
    fun mediaKey(url: String): String {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return url.substringBefore('?')
        val host = parsed.host.lowercase(Locale.ROOT)
        return "$host${parsed.path}"
    }

    fun decodeEscapes(value: String): String {
        var out = value
            .replace("\\/", "/")
            .replace("\\\\", "\\")
        out = unicodeEscape.replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull(16) ?: return@replace match.value
            code.toChar().toString()
        }
        return decodeHtmlEntities(out)
    }

    fun decodeHtmlEntities(value: String): String {
        if (!value.contains('&')) return value
        return htmlEntity.replace(value) { match ->
            val name = match.groupValues[1]
            when {
                name.startsWith("#x", ignoreCase = true) ->
                    name.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: match.value
                name.startsWith("#") ->
                    name.drop(1).toIntOrNull()?.let { codePoint(it) } ?: match.value
                else -> htmlEntities[name.lowercase(Locale.ROOT)] ?: match.value
            }
        }
    }

    private fun codePoint(code: Int): String =
        runCatching { String(Character.toChars(code)) }.getOrDefault("")

    /** Normalise a CDN URL found in HTML/JSON: unescape, decode entities, keep query. */
    fun normalizeUrl(raw: String): String? {
        val decoded = decodeEscapes(raw.trim()).replace("\n", "").replace("\r", "").trim()
        if (decoded.isEmpty()) return null
        val withoutFragment = decoded.substringBefore('#')
        val parsed = runCatching { URL(withoutFragment) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        val host = parsed.host.lowercase(Locale.ROOT)
        if (listOf("cdninstagram.com", "fbcdn.net", "instagram.com").none { host == it || host.endsWith(".$it") }) return null
        if (isNoiseUrl(withoutFragment)) return null
        return withoutFragment
    }

    private fun isNoiseUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains("/rsrc.php")) return true
        if (lower.contains("/static/")) return true
        if (lower.contains("/v/t51.2885-19/")) return true     // profile pictures
        if (lower.contains("profile_pic")) return true
        if (lower.contains("/images/ico/")) return true
        return false
    }

    private fun hasImageExtension(url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrNull() ?: return false
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    private fun hasVideoExtension(url: String): Boolean {
        val path = runCatching { URL(url).path }.getOrNull() ?: return false
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in videoExtensions
    }

    /** True when the URL can be fetched as a picture (extension may be missing on video posters). */
    fun isUsableImageUrl(url: String): Boolean =
        normalizeUrl(url) != null && (hasImageExtension(url) || looksLikeMediaCdn(url))

    private fun looksLikeMediaCdn(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host.contains("scontent") || host.contains("cdninstagram") || host.contains("fbcdn")
    }

    // ------------------------------------------------------------------ JSON walk

    /**
     * Parse any Instagram JSON payload (page payload, api/v1, graphql, embed).
     *
     * @param shortcode the post the caller asked for. When it is known, payload nodes that identify
     *                  themselves as a *different* post are skipped: a post page carries its
     *                  suggested rail in the same document, and reading that as the post's own
     *                  media is what produced "40 pictures and no video".
     */
    fun parseJson(json: String, source: Source = Source.JSON_API, shortcode: String = ""): Result {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: runCatching { JSONArray(json) }.getOrNull()
            ?: return Result(
                imageUrls = emptyList(),
                source = source,
                note = "payload was not JSON"
            )
        return parseJsonNode(root, source, shortcode)
    }

    fun parseJsonNode(root: Any, source: Source = Source.JSON_API, shortcode: String = ""): Result {
        // Resolve the post before visiting media. Child shortcodes identify slides, not
        // foreign posts; only the selected parent is compared with the requested shortcode.
        val post = findPost(root, shortcode) ?: return Result(emptyList(), source = source)
        val array = post.optJSONArray("carousel_media")
            ?: post.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        val declared = maxOf(post.optInt("carousel_media_count", 0), array?.length() ?: 1)
        val nodes = if (array == null) listOf(post) else (0 until minOf(array.length(), MAX_SLIDES))
            .mapNotNull { array.optJSONObject(it) }
            .map { it.optJSONObject("node") ?: it }
        val ordered = nodes.mapNotNull { node ->
            if (isVideoNode(node)) {
                bestVideo(node)?.let { Slide(it, true, bestImage(node).orEmpty()) }
            } else {
                bestImage(node)?.let { Slide(it, false) }
            }
        }.distinctBy { mediaKey(it.url) }
        return Result(
            imageUrls = ordered.filterNot { it.isVideo }.map { it.url },
            videoUrls = ordered.filter { it.isVideo }.map { it.url },
            videoPosters = ordered.filter { it.isVideo }.map { it.posterUrl },
            slides = ordered,
            expectedCount = declared,
            source = source,
            caption = findCaption(post),
            note = if (ordered.size < declared) "post listed $declared slides but only ${ordered.size} were readable" else null
        )
    }

    /** Prefer the named post container, never loose url/src/thumbnail fields. */
    private fun findPost(root: Any, shortcode: String): JSONObject? {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var fallback: JSONObject? = null
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 20_000) {
            when (val node = queue.removeFirst()) {
                is JSONObject -> {
                    if (isForeignPost(node, shortcode)) continue
                    for (key in listOf("shortcode_media", "xdt_shortcode_media")) {
                        node.optJSONObject(key)?.let { if (!isForeignPost(it, shortcode)) return it }
                    }
                    if (isMediaNode(node)) {
                        if (listOf("code", "shortcode").any { node.optString(it) == shortcode && shortcode.isNotEmpty() }) return node
                        // API items are authoritative; don't walk into their renditions or owner.
                        if (fallback == null && node === root) fallback = node
                        continue
                    }
                    node.optJSONArray("items")?.optJSONObject(0)?.let {
                        if (!isForeignPost(it, shortcode) && isMediaNode(it)) return it
                    }
                    for (key in node.keys()) node.opt(key)?.let {
                        if (it is JSONObject || it is JSONArray) queue.add(it)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) node.opt(i)?.let {
                    if (it is JSONObject || it is JSONArray) queue.add(it)
                }
            }
        }
        return fallback
    }

    private fun isMediaNode(node: JSONObject): Boolean =
        node.has("carousel_media") || node.has("edge_sidecar_to_children") ||
            node.has("image_versions2") || node.has("display_url") ||
            isVideoNode(node)

    private fun isVideoNode(node: JSONObject): Boolean =
        node.optInt("media_type") == 2 || node.optBoolean("is_video") ||
            node.optString("__typename").contains("Video", ignoreCase = true) ||
            node.optJSONArray("video_versions")?.length()?.let { it > 0 } == true ||
            listOf("video_url", "playback_url").any { node.optString(it).isNotBlank() }

    /** Best picture URL of a single media node (video slides fall back to their poster). */
    private fun bestImage(node: JSONObject): String? {
        node.optJSONObject("image_versions2")?.optJSONArray("candidates")?.let { candidates ->
            pickLargest(candidates, "url")?.let { return it }
        }
        node.optJSONArray("display_resources")?.let { resources ->
            pickLargest(resources, "src")?.let { return it }
        }
        val display = node.optString("display_url", "")
        if (display.isNotEmpty()) normalizeUrl(display)?.let { return it }
        node.optString("display_uri", "").takeIf { it.isNotEmpty() }?.let { uri ->
            normalizeUrl(uri)?.let { return it }
        }
        return null
    }

    /** Best playable URL of a video slide (`video_versions` holds the renditions). */
    private fun bestVideo(node: JSONObject): String? {
        node.optJSONArray("video_versions")?.let { versions ->
            pickLargest(versions, "url")?.let { return it }
        }
        for (field in listOf("video_url", "playback_url", "url")) {
            val value = node.optString(field, "")
            if (value.isNotEmpty() && (hasVideoExtension(value) ||
                    (field != "url" && !hasImageExtension(value) && !MediaClassifier.isManifestUrl(value)))) {
                normalizeUrl(value)?.let { return it }
            }
        }
        return null
    }

    private fun pickLargest(array: JSONArray, field: String): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        for (index in 0 until array.length()) {
            val candidate = array.optJSONObject(index) ?: continue
            val url = normalizeUrl(candidate.optString(field, "")) ?: continue
            val width = candidate.optInt("width", candidate.optInt("config_width", 0))
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** First caption text of the post (GraphQL edge form and the plain forms). */
    private fun findCaption(root: Any, shortcode: String = ""): String {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JSONObject -> {
                    if (isForeignPost(node, shortcode)) continue
                    node.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.let { edges ->
                        for (index in 0 until edges.length()) {
                            val text = edges.optJSONObject(index)
                                ?.optJSONObject("node")
                                ?.optString("text", "")
                            if (!text.isNullOrBlank()) return text
                        }
                    }
                    when (val caption = node.opt("caption")) {
                        is String -> if (caption.isNotBlank()) return caption
                        is JSONObject -> caption.optString("text", "")
                            .takeIf { it.isNotBlank() }?.let { return it }
                    }
                    for (key in node.keys()) {
                        node.opt(key)?.let { if (it is JSONObject || it is JSONArray) queue.add(it) }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until node.length()) {
                        node.opt(index)?.let { if (it is JSONObject || it is JSONArray) queue.add(it) }
                    }
                }
            }
        }
        return ""
    }

    // ------------------------------------------------------------------ HTML walk

    fun parseHtml(html: String, pageUrl: String? = null): Result {
        val shortcode = pageUrl?.let(::shortcodeFromUrl).orEmpty()
        // Never scan CDN strings or og:image as post items. Those include video posters,
        // play-button composites, alternate sizes and recommendations. Keep one typed snapshot.
        val results = postBlobs(html, shortcode).map { parseJson(it, Source.HTML_PAYLOAD, shortcode) }
        val best = results.maxWithOrNull(compareBy<Result> { it.mediaCount }.thenBy { it.expectedCount })
            ?: Result(emptyList(), source = Source.HTML_PAYLOAD)
        return best.copy(
            shortcode = shortcode,
            note = best.note ?: when {
                best.mediaCount > 0 -> null
                looksLikeLoginWall(html) -> "Instagram returned a login wall"
                else -> "no typed post media found in the page payload"
            }
        )
    }

    /**
     * The JSON payloads of a post page. A page carries payloads for suggested posts
     * too, so this stops after a few: parsing the rest costs memory and time and
     * only ever contributed other posts' media.
     */
    /**
     * The payloads of a post page that describe **this** post.
     *
     * A post page carries a payload for the post itself and smaller ones for the suggested rail.
     * Merging media across all of them is what turned "one video post" into "forty pictures and a
     * video at the bottom", so when the shortcode is known only the blobs that mention it are read,
     * and when it is not, the largest blob (the post payload is always the biggest) is used alone.
     */
    private fun postBlobs(html: String, shortcode: String): List<String> {
        val all = jsonBlobs(html).filter { it.isNotBlank() }
        if (all.isEmpty()) return emptyList()
        if (shortcode.isBlank()) return listOf(all.maxByOrNull { it.length }.orEmpty())
        val matching = all.filter { blob -> blob.contains(shortcode, ignoreCase = true) }
        // Nothing names the post: the largest payload is the post itself (the suggested rail is
        // summaries), so read that one instead of merging every other post on the page.
        return matching.ifEmpty { listOf(all.maxByOrNull { it.length }.orEmpty()) }
    }

    private fun jsonBlobs(html: String): List<String> {
        val blobs = mutableListOf<String>()
        var budget = 1_200_000
        fun take(body: String) {
            val trimmed = body.trim()
            if (trimmed.length > 40 && trimmed.length <= budget && blobs.size < MAX_JSON_BLOBS) {
                blobs.add(trimmed)
                budget -= trimmed.length
            }
        }
        for (match in scriptJson.findAll(html)) take(match.groupValues[1])
        for (match in sharedDataJs.findAll(html)) take(match.groupValues[1])
        for (match in additionalDataJs.findAll(html)) take(match.groupValues[1])
        return blobs
    }

    /** Instagram answers logged-out scrapers with a login page instead of a 403. */
    fun looksLikeLoginWall(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)
        if (lower.contains("loginform") || lower.contains("login_form")) return true
        if (lower.contains("\"require_login\":true") || lower.contains("\"requirelogin\":true")) return true
        if (lower.contains("accounts/login")) return true
        return lower.contains("you must log in to continue") || lower.contains("please wait a few minutes")
    }
}
