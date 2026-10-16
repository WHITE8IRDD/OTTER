package com.otter.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections

/**
 * Turns a TikTok share link into one the extraction engine recognises.
 *
 * Two things have to happen and neither is offline:
 *
 *  * the `vm.tiktok.com` / `vt.tiktok.com` short link has to be followed, because it carries no post
 *    id at all;
 *  * the forwarded form (`www.tiktok.com/share/video/<id>/`) has to be given an author handle,
 *    because that is the shape the extractor matches (`@<handle>/video/<id>`) — the pinned engine
 *    build has no rule for `share/video/` at all, which is why every TikTok video from the share
 *    sheet failed identically.
 *
 * The author comes from TikTok's own public oEmbed endpoint, the documented embed lookup that needs
 * no login and sends no cookies. If anything here fails the original link is returned unchanged and
 * the engine gets its usual turn: this can only improve the input, never replace a working path.
 */
object TikTokLinkResolver {

    private const val TAG = "TikTokLinkResolver"
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 4_000
    private const val MAX_REDIRECTS = 3
    private const val CACHE_SIZE = 64

    /** A desktop UA: the mobile one makes TikTok answer share links with an "open in app" shell. */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /**
     * Resolved links, kept process-wide because the fetch and the download run in different objects
     * (and the download is often issued by the foreground service with the link the user pasted).
     */
    private val resolved = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
                size > CACHE_SIZE
        }
    )

    /** What the fetch already learned about this link, if anything. */
    fun lookup(url: String?): String? = url?.let { resolved[it.trim()] }

    fun remember(original: String, canonical: String) {
        if (original.isBlank() || canonical.isBlank() || original == canonical) return
        resolved[original.trim()] = canonical
    }

    /**
     * The URL to extract this link with. Never throws: a lookup that fails returns [url].
     *
     * Runs on [Dispatchers.IO] and is bounded by the timeouts above, so a TikTok link cannot hold
     * the fetch hostage the way the unbounded redirect walk used to.
     */
    suspend fun resolve(url: String?): String = withContext(Dispatchers.IO) {
        val original = url?.trim().orEmpty()
        if (original.isEmpty() || !TikTokUrl.hostMatches(original)) return@withContext original
        resolved[original]?.let { return@withContext it }

        // The whole body is a *resolution attempt*, not a requirement: every helper below already
        // guards its own network call, but this is the backstop the KDoc promises - nothing reachable
        // from the fetch's hot path may turn "could not improve this link" into a crash that takes
        // the whole extraction down with it.
        val canonical = runCatching {
            val parsed = TikTokUrl.parse(original) ?: return@runCatching original
            var working = original

            // 1. A short link carries no id: follow it, at most a few hops.
            if (parsed.itemId.isEmpty()) {
                working = followRedirects(original)
            }
            var current = TikTokUrl.parse(working) ?: parsed

            // 2. An id without a handle: ask the embed endpoint who posted it.
            if (current.itemId.isNotEmpty() && current.author.isEmpty() &&
                current.kind != TikTokUrl.Kind.PHOTO
            ) {
                val author = oEmbedAuthor(working)
                if (author.isNotBlank()) {
                    current = current.copy(author = author)
                    working = "https://www.tiktok.com/@$author/" +
                        (if (current.kind == TikTokUrl.Kind.PHOTO) "photo/" else "video/") + current.itemId
                }
            }

            TikTokUrl.engineUrl(working).ifBlank { original }
        }.onFailure { Log.w(TAG, "TikTok link resolution failed, using the link as given: ${it.message}") }
            .getOrDefault(original)

        remember(original, canonical)
        if (canonical != original) {
            Log.i(TAG, "Resolved TikTok link ${original.take(60)} -> $canonical")
        }
        canonical
    }

    /** Follows the short link far enough to reach a URL that names a post. */
    private fun followRedirects(start: String): String {
        var current = start
        repeat(MAX_REDIRECTS) {
            val connection = runCatching { URL(current).openConnection() as HttpURLConnection }
                .getOrNull() ?: return current
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", UA)
                connection.setRequestProperty("Accept", "text/html,*/*;q=0.8")
                // A range request is the cheapest way to be answered with the Location header.
                connection.setRequestProperty("Range", "bytes=0-0")
                val status = connection.responseCode
                // The single-field accessor, not the raw header map: servers are inconsistent about
                // the case of "Location", and this one does the case-insensitive lookup for us.
                val location = connection.getHeaderField("Location")
                if (status !in 300..399 || location.isNullOrBlank()) return current
                val next = runCatching { URL(URL(current), location).toExternalForm() }.getOrNull()
                    ?: return current
                if (!TikTokUrl.hostMatches(next)) return current
                current = next
                val path = runCatching { URL(current).path }.getOrDefault("")
                if (TikTokUrl.parse(current)?.itemId?.isNotEmpty() == true &&
                    (path.contains("/video/") || path.contains("/photo/") || path.contains("/embed/"))
                ) return current
            } catch (e: Exception) {
                Log.w(TAG, "Could not follow the TikTok short link: ${e.message}")
                return current
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        return current
    }

    /** The author handle TikTok's public oEmbed endpoint reports for this link. */
    private fun oEmbedAuthor(url: String): String {
        val endpoint = "https://www.tiktok.com/oembed?url=" +
            URLEncoder.encode(url, "UTF-8")
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }
            .getOrNull() ?: return ""
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.i(TAG, "TikTok oEmbed answered HTTP $status")
                return ""
            }
            val body = connection.inputStream.bufferedReader().readText()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
            val authorUrl = json.optString("author_url")
            TikTokUrl.parse(authorUrl)?.author.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "TikTok oEmbed lookup failed: ${e.message}")
            ""
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
