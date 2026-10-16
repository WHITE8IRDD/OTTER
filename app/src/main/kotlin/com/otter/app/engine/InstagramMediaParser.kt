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
            if (value.equals(shortcode, ignoreCase = true)) return false
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
            ?: return Result(emptyList(), emptyList(), emptyList(), 0, 0, source, note = "payload was not JSON")
        return parseJsonNode(root, source, shortcode)
    }

    fun parseJsonNode(root: Any, source: Source = Source.JSON_API, shortcode: String = ""): Result {
        val caption = findCaption(root, shortcode)
        val carousel = findCarousel(root, shortcode)
        if (carousel != null && carousel.size >= 2) {
            return Result(
                carousel.photos.urls(),
                carousel.videos.urls(),
                carousel.videos.posters(),
                carousel.slides,
                carousel.declaredCount,
                source,
                caption = caption
            )
        }
        val collected = collectMediaUrls(root, shortcode)
        val photos = MediaSet()
        val videos = MediaSet()
        carousel?.photos?.entries()?.forEach { photos.add(it.url) }
        collected.images.entries().forEach { photos.add(it.url) }
        carousel?.videos?.entries()?.forEach { videos.add(it.url, it.poster) }
        collected.videos.entries().forEach { videos.add(it.url, it.poster) }

        val expected = carousel?.declaredCount ?: 0
        return Result(
            photos.urls().take(MAX_URLS),
            videos.urls().take(MAX_URLS),
            videos.posters().take(MAX_URLS),
            collected.slides.take(MAX_URLS),
            expected,
            source,
            caption = caption
        )
    }

    /**
     * Locate the first object that explicitly describes a multi-slide post and
     * return its slides in payload order (photos and videos kept apart).
     */
    private fun findCarousel(root: Any, shortcode: String = ""): Slides? {
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 20_000) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JSONObject -> {
                    // A sidecar of one of the suggested posts must not answer for the post that was
                    // asked for; without this the walk returned the first carousel on the page.
                    if (isForeignPost(node, shortcode)) continue
                    slides(node, shortcode)?.let { if (it.size >= 2) return it }
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

    /** The photo and video slides of one multi-slide post, in payload order. */
    private data class Slides(
        val photos: MediaSet,
        val videos: MediaSet,
        /** Slide count the payload itself declared (0 = unknown). */
        val declaredCount: Int,
        /** Every slide in the order the payload listed them, typed photo or video. */
        val slides: List<Result.Slide> = emptyList()
    ) {
        val size: Int get() = photos.size + videos.size
    }

    private fun slides(node: JSONObject, shortcode: String = ""): Slides? {
        node.optJSONArray("carousel_media")?.let { array ->
            val slides = readSlideArray(array, shortcode)
            if (slides.size > 0) return slides.withDeclared(node)
        }
        // The page payload marks a sidecar with `__typename: GraphSidecar` and carries
        // no `media_type`, which is a private-API field. Requiring `media_type` 8 meant
        // the payload that is actually available without a session was never recognised,
        // and the post fell through to a blind scan of the whole page.
        val edges = node.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
            ?: node.optJSONArray("edges")
        if (edges != null) {
            val slides = readEdgeArray(edges, shortcode)
            if (slides.size > 0) return slides.withDeclared(node)
        }
        return null
    }

    private fun Slides.withDeclared(node: JSONObject): Slides =
        copy(declaredCount = maxOf(node.optInt("carousel_media_count", 0), size))

    private fun readSlideArray(array: JSONArray, shortcode: String = ""): Slides {
        val ordered = readSlides(array, shortcode, wrappedInNode = false)
        return toSlides(ordered)
    }

    private fun readEdgeArray(edges: JSONArray, shortcode: String = ""): Slides {
        val ordered = readSlides(edges, shortcode, wrappedInNode = true)
        return toSlides(ordered)
    }

    private fun toSlides(ordered: List<Result.Slide>): Slides {
        val photos = MediaSet()
        val videos = MediaSet()
        ordered.forEach { slide ->
            if (slide.isVideo) videos.add(slide.url, slide.posterUrl) else photos.add(slide.url)
        }
        return Slides(photos, videos, photos.size + videos.size, ordered)
    }

    /**
     * The slides of a `carousel_media` or `edges` array in payload order, each typed photo or video.
     *
     * A slide typed as a video by `media_type`/`__typename`/`is_video` stays a video even though it
     * also carries a picture: that picture is its poster, which is why the type flag decides before
     * any "has an image" rule does. Slides of another post are skipped.
     */
    private fun readSlides(
        array: JSONArray,
        shortcode: String,
        wrappedInNode: Boolean
    ): List<Result.Slide> {
        val slides = LinkedHashMap<String, Result.Slide>()
        for (index in 0 until minOf(array.length(), MAX_SLIDES)) {
            val entry = array.optJSONObject(index) ?: continue
            if (isForeignPost(entry, shortcode)) continue
            val node = if (wrappedInNode) entry.optJSONObject("node") ?: entry else entry
            if (isForeignPost(node, shortcode)) continue
            if (isVideoNode(node)) {
                val poster = bestImage(node).orEmpty()
                bestVideo(node)?.let { video ->
                    slides.putIfAbsent(
                        MediaClassifier.identityOf(video),
                        Result.Slide(url = video, isVideo = true, posterUrl = poster)
                    )
                }
            } else {
                bestImage(node)?.let { image ->
                    slides.putIfAbsent(
                        MediaClassifier.identityOf(image),
                        Result.Slide(url = image, isVideo = false)
                    )
                }
            }
        }
        return slides.values.toList()
    }

    /**
     * `media_type` 2 is a video slide, 1 a photo; GraphQL nodes use `is_video`.
     * A video slide also carries a poster in `image_versions2`, so the type flag
     * wins over "has a picture".
     */
    private fun isVideoNode(node: JSONObject): Boolean {
        when (node.optInt("media_type", -1)) {
            2 -> return true
            1 -> return false
        }
        // The page payload types every slide: GraphVideo / XDTGraphVideo carry the
        // video, GraphImage / XDTGraphImage carry a picture.
        val typename = node.optString("__typename", "")
        if (typename.contains("Video", ignoreCase = true)) return true
        if (typename.contains("Image", ignoreCase = true)) return false
        if (node.optBoolean("is_video", false)) return true
        return node.optJSONArray("video_versions")?.let { it.length() > 0 } == true
    }

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
        val thumbnail = node.optString("thumbnail_src", "")
        if (thumbnail.isNotEmpty()) normalizeUrl(thumbnail)?.let { return it }
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
            if (value.isNotEmpty() && hasVideoExtension(value)) {
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
            val width = candidate.optInt("width", 0)
            if (width > bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Every picture and video URL in payload order, deduplicated by CDN path. */
    private fun collectMediaUrls(root: Any, shortcode: String = ""): Collected {
        val images = MediaSet()
        val videos = MediaSet()
        val slides = mutableListOf<Result.Slide>()
        val queue = ArrayDeque<Any>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 40_000 && images.size + videos.size < MAX_URLS) {
            val node = queue.removeFirst()
            visited++
            when (node) {
                is JSONObject -> {
                    // Never read another post's media as this one's.
                    if (isForeignPost(node, shortcode)) continue
                    val isVideo = isVideoNode(node)
                    // Structures already read here must not be walked again: their
                    // `url` entries would otherwise be collected a second time (and a
                    // video's poster frame would be offered as a photo of the post).
                    val consumed = mutableSetOf<String>()

                    val poster = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
                        ?.let { candidates ->
                            consumed.add("image_versions2")
                            pickLargest(candidates, "url")
                        }
                    val display = node.optJSONArray("display_resources")?.let { resources ->
                        consumed.add("display_resources")
                        pickLargest(resources, "src")
                    }
                    val preview = poster ?: display.orEmpty()

                    if (isVideo) {
                        node.optJSONArray("video_versions")?.let { versions ->
                            consumed.add("video_versions")
                            pickLargest(versions, "url")?.let { url ->
                                videos.add(url, preview)
                                slides.add(Result.Slide(url, isVideo = true, posterUrl = preview))
                            }
                        }
                        for (field in listOf("video_url", "playback_url")) {
                            val value = node.optString(field, "")
                            if (value.isNotEmpty() && looksLikeMediaCdn(value)) {
                                normalizeUrl(value)?.let { url ->
                                    if (!videos.contains(url)) {
                                        videos.add(url, preview)
                                        slides.add(Result.Slide(url, isVideo = true, posterUrl = preview))
                                    }
                                }
                            }
                        }
                    } else {
                        for (field in listOf("display_url", "thumbnail_src", "display_uri", "src", "url")) {
                            val value = node.optString(field, "")
                            if (value.isNotEmpty() && looksLikeMediaCdn(value) && !hasVideoExtension(value)) {
                                normalizeUrl(value)?.let { images.add(it) }
                            }
                        }
                        poster?.let { images.add(it) }
                        display?.let { images.add(it) }
                    }

                    for (key in node.keys()) {
                        if (key in consumed) continue
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
        return Collected(images, videos, slides)
    }

    private data class Collected(
        val images: MediaSet,
        val videos: MediaSet,
        val slides: List<Result.Slide> = emptyList()
    )

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
        val photos = MediaSet()
        val videos = MediaSet()
        val slides = mutableListOf<Result.Slide>()
        var expected = 0
        var caption = ""

        // A post page carries a payload for the post itself plus smaller ones for
        // suggested posts, so the declared slide count is only trusted from the
        // payload that actually produced the most slides. Taking the maximum over
        // every blob instead made unrelated posts inflate the count, which left
        // complete posts reported as partial reads.
        var richestPayload = 0
        for (blob in postBlobs(html, shortcode)) {
            val partial = runCatching {
                parseJson(blob, Source.HTML_PAYLOAD, shortcode)
            }.getOrNull() ?: continue
            val found = partial.imageUrls.size + partial.videoUrls.size
            if (found == 0) {
                if (caption.isBlank()) caption = partial.caption
                continue
            }
            if (found > richestPayload) {
                richestPayload = found
                expected = maxOf(partial.expectedCount, found)
            }
            if (caption.isBlank()) caption = partial.caption
            partial.imageUrls.forEach { photos.add(it) }
            partial.videoUrls.forEachIndexed { index, url ->
                videos.add(url, partial.videoPosters.getOrNull(index).orEmpty())
            }
            partial.slides.forEach { slide -> slides.add(slide) }
        }

        if (photos.size + videos.size < 2) {
            // Raw scan of the HTML (handles pages whose payload is inline JSON without a script tag).
            for (match in anyCdnUrl.findAll(html)) {
                val url = normalizeUrl(match.value) ?: continue
                if (hasImageExtension(url)) {
                    photos.add(url)
                    slides.add(Result.Slide(url, isVideo = false))
                } else if (hasVideoExtension(url)) {
                    videos.add(url)
                    slides.add(Result.Slide(url, isVideo = true))
                }
            }
        }

        if (photos.isEmpty() && videos.isEmpty()) {
            for (tag in ogImage.findAll(html)) {
                for (value in attributeValue.findAll(tag.value)) {
                    val raw = value.groupValues[1].ifEmpty { value.groupValues[2] }
                    val url = normalizeUrl(raw) ?: continue
                    photos.add(url)
                }
            }
        }

        val urls = photos.entries()
            .map { it.url }
            .filter { hasImageExtension(it) || looksLikeMediaCdn(it) }
            .distinctBy { mediaKey(it) }
            .take(MAX_URLS)
        val videoEntries = videos.entries()
            .filter { hasVideoExtension(it.url) || looksLikeMediaCdn(it.url) }
            .distinctBy { mediaKey(it.url) }
            .take(MAX_URLS)
        val read = urls.size + videoEntries.size
        val note = when {
            read == 0 && looksLikeLoginWall(html) -> "Instagram returned a login wall"
            read == 0 -> "no media found in the page payload"
            expected > 0 && read < expected -> "page listed $expected slides but only $read were readable"
            else -> null
        }
        val ordered = orderSlides(slides, urls, videoEntries.map { it.url to it.poster })
        return Result(
            imageUrls = urls,
            videoUrls = videoEntries.map { it.url },
            videoPosters = videoEntries.map { it.poster },
            slides = ordered,
            expectedCount = expected,
            source = Source.HTML_PAYLOAD,
            shortcode = shortcode,
            caption = caption,
            note = note
        )
    }

    /**
     * The slide order of the page: whatever the payloads listed in order wins, and the sets
     * collected without order are appended so nothing read is dropped.
     */
    private fun orderSlides(
        recorded: List<Result.Slide>,
        photos: List<String>,
        videos: List<Pair<String, String>>
    ): List<Result.Slide> {
        if (recorded.isEmpty()) return emptyList()
        val seen = recorded.mapTo(LinkedHashSet()) { mediaKey(it.url) }
        val ordered = recorded.toMutableList()
        photos.forEach { url -> if (seen.add(mediaKey(url))) ordered.add(Result.Slide(url, isVideo = false)) }
        videos.forEach { (url, poster) ->
            if (seen.add(mediaKey(url))) ordered.add(Result.Slide(url, isVideo = true, posterUrl = poster))
        }
        return ordered
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

/**
 * An insertion-ordered set of media URLs keyed on host+path, so the same slide
 * delivered by two CDN edges is only kept once.
 */
private class MediaSet {

    data class Entry(val url: String, val poster: String = "")

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun add(url: String, poster: String = "") {
        val key = InstagramMediaParser.mediaKey(url)
        val existing = entries[key]
        if (existing == null) {
            entries[key] = Entry(url, poster)
            return
        }
        // Prefer the copy that still carries its signed query string, then the longer one.
        val betterUrl = prefer(existing.url, url)
        val betterPoster = existing.poster.ifBlank { poster }
        entries[key] = Entry(betterUrl, betterPoster)
    }

    fun contains(url: String): Boolean = entries.containsKey(InstagramMediaParser.mediaKey(url))

    fun entries(): List<Entry> = entries.values.toList()

    fun urls(): List<String> = entries.values.map { it.url }

    fun posters(): List<String> = entries.values.map { it.poster }

    private fun prefer(existing: String, candidate: String): String {
        val existingHasQuery = existing.contains('?')
        val candidateHasQuery = candidate.contains('?')
        return if ((!existingHasQuery && candidateHasQuery) ||
            (existingHasQuery == candidateHasQuery && candidate.length > existing.length)
        ) candidate else existing
    }
}
