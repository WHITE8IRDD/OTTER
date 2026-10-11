package com.otter.app.engine

import java.net.URL
import java.util.Locale

/** A small, standards-based fallback for ordinary image links and static media pages. */
data class PageImageDiscovery(
    val title: String,
    val imageUrls: List<String>
)

object GenericPageMediaParser {
    private const val MAX_IMAGES = 40

    private val titleTag = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
    private val mediaTag = Regex("""(?is)<(meta|img|source|link)\b[^>]*>""")
    private val attribute = Regex("""(?is)([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val jsonLdScript = Regex("""(?is)<script\b(?=[^>]*type\s*=\s*["']application/ld\+json["'])[^>]*>(.*?)</script\s*>""")
    private val jsonImageKeys = listOf("image", "thumbnailUrl", "thumbnail_url")

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
            for (value in jsonStringsForKeys(script.groupValues[1], jsonImageKeys)) {
                add(value)
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

    /**
     * Reads the string values of the given JSON keys without relying on a regex
     * character class (Java regex classes such as `[^"\]` are easy to get wrong)
     * and without a JSON dependency, so it also runs in plain JVM unit tests.
     */
    private fun jsonStringsForKeys(text: String, keys: List<String>): List<String> {
        val results = mutableListOf<String>()
        for (key in keys) {
            val needle = "\"$key\""
            var index = text.indexOf(needle)
            while (index >= 0 && results.size < MAX_IMAGES) {
                var cursor = index + needle.length
                while (cursor < text.length && (text[cursor].isWhitespace() || text[cursor] == ':')) cursor++
                if (cursor < text.length && text[cursor] == '"') {
                    val (value, next) = readJsonString(text, cursor) ?: (null to cursor)
                    if (value != null) {
                        results.add(decodeJsonString(value))
                        cursor = next
                    }
                } else if (cursor < text.length && text[cursor] == '[') {
                    var depth = 0
                    var i = cursor
                    while (i < text.length && results.size < MAX_IMAGES) {
                        when (text[i]) {
                            '[' -> depth++
                            ']' -> {
                                depth--
                                if (depth == 0) {
                                    i = text.length
                                    break
                                }
                            }
                            '"' -> {
                                val (value, next) = readJsonString(text, i) ?: (null to i)
                                if (value != null) {
                                    results.add(decodeJsonString(value))
                                    i = next
                                    continue
                                }
                            }
                        }
                        i++
                    }
                }
                index = text.indexOf(needle, (index + needle.length).coerceAtLeast(1))
            }
        }
        return results
    }

    private fun readJsonString(text: String, startQuote: Int): Pair<String, Int>? {
        if (startQuote >= text.length || text[startQuote] != '"') return null
        val builder = StringBuilder()
        var i = startQuote + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    val next = text[i + 1]
                    if (next == 'u' && i + 5 < text.length) {
                        val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            builder.append(code.toChar())
                            i += 6
                            continue
                        }
                    }
                    builder.append(next)
                    i += 2
                }
                c == '"' -> return builder.toString() to (i + 1)
                else -> {
                    builder.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun decodeJsonString(value: String): String = value
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
