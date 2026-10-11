package com.otter.app.engine

import com.otter.app.engine.MiniJson.Arr
import com.otter.app.engine.MiniJson.Obj
import com.otter.app.engine.MiniJson.Value
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

    /**
     * Identity of a media URL: the CDN path is unique per photo version, while the
     * host differs between delivery edges and the query differs per signature. The
     * same photo returned by two endpoints (page + embed) must collapse to one tile.
     */
    fun mediaKey(url: String): String {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return url.substringBefore('?')
        return parsed.path.lowercase(Locale.ROOT)
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
    fun parseJson(
        json: String,
        source: Source = Source.JSON_API,
        expectedMediaId: Long = 0L
    ): Result {
        val root = MiniJson.parseOrNull(json)
            ?: return Result(emptyList(), 0, source, note = "payload was not JSON")
        return parseJsonNode(root, source, expectedMediaId)
    }

    internal fun parseJsonNode(
        root: Value,
        source: Source = Source.JSON_API,
        expectedMediaId: Long = 0L
    ): Result {
        val carousel = findCarousel(root, expectedMediaId)
        if (carousel != null && carousel.urls.size >= 2) {
            // The post's own slides, in payload order. Pictures from "more posts"
            // previews on the same page must never end up in the picker.
            val expected = maxOf(carousel.declaredCount, carousel.urls.size)
            return Result(carousel.urls, expected.coerceAtMost(MAX_SLIDES), source)
        }
        if (expectedMediaId > 0L) {
            findPostImage(root, expectedMediaId)?.let { single ->
                return Result(listOf(single), 1, source)
            }
        }
        val collected = collectMediaUrls(root)
        val declared = carousel?.declaredCount ?: 0
        val order = carousel?.urls.orEmpty().ifEmpty { collected.urls }
        val expected = when {
            declared > 0 -> declared
            collected.sawMediaMarker && order.isNotEmpty() -> 1
            else -> 0
        }
        return Result(order.take(MAX_URLS), expected, source)
    }

    /** Picture of the post itself (the node whose id/pk matches the media id). */
    private fun findPostImage(root: Value, mediaId: Long): String? {
        val needle = mediaId.toString()
        val queue = ArrayDeque<Value>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is Obj -> {
                    val ids = listOf("id", "pk", "media_id").mapNotNull { node.string(it) }
                    if (ids.any { it.contains(needle) }) {
                        bestImage(node)?.let { return it }
                    }
                    for (child in node.fields.values) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                is Arr -> {
                    for (child in node.items) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private data class Carousel(val urls: List<String>, val declaredCount: Int)

    private data class Collected(val urls: List<String>, val sawMediaMarker: Boolean)

    /**
     * Locate the object that describes the post's own multi-slide carousel and
     * return its slides in payload order plus how many slides it declares.
     *
     * A page can carry several carousels (the post plus "more posts" previews), so
     * the candidate whose subtree contains this post's media id wins; otherwise the
     * first carousel with real slides wins.
     */
    private fun findCarousel(root: Value, expectedMediaId: Long = 0L): Carousel? {
        var best: Carousel? = null
        var bestScore = Int.MIN_VALUE
        val queue = ArrayDeque<Value>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is Obj -> {
                    slides(node)?.let { carousel ->
                        if (carousel.declaredCount >= 2 || carousel.urls.size >= 2) {
                            val idMatch = expectedMediaId > 0L &&
                                subtreeContainsMediaId(node, expectedMediaId)
                            val score = (if (idMatch) 1_000 else 0) +
                                carousel.declaredCount * 2 + carousel.urls.size
                            if (score > bestScore) {
                                bestScore = score
                                best = carousel
                            }
                        }
                    }
                    for (child in node.fields.values) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                is Arr -> {
                    for (child in node.items) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                else -> Unit
            }
        }
        return best
    }

    /** True when this subtree mentions the numeric media id we are looking for. */
    private fun subtreeContainsMediaId(node: Obj, mediaId: Long): Boolean {
        val needle = mediaId.toString()
        val queue = ArrayDeque<Value>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4_000) {
            val current = queue.removeFirst()
            visited++
            when (current) {
                is Obj -> {
                    for (field in listOf("id", "pk", "media_id", "code")) {
                        val value = current.string(field) ?: continue
                        if (value.contains(needle)) return true
                    }
                    for (child in current.fields.values) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                is Arr -> {
                    for (child in current.items) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                else -> Unit
            }
        }
        return false
    }

    /** Carousel entries are either media objects or wrapped in `node`/`media`. */
    private fun unwrapMedia(node: Obj): Obj {
        node.obj("node")?.let { return it }
        node.obj("media")?.let { return it }
        return node
    }

    private fun slides(node: Obj): Carousel? {
        node.arr("carousel_media")?.let { array ->
            if (array.size == 0) return null
            val urls = mutableListOf<String>()
            for (index in 0 until minOf(array.size, MAX_SLIDES)) {
                val child = unwrapMedia(array.obj(index) ?: continue)
                bestImage(child)?.let { if (!urls.contains(it)) urls.add(it) }
            }
            // Newer payloads inline only a window of the slides but still declare the total.
            val declared = maxOf(node.int("carousel_media_count", array.size), urls.size)
            return Carousel(urls, declared.coerceAtMost(MAX_SLIDES))
        }
        val edges = node.obj("edge_sidecar_to_children")?.arr("edges")
            ?: if (node.int("media_type", -1) == 8) node.arr("edges") else null
        if (edges != null) {
            val urls = mutableListOf<String>()
            for (index in 0 until minOf(edges.size, MAX_SLIDES)) {
                val child = unwrapMedia(edges.obj(index)?.obj("node") ?: continue)
                bestImage(child)?.let { if (!urls.contains(it)) urls.add(it) }
            }
            return Carousel(urls, minOf(edges.size, MAX_SLIDES))
        }
        return null
    }

    /** Best picture URL of a single media node (thumbnails are used for video slides). */
    private fun bestImage(node: Obj): String? {
        node.obj("image_versions2")?.arr("candidates")?.let { candidates ->
            pickLargest(candidates, "url")?.let { return it }
        }
        node.arr("display_resources")?.let { resources ->
            pickLargest(resources, "src")?.let { return it }
        }
        node.string("display_url")?.let { display ->
            if (display.isNotEmpty()) normalizeUrl(display)?.let { return it }
        }
        node.string("thumbnail_src")?.let { thumbnail ->
            if (thumbnail.isNotEmpty()) normalizeUrl(thumbnail)?.let { return it }
        }
        node.string("display_uri")?.let { uri ->
            if (uri.isNotEmpty()) normalizeUrl(uri)?.let { return it }
        }
        return null
    }

    private fun pickLargest(array: Arr, field: String): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        for (index in 0 until array.size) {
            val candidate = array.obj(index) ?: continue
            val raw = candidate.string(field) ?: continue
            val url = normalizeUrl(raw) ?: continue
            val width = candidate.int("width", 0)
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Every picture URL in payload order, deduplicated by CDN path. */
    private fun collectMediaUrls(root: Value): Collected {
        val found = LinkedHashMap<String, String>()
        var sawMediaMarker = false
        val queue = ArrayDeque<Value>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 40_000 && found.size < MAX_URLS) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is Obj -> {
                    if (node.has("media_type") || node.has("image_versions2")) sawMediaMarker = true
                    // Node level picture fields only. Bare "url"/"src" fields belong to
                    // size candidates, which are resolved to their largest entry below.
                    for (field in listOf("display_url", "thumbnail_src", "display_uri")) {
                        val value = node.string(field)
                        if (!value.isNullOrEmpty() && looksLikeMediaCdn(value)) {
                            normalizeUrl(value)?.let { add(found, it) }
                        }
                    }
                    node.obj("image_versions2")?.arr("candidates")?.let { candidates ->
                        pickLargest(candidates, "url")?.let { add(found, it) }
                    }
                    node.arr("display_resources")?.let { resources ->
                        pickLargest(resources, "src")?.let { add(found, it) }
                    }
                    for (child in node.fields.values) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                is Arr -> {
                    for (child in node.items) {
                        if (child is Obj || child is Arr) queue.add(child)
                    }
                }
                else -> Unit
            }
        }
        return Collected(found.values.toList(), sawMediaMarker)
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
        val mediaId = if (shortcode.isNotEmpty()) mediaIdFromShortcode(shortcode) else 0L
        val merged = LinkedHashMap<String, String>()
        var expected = 0

        for (blob in jsonBlobs(html)) {
            val partial = runCatching { parseJson(blob, Source.HTML_PAYLOAD, mediaId) }.getOrNull() ?: continue
            if (partial.expectedCount > expected) expected = partial.expectedCount
            for (url in partial.imageUrls) add(merged, url)
        }

        if (merged.isEmpty()) {
            // Raw scan (only when no payload was understood at all): handles pages whose
            // inline JSON has no script tag and JS payloads with escaped slashes.
            val scanTargets = if (html.contains("\\/")) listOf(html, decodeEscapes(html)) else listOf(html)
            for (target in scanTargets) {
                for (match in anyCdnUrl.findAll(target)) {
                    val url = normalizeUrl(match.value) ?: continue
                    if (hasImageExtension(url)) add(merged, url)
                }
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
