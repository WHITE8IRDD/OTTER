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

/** A small, standards-based fallback for ordinary image links and static media pages. */
data class PageImageDiscovery(
    val title: String,
    val imageUrls: List<String>
)

/**
 * Extracts images exposed by common HTML conventions. This is deliberately a fallback,
 * not a replacement for yt-dlp: JavaScript-only pages and protected streams still need
 * their site's supported extractor or valid user-provided cookies.
 */
object GenericPageMediaParser {
    private const val MAX_IMAGES = 40

    private val titleTag = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
    private val mediaTag = Regex("""(?is)<(meta|img|source|link)\b[^>]*>""")
    private val attribute = Regex("""(?is)([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val jsonLdScript = Regex("""(?is)<script\b(?=[^>]*type\s*=\s*["']application/ld\+json["'])[^>]*>(.*?)</script\s*>""")
    private val jsonImageField = Regex("""(?is)"(?:image|thumbnailUrl|thumbnail_url)"\s*:\s*(?:"((?:\.|[^"\])*)"|\[(.*?)\])""")
    private val jsonString = Regex("\"((?:\\.|[^\"\\])*)\"")

    private val imageMetaNames = setOf(
        "og:image", "og:image:url", "og:image:secure_url",
        "twitter:image", "twitter:image:src", "image", "thumbnailurl"
    )

    /** Parse a bounded set of likely image URLs while preserving their signed query strings. */
    fun parse(pageUrl: String, html: String): PageImageDiscovery {
        val images = LinkedHashSet<String>()
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
                    if (name in imageMetaNames) add(attributes["content"])
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
                }
            }
        }

        for (script in jsonLdScript.findAll(html)) {
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

        return PageImageDiscovery(title = title, imageUrls = images.toList())
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
