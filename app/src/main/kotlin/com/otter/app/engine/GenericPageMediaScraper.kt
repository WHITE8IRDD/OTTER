package com.otter.app.engine

import android.content.Context
import android.util.Log
import com.otter.app.data.cookies.CookieManager as AppCookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * What a plain page exposes.
 *
 * [videoUrls] is the point of this whole structure: a page that carries a video must answer with
 * the video, and the pictures are the secondary result. [hasVideoSignals] says the page declared a
 * video even when no usable URL could be read from it, which is reported as "this page advertises a
 * video that could not be extracted" instead of quietly offering its thumbnails.
 */
data class PageImageDiscovery(
    val title: String,
    val imageUrls: List<String>,
    val videoUrls: List<String> = emptyList(),
    /** Poster frame per entry of [videoUrls] (blank when the page carried none). */
    val videoPosters: List<String> = emptyList(),
    val hasVideoSignals: Boolean = false
)

/**
 * Extracts images exposed by common HTML conventions. This is deliberately a fallback,
 * not a replacement for yt-dlp: JavaScript-only pages and protected streams still need
 * their site's supported extractor or valid user-provided cookies.
 */
object GenericPageMediaParser {
    private const val MAX_IMAGES = 40
    private const val MAX_VIDEOS = 8

    private val titleTag = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
    private val mediaTag = Regex("""(?is)<(meta|img|source|link|video|a)\b[^>]*>""")
    private val attribute = Regex("""(?is)([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val jsonLdScript = Regex("""(?is)<script\b(?=[^>]*type\s*=\s*["']application/ld\+json["'])[^>]*>(.*?)</script\s*>""")
    private val jsonImageField = Regex("""(?is)"(?:image|thumbnailUrl|thumbnail_url)"\s*:\s*(?:"((?:\\.|[^"\\])*)"|\[(.*?)\])""")
    private val jsonVideoField = Regex("""(?is)"(?:contentUrl|content_url|embedUrl|videoUrl|url)"\s*:\s*"((?:\\.|[^"\\])*\.(?:mp4|webm|m4v|mov|m3u8|mp3|m4a|ogg|opus))"""")
    private val jsonString = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")

    private val videoMetaNames = setOf(
        "og:video", "og:video:url", "og:video:secure_url", "og:video:src",
        "twitter:player:stream", "twitter:player", "video:other_url_secure",
        "og:video:embed_url"
    )

    private val imageMetaNames = setOf(
        "og:image", "og:image:url", "og:image:secure_url",
        "twitter:image", "twitter:image:src", "image", "thumbnailurl"
    )

    /** Parse a bounded set of likely image URLs while preserving their signed query strings. */
    fun parse(pageUrl: String, html: String): PageImageDiscovery {
        val images = LinkedHashSet<String>()
        /** Media URLs of the page in the order they were declared, each with its poster. */
        val videos = LinkedHashMap<String, String>()
        val videoKeys = HashSet<String>()
        var videoSignals = false
        var pagePoster: String? = null

        /** A declared video is the media of the page; a picture found in markup is not. */
        fun addVideo(candidate: String?, poster: String = "", declared: Boolean = true) {
            val resolved = candidate?.let { resolveHttpUrl(pageUrl, it) } ?: return
            if (MediaClassifier.isDecorative(resolved)) return
            if (!MediaClassifier.isDirectlyDownloadable(resolved) && !MediaClassifier.isManifestUrl(resolved)) return
            if (videoKeys.add(MediaClassifier.identityOf(resolved)) && videos.size < MAX_VIDEOS) {
                // Resolved like the media URL itself: a page may name its poster "/p.jpg", and a
                // relative poster handed to the image loader is a card that never fills in.
                val resolvedPoster = poster.takeIf { it.isNotBlank() }
                    ?.let { resolveHttpUrl(pageUrl, it) }
                    .orEmpty()
                videos[resolved] = resolvedPoster.ifBlank { pagePoster.orEmpty() }
            }
            if (declared) videoSignals = true
        }
        val title = titleTag.find(html)?.groupValues?.getOrNull(1)
            ?.let(::decodeHtml)
            ?.replace(Regex("<[^>]*>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        fun add(candidate: String?) {
            if (candidate.isNullOrBlank() || images.size >= MAX_IMAGES) return
            val resolved = resolveHttpUrl(pageUrl, candidate) ?: return
            if (!isTrackingPixel(resolved)) images.add(resolved)
        }

        for (tagMatch in mediaTag.findAll(html)) {
            if (images.size >= MAX_IMAGES) break
            val tagName = tagMatch.groupValues[1].lowercase(Locale.ROOT)
            val attributes = parseAttributes(tagMatch.value)

            when (tagName) {
                "meta" -> {
                    val name = (attributes["property"] ?: attributes["name"] ?: attributes["itemprop"])
                        ?.lowercase(Locale.ROOT)
                        .orEmpty()
                    if (name in imageMetaNames) {
                        add(attributes["content"])
                    }
                    if (name in videoMetaNames) {
                        val content = attributes["content"]
                        // og:video may name an embed page rather than a file: it is still the page's
                        // declared video, so it counts as a signal even when it is not downloadable.
                        if (content != null) {
                            addVideo(content, declared = true)
                            if (!MediaClassifier.isDirectlyDownloadable(content) &&
                                !MediaClassifier.isManifestUrl(content)
                            ) videoSignals = true
                        }
                    }
                    if (name == "og:image" || name == "og:image:secure_url" || name == "twitter:image") {
                        // Kept as the poster of the declared video, which is what a picker shows for
                        // a stream that cannot be previewed any other way.
                        pagePoster = attributes["content"]?.let { resolveHttpUrl(pageUrl, it) } ?: pagePoster
                    }
                }
                "video" -> {
                    val src = attributes["src"] ?: attributes["data-src"]
                    addVideo(src, poster = attributes["poster"].orEmpty(), declared = true)
                    if (src == null) videoSignals = true
                }
                "a" -> {
                    val href = attributes["href"]
                    if (href != null && (MediaClassifier.isDirectlyDownloadable(href) ||
                            MediaClassifier.isManifestUrl(href))
                    ) {
                        addVideo(href, declared = false)
                    }
                }
                "img" -> {
                    val width = attributes["width"]?.toIntOrNull()
                    val height = attributes["height"]?.toIntOrNull()
                    val isOnePixel = width == 1 || height == 1
                    if (!isOnePixel) {
                        val candidates = listOf(
                            attributes["data-original"],
                            attributes["data-original-src"],
                            attributes["data-lazy-src"],
                            attributes["data-src"],
                            bestSrcset(attributes["data-srcset"] ?: attributes["srcset"]),
                            attributes["src"]
                        )
                        val selected = candidates.firstNotNullOfOrNull { candidate ->
                            candidate?.takeIf { it.isNotBlank() && resolveHttpUrl(pageUrl, it) != null }
                        }
                        add(selected)
                    }
                }
                "source" -> {
                    val type = attributes["type"]?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    val sourceSet = attributes["data-srcset"] ?: attributes["srcset"]
                    if (type.startsWith("video/") || type.contains("mpegurl") || type.contains("dash+xml")) {
                        addVideo(bestSrcset(sourceSet) ?: attributes["src"], declared = true)
                    }
                    if (type.startsWith("image/") || looksLikeImageUrl(sourceSet.orEmpty())) {
                        add(bestSrcset(sourceSet))
                        add(attributes["src"])
                    }
                }
                "link" -> {
                    val rel = attributes["rel"]?.lowercase(Locale.ROOT).orEmpty()
                    val asType = attributes["as"]?.lowercase(Locale.ROOT).orEmpty()
                    if (rel.split(Regex("\\s+")).contains("image_src") ||
                        (rel == "preload" && asType == "image")) {
                        add(attributes["href"])
                    }
                    // A manifest published as an alternate representation of the page is the video.
                    val linkType = attributes["type"]?.lowercase(Locale.ROOT).orEmpty()
                    if (rel.split(Regex("\\s+")).contains("alternate") &&
                        (linkType.contains("mpegurl") || linkType.contains("dash+xml"))
                    ) {
                        addVideo(attributes["href"], declared = true)
                    }
                }
            }
        }

        for (script in jsonLdScript.findAll(html)) {
            for (field in jsonVideoField.findAll(script.groupValues[1])) {
                addVideo(decodeJsonString(field.groupValues[1]), declared = true)
            }
            if (images.size >= MAX_IMAGES) break
            for (field in jsonImageField.findAll(script.groupValues[1])) {
                val single = field.groupValues.getOrNull(1).orEmpty()
                if (single.isNotEmpty()) add(decodeJsonString(single))
                val array = field.groupValues.getOrNull(2).orEmpty()
                if (array.isNotEmpty()) {
                    for (value in jsonString.findAll(array)) {
                        add(decodeJsonString(value.groupValues[1]))
                    }
                }
            }
        }

        // Ranked so the media the page is about cannot be buried under its own pictures: a
        // declared video first, then other videos, then the page's images, with thumbnails last.
        val candidates = buildList {
            videos.forEach { (url, poster) ->
                add(
                    MediaClassifier.Candidate(
                        url = url,
                        kind = MediaClassifier.kindOf(url, MediaClassifier.Source.DECLARED),
                        source = MediaClassifier.Source.DECLARED,
                        posterUrl = poster
                    )
                )
            }
            images.forEach { url ->
                add(
                    MediaClassifier.Candidate(
                        url = url,
                        kind = MediaClassifier.kindOf(url, MediaClassifier.Source.IMAGE_TAG),
                        source = MediaClassifier.Source.IMAGE_TAG
                    )
                )
            }
        }
        val ranked = MediaClassifier.rank(candidates, pageUrl)
        val orderedVideos = ranked.filter {
            it.kind == MediaClassifier.MediaKind.PRIMARY_VIDEO || it.kind == MediaClassifier.MediaKind.VIDEO ||
                it.kind == MediaClassifier.MediaKind.MANIFEST
        }.map { it.url }
        val orderedImages = ranked.filter {
            it.kind == MediaClassifier.MediaKind.GALLERY_IMAGE || it.kind == MediaClassifier.MediaKind.IMAGE ||
                it.kind == MediaClassifier.MediaKind.PREVIEW
        }.map { it.url }

        val orderedVideoUrls = orderedVideos.ifEmpty { videos.keys.toList() }
        return PageImageDiscovery(
            title = title,
            imageUrls = (orderedImages.ifEmpty { images.toList() }).take(MAX_IMAGES),
            videoUrls = orderedVideoUrls,
            videoPosters = orderedVideoUrls.map { videos[it].orEmpty() },
            hasVideoSignals = videoSignals
        )
    }

    fun isDirectImageUrl(url: String): Boolean = MediaUrlNormalizer.isDirectImageUrl(url)

    private fun parseAttributes(tag: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (match in attribute.findAll(tag)) {
            val value = listOf(
                match.groupValues.getOrElse(2) { "" },
                match.groupValues.getOrElse(3) { "" },
                match.groupValues.getOrElse(4) { "" }
            ).firstOrNull { it.isNotEmpty() }.orEmpty()
            values[match.groupValues[1].lowercase(Locale.ROOT)] = decodeHtml(value)
        }
        return values
    }

    private fun bestSrcset(srcset: String?): String? {
        if (srcset.isNullOrBlank()) return null
        return srcset.split(',')
            .mapNotNull { candidate ->
                val parts = candidate.trim().split(Regex("\\s+"), limit = 2)
                val url = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val descriptor = parts.getOrNull(1).orEmpty()
                val score = when {
                    descriptor.endsWith("w", ignoreCase = true) -> descriptor.dropLast(1).toDoubleOrNull() ?: 1.0
                    descriptor.endsWith("x", ignoreCase = true) -> (descriptor.dropLast(1).toDoubleOrNull() ?: 1.0) * 1000.0
                    else -> 1.0
                }
                url to score
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun looksLikeImageUrl(value: String): Boolean =
        value.split(',').any { MediaUrlNormalizer.isDirectImageUrl(it.trim().substringBefore(' ')) }

    private fun resolveHttpUrl(baseUrl: String, candidate: String): String? {
        val cleaned = decodeHtml(candidate)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\/", "/")
            .replace("\\\\", "")
            .trim()
            .trim('"', '\'')
        if (cleaned.isEmpty() || cleaned.startsWith("#") || cleaned.startsWith("data:", true) ||
            cleaned.startsWith("blob:", true) || cleaned.startsWith("javascript:", true)) return null

        return runCatching {
            val resolved = URL(URL(baseUrl), cleaned.replace(" ", "%20"))
            if (resolved.protocol != "http" && resolved.protocol != "https") return null
            if (resolved.host.isBlank()) return null
            resolved.toExternalForm().substringBefore('#')
        }.getOrNull()
    }

    private fun isTrackingPixel(url: String): Boolean {
        val path = runCatching { URL(url).path.lowercase(Locale.ROOT) }.getOrDefault("")
        return path.contains("/pixel.") || path.contains("/beacon.") ||
            path.endsWith("/spacer.gif") || path.endsWith("/blank.gif")
    }

    private fun decodeHtml(value: String): String {
        return value
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace(Regex("&#(x[0-9a-f]+|[0-9]+);", RegexOption.IGNORE_CASE)) { match ->
                val raw = match.groupValues[1]
                val codePoint = if (raw.startsWith("x", true)) {
                    raw.drop(1).toIntOrNull(16)
                } else {
                    raw.toIntOrNull()
                }
                codePoint?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                    ?: match.value
            }
    }

    private fun decodeJsonString(value: String): String = value
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

/**
 * Fetches a static page (or a direct image) and extracts images from standard HTML metadata
 * and image tags. Redirects are followed manually so saved cookies are only sent to their
 * matching host.
 */
class GenericPageMediaScraper(private val context: Context) {
    companion object {
        private const val TAG = "PageMediaScraper"
        private const val MAX_REDIRECTS = 5
        private const val MAX_PAGE_CHARS = 2 * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    suspend fun discoverImages(pageUrl: String): PageImageDiscovery? = withContext(Dispatchers.IO) {
        if (GenericPageMediaParser.isDirectImageUrl(pageUrl)) {
            return@withContext PageImageDiscovery(
                title = titleFromUrl(pageUrl),
                imageUrls = listOf(pageUrl)
            )
        }

        try {
            fetchPage(pageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Could not inspect page for images: ${e.message}")
            null
        }
    }

    private fun fetchPage(pageUrl: String): PageImageDiscovery? {
        var currentUrl = URL(pageUrl)
        var redirects = 0

        while (redirects <= MAX_REDIRECTS) {
            if (currentUrl.protocol != "http" && currentUrl.protocol != "https") return null
            val connection = currentUrl.openConnection() as? HttpURLConnection ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/avif,image/webp,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                cookieHeader(currentUrl)?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("Cookie", it)
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    currentUrl = URL(currentUrl, location)
                    redirects++
                    continue
                }
                if (responseCode !in 200..299) return null

                val contentType = connection.contentType?.substringBefore(';')
                    ?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (contentType.startsWith("image/")) {
                    return PageImageDiscovery(
                        title = titleFromUrl(currentUrl.toExternalForm()),
                        imageUrls = listOf(currentUrl.toExternalForm().substringBefore('#'))
                    )
                }
                if (contentType.isNotEmpty() &&
                    !contentType.contains("html") &&
                    contentType != "application/xhtml+xml" &&
                    contentType != "application/xml") return null

                val charset = responseCharset(connection.contentType)
                val html = connection.inputStream.bufferedReader(charset).use(::readLimitedPage)
                val parsed = GenericPageMediaParser.parse(currentUrl.toExternalForm(), html)
                if (parsed.imageUrls.isEmpty()) return null
                return parsed.copy(title = parsed.title.ifBlank { titleFromUrl(currentUrl.toExternalForm()) })
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun readLimitedPage(reader: java.io.BufferedReader): String {
        val result = StringBuilder()
        val buffer = CharArray(8192)
        var remaining = MAX_PAGE_CHARS
        while (remaining > 0) {
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            if (count == 0) continue
            result.append(buffer, 0, count)
            remaining -= count
        }
        return result.toString()
    }

    private fun responseCharset(contentType: String?): Charset {
        val charsetName = contentType?.split(';')
            ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
        return runCatching { charsetName?.let { Charset.forName(it) } ?: StandardCharsets.UTF_8 }
            .getOrDefault(StandardCharsets.UTF_8)
    }

    private fun cookieHeader(url: URL): String? {
        val cookies = AppCookieManager(context).load() ?: return null
        val host = url.host.lowercase(Locale.ROOT).trimEnd('.')
        val path = url.path.ifBlank { "/" }
        val secureRequest = url.protocol.equals("https", ignoreCase = true)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val matched = linkedMapOf<String, String>()

        for (rawLine in cookies.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            val httpOnly = line.startsWith("#HttpOnly_", ignoreCase = true)
            if (httpOnly) {
                line = line.substringAfter('_')
            } else if (line.startsWith('#')) {
                continue
            }

            val columns = line.split('\t')
            if (columns.size < 7) continue
            val domain = columns[0].trim().trimStart('.').lowercase(Locale.ROOT)
            if (domain.isEmpty()) continue
            val includeSubdomains = columns[1].equals("TRUE", ignoreCase = true)
            val domainMatches = host == domain || (includeSubdomains && host.endsWith(".$domain"))
            if (!domainMatches) continue

            val cookiePath = columns[2].ifBlank { "/" }
            if (!path.startsWith(cookiePath)) continue
            if (columns[3].equals("TRUE", ignoreCase = true) && !secureRequest) continue
            val expires = columns[4].toLongOrNull() ?: 0L
            if (expires > 0L && expires < nowSeconds) continue

            val name = columns[5].trim()
            if (name.isNotEmpty()) matched[name] = columns[6]
        }

        return matched.entries.joinToString("; ") { "${it.key}=${it.value}" }
            .takeIf { it.isNotBlank() }
    }

    private fun titleFromUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            parsed.path.substringAfterLast('/').substringBeforeLast('.')
                .replace(Regex("[-_]+"), " ")
                .trim()
                .take(80)
                .ifBlank { parsed.host.removePrefix("www.") }
        }.getOrDefault("Website images")
    }
}
