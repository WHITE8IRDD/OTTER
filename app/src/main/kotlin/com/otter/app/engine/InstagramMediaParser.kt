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
 *  - Carousel slides keep the order the payload reports (never the order a regex
 *    happens to find them in).
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

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    private val scriptJson = Regex(
        """(?is)<script[^>]*type=["']application/json["'][^>]*>(.*?)</script\s*>"""
    )
    private val sharedDataJs = Regex(
        """(?is)window\._sharedData\s*=\s*(\{.*?\})\s*;\s*</script>"""
    )
    private val additionalDataJs = Regex(
        """(?is)window\.__additionalDataLoaded\s*\(\s*['"][^'"]*['"]\s*,\s*(\{.*?\})\s*\)\s*;"""
    )
    private val ogImage = Regex(
        """(?is)<meta[^>]+(?:property|name)=["'](?:og:image(?::secure_url|:url)?|twitter:image(?::src)?)["'][^>]*>"""
    )
    private val attributeValue = Regex(
        """(?is)(?:content|href|src)\s*=\s*(?:"([^"]*)"|'([^']*)')"""
    )
    private val anyCdnUrl = Regex(
        """https?://[A-Za-z0-9._-]*(?:cdninstagram\.com|fbcdn\.net)[^"'\s<>\\]*""",
        RegexOption.IGNORE_CASE
    )
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
    private val htmlEntity = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    private val htmlEntities = mapOf(
        "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "colon" to ":",
        "sol" to "/", "equals" to "=", "quest" to "?", "percnt" to "%", "plus" to "+"
    )

    enum class Source { HTML_PAYLOAD, JSON_API, EMBED, WEBVIEW, DIRECT }

    data class Result(
        val imageUrls: List<String>,
        /** Number of slides the payload declared, or 0 when unknown. */
        val expectedCount: Int,
        val source: Source,
        val shortcode: String = "",
        /** Short human readable explanation when the payload was empty or partial. */
        val note: String? = null
    ) {
        val isComplete: Boolean
            get() = expectedCount == 0 || imageUrls.size >= expectedCount
    }

    // ---------------------------------------------------------------- URL helpers

    fun shortcodeFromUrl(url: String): String {
        val match = Regex("""instagram\.com/(?:p|reel|tv)/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)
        return match?.groupValues?.getOrNull(1).orEmpty()
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
        if (!host.endsWith("cdninstagram.com") && !host.endsWith("fbcdn.net") &&
            !host.endsWith("instagram.com")
        ) return null
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

    /** True when the URL can be fetched as a picture (extension may be missing on video posters). */
    fun isUsableImageUrl(url: String): Boolean =
        normalizeUrl(url) != null && (hasImageExtension(url) || looksLikeMediaCdn(url))

    private fun looksLikeMediaCdn(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host.contains("scontent") || host.contains("cdninstagram") || host.contains("fbcdn")
    }

    // ------------------------------------------------------------------ JSON walk

    /** Parse any Instagram JSON payload (page payload, api/v1, graphql, embed). */
    fun parseJson(json: String, source: Source = Source.JSON_API): Result {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: runCatching { JSONArray(json) }.getOrNull()
            ?: return Result(emptyList(), 0, source, note = "payload was not JSON")
        return parseJsonNode(root, source)
    }

    fun parseJsonNode(root: Any, source: Source = Source.JSON_API): Result {
        val carousel = findCarousel(root)
        if (carousel != null && carousel.size >= 2) {
            return Result(carousel, carousel.size, source)
        }
        val collected = collectMediaUrls(root)
        val order = if (carousel != null && carousel.isNotEmpty()) {
            (carousel + collected).distinctBy { mediaKey(it) }
        } else {
            collected
        }
        val ordered = order.take(MAX_URLS)
        return Result(ordered, 0, source)
    }

    /**
     * Locate the first object that explicitly describes a multi-slide post and
     * return its slides in payload order.
     */
    private fun findCarousel(root: Any): List<String>? {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JSONObject -> {
                    slides(node)?.let { if (it.size >= 2) return it }
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
        return null
    }

    private fun slides(node: JSONObject): List<String>? {
        node.optJSONArray("carousel_media")?.let { array ->
            val urls = mutableListOf<String>()
            for (index in 0 until minOf(array.length(), MAX_SLIDES)) {
                val child = array.optJSONObject(index) ?: continue
                bestImage(child)?.let { if (!urls.contains(it)) urls.add(it) }
            }
            if (urls.isNotEmpty()) return urls
        }
        val edges = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            ?: node.optJSONArray("edges")
        if (edges != null && node.optInt("media_type", -1) == 8) {
            val urls = mutableListOf<String>()
            for (index in 0 until minOf(edges.length(), MAX_SLIDES)) {
                val child = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
                bestImage(child)?.let { if (!urls.contains(it)) urls.add(it) }
            }
            if (urls.isNotEmpty()) return urls
        }
        // `items: [...]` from the web info payload can also hold the carousel.
        return null
    }

    /** Best picture URL of a single media node (thumbnails are used for video slides). */
    private fun bestImage(node: JSONObject): String? {
        node.optJSONObject("image_versions2")?.optJSONArray("candidates")?.let { candidates ->
            pickLargest(candidates, "url")?.let { return it }
        }
        node.optJSONArray("display_resources")?.let { resources ->
            pickLargest(resources, "src")?.let { return it }
        }
        val display = node.optString("display_url", "")
        if (display.isNotEmpty()) normalizeUrl(display)?.let { return it }
        val thumbnail = node.optString("thumbnail_src", "")
        if (thumbnail.isNotEmpty()) normalizeUrl(thumbnail)?.let { return it }
        node.optString("display_uri", "").takeIf { it.isNotEmpty() }?.let { uri ->
            normalizeUrl(uri)?.let { return it }
        }
        return null
    }

    private fun pickLargest(array: JSONArray, field: String): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        for (index in 0 until array.length()) {
            val candidate = array.optJSONObject(index) ?: continue
            val url = normalizeUrl(candidate.optString(field, "")) ?: continue
            val width = candidate.optInt("width", 0)
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Every picture URL in payload order, deduplicated by CDN path. */
    private fun collectMediaUrls(root: Any): List<String> {
        val found = LinkedHashMap<String, String>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 40_000 && found.size < MAX_URLS) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JSONObject -> {
                    for (field in listOf("display_url", "thumbnail_src", "display_uri", "src", "url")) {
                        val value = node.optString(field, "")
                        if (value.isNotEmpty() && looksLikeMediaCdn(value)) {
                            normalizeUrl(value)?.let { add(found, it) }
                        }
                    }
                    node.optJSONObject("image_versions2")?.optJSONArray("candidates")?.let { candidates ->
                        pickLargest(candidates, "url")?.let { add(found, it) }
                    }
                    node.optJSONArray("display_resources")?.let { resources ->
                        pickLargest(resources, "src")?.let { add(found, it) }
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
        return found.values.toList()
    }

    private fun add(target: MutableMap<String, String>, url: String) {
        val key = mediaKey(url)
        val existing = target[key]
        if (existing == null) {
            target[key] = url
            return
        }
        // Prefer the copy that still carries its signed query string, then the longer one.
        val existingHasQuery = existing.contains('?')
        val candidateHasQuery = url.contains('?')
        if ((!existingHasQuery && candidateHasQuery) ||
            (existingHasQuery == candidateHasQuery && url.length > existing.length)
        ) {
            target[key] = url
        }
    }

    // ------------------------------------------------------------------ HTML walk

    fun parseHtml(html: String, pageUrl: String? = null): Result {
        val shortcode = pageUrl?.let(::shortcodeFromUrl).orEmpty()
        val merged = LinkedHashMap<String, String>()
        var expected = 0

        for (blob in jsonBlobs(html)) {
            val partial = runCatching { parseJson(blob, Source.HTML_PAYLOAD) }.getOrNull() ?: continue
            if (partial.expectedCount > expected) expected = partial.expectedCount
            for (url in partial.imageUrls) add(merged, url)
        }

        if (merged.size < 2) {
            // Raw scan of the HTML (handles pages whose payload is inline JSON without a script tag).
            for (match in anyCdnUrl.findAll(html)) {
                val url = normalizeUrl(match.value) ?: continue
                if (hasImageExtension(url)) add(merged, url)
            }
        }

        if (merged.isEmpty()) {
            for (tag in ogImage.findAll(html)) {
                for (value in attributeValue.findAll(tag.value)) {
                    val raw = value.groupValues[1].ifEmpty { value.groupValues[2] }
                    val url = normalizeUrl(raw) ?: continue
                    add(merged, url)
                }
            }
        }

        val urls = merged.values
            .filter { hasImageExtension(it) || looksLikeMediaCdn(it) }
            .distinctBy { mediaKey(it) }
            .take(MAX_URLS)
        val note = when {
            urls.isEmpty() && looksLikeLoginWall(html) -> "Instagram returned a login wall"
            urls.isEmpty() -> "no media found in the page payload"
            expected > 0 && urls.size < expected -> "page listed $expected slides but only ${urls.size} were readable"
            else -> null
        }
        return Result(urls, expected, Source.HTML_PAYLOAD, shortcode, note)
    }

    private fun jsonBlobs(html: String): List<String> {
        val blobs = mutableListOf<String>()
        for (match in scriptJson.findAll(html)) {
            val body = match.groupValues[1].trim()
            if (body.length > 20) blobs.add(body)
        }
        for (match in sharedDataJs.findAll(html)) blobs.add(match.groupValues[1])
        for (match in additionalDataJs.findAll(html)) blobs.add(match.groupValues[1])
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
