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
