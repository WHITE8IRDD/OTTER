package com.otter.app.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The HTTP side of reading an Instagram post, without any Android dependency so
 * the exact code path the app uses can be verified against the live site from a
 * plain JVM test.
 *
 * Endpoints are tried in order of reliability and merged: a payload that only
 * exposes part of a carousel is completed by the next endpoint instead of being
 * presented as a finished one-slide post.
 */
class InstagramPostFetcher(
    private val desktopUserAgent: String = DESKTOP_UA,
    private val mobileUserAgent: String = MOBILE_UA,
    private val timeoutMs: Int = 15_000,
    private val overallBudgetMs: Long = 50_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val IG_APP_ID = "936619743392459"
    }

    data class Result(
        val imageUrls: List<String>,
        val expectedCount: Int,
        val shortcode: String,
        val sources: List<String>,
        val note: String? = null,
        val httpStatuses: List<String> = emptyList()
    ) {
        val isComplete: Boolean
            get() = imageUrls.isNotEmpty() && (expectedCount == 0 || imageUrls.size >= expectedCount)

        val isPartial: Boolean get() = imageUrls.isNotEmpty() && !isComplete
    }

    fun fetch(postUrl: String, cookies: String = ""): Result {
        val startedAt = clock()
        val shortcode = InstagramMediaParser.shortcodeFromUrl(postUrl)
        val canonical = if (shortcode.isNotEmpty()) "https://www.instagram.com/p/$shortcode/" else postUrl
        val mediaId = InstagramMediaParser.mediaIdFromShortcode(shortcode)
        val csrf = cookieValue(cookies, "csrftoken")

        val merged = LinkedHashMap<String, String>()
        var expected = 0
        var note: String? = null
        val sources = mutableListOf<String>()
        val statuses = mutableListOf<String>()

        fun absorb(result: InstagramMediaParser.Result?, label: String) {
            if (result == null) return
            result.imageUrls.forEach { url -> merged.putIfAbsent(InstagramMediaParser.mediaKey(url), url) }
            if (result.expectedCount > expected) expected = result.expectedCount
            if (!result.note.isNullOrBlank()) note = result.note
            sources += if (result.imageUrls.isEmpty()) {
                "$label(0)"
            } else {
                "$label(${result.imageUrls.size}${if (result.expectedCount > 0) "/${result.expectedCount}" else ""})"
            }
        }

        fun complete(): Boolean =
            merged.isNotEmpty() && (expected == 0 && merged.size >= 2 || expected > 0 && merged.size >= expected)

        fun budgetLeft(): Boolean = clock() - startedAt < overallBudgetMs

        fun get(url: String, label: String, ua: String, extra: Map<String, String> = emptyMap()): String? {
            val response = request(url, cookies, ua, extra)
            statuses += "$label=${response.status}"
            return response.body
        }

        // 1. Post page: current payloads embed the whole carousel in the HTML.
        if (!complete()) {
            val page = get(canonical, "page", desktopUserAgent)
            if (page != null) {
                val parsed = InstagramMediaParser.parseHtml(page, canonical)
                absorb(parsed, "page")
                if (parsed.imageUrls.isEmpty() && InstagramMediaParser.looksLikeLoginWall(page)) {
                    note = "Instagram showed a login wall"
                }
            }
        }

        // 2. Mobile page: some payloads are only served to mobile clients.
        if (!complete() && budgetLeft()) {
            val page = get(canonical, "page-mobile", mobileUserAgent)
            if (page != null) absorb(InstagramMediaParser.parseHtml(page, canonical), "page-mobile")
        }

        // 3. Public web API.
        if (!complete() && mediaId > 0 && budgetLeft()) {
            val headers = linkedMapOf(
                "X-IG-App-ID" to IG_APP_ID,
                "Accept" to "*/*",
                "Referer" to "https://www.instagram.com/",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (csrf.isNotEmpty()) headers["X-CSRFToken"] = csrf
            val body = get("https://www.instagram.com/api/v1/media/$mediaId/info/", "web-api", mobileUserAgent, headers)
            if (body != null) absorb(InstagramMediaParser.parseJson(body), "web-api")
        }

        // 4. Embed page (light, often served without a session).
        if (!complete() && shortcode.isNotEmpty() && budgetLeft()) {
            val embed = get("https://www.instagram.com/p/$shortcode/embed/captioned/", "embed", desktopUserAgent)
            if (embed != null) absorb(InstagramMediaParser.parseHtml(embed, canonical), "embed")
        }

        // 5. Legacy `?__a=1` JSON.
        if (!complete() && budgetLeft()) {
            val body = get("$canonical?__a=1&__d=dis", "a1", mobileUserAgent)
            if (body != null) absorb(InstagramMediaParser.parseJson(body), "a1")
        }

        val urls = merged.values.toList()
        if (urls.isEmpty() && note == null) note = "Instagram returned no readable media for this post"
        return Result(
            imageUrls = urls,
            expectedCount = if (expected > 0) expected else urls.size,
            shortcode = shortcode,
            sources = sources,
            note = note,
            httpStatuses = statuses
        )
    }

    private data class Response(val status: Int, val body: String?)

    private fun request(
        url: String,
        cookies: String,
        userAgent: String,
        extraHeaders: Map<String, String>
    ): Response {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Response(-1, null)
        }
        return try {
            connection.apply {
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies)
                extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Response(code, null)
            } else {
                val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                    .use { it.readText() }
                Response(code, body)
            }
        } catch (e: Exception) {
            Response(-1, null)
        } finally {
            connection.disconnect()
        }
    }

    private fun cookieValue(cookies: String, name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return cookies.split(';')
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').lowercase(Locale.ROOT) == lower }
            ?.substringAfter('=', "")
            .orEmpty()
    }
}
