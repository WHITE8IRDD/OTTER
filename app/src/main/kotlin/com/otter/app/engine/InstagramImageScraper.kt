package com.otter.app.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Reads the media of an Instagram post / carousel — photos **and** video slides.
 *
 * Endpoints are tried in order of reliability and their results are *merged*, so a
 * partially readable payload no longer looks like a finished one-slide post:
 *
 *  1. post page HTML (desktop, then mobile user agent)
 *  2. `www.instagram.com/api/v1/media/{id}/info/`
 *  3. `/embed/captioned/`
 *  4. `?__a=1&__d=dis` (legacy JSON)
 *  5. WebView rendering, as the last resort
 *
 * Every URL is stored exactly as the CDN returned it, including the signed query
 * string: Instagram answers 403 without it, which is what left previews blank and
 * "Download Selected" saving nothing.
 *
 * A mixed carousel returns two ordered lists: the photo slides, for the image
 * download path, and the video slides (with their poster frames) for the video path.
 * The slide count the post declares covers both, so a post that lists 8 slides but
 * only exposes 5 readable ones is reported as a partial read.
 */
class InstagramImageScraper(private val context: Context) {

    companion object {
        private const val TAG = "InstaScraper"
        private const val HTTP_TIMEOUT_MS = 6_000
        private const val WEBVIEW_TIMEOUT_MS = 25_000L
        private const val OVERALL_HTTP_BUDGET_MS = 12_000L

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val IG_APP_ID = "936619743392459"

        // A browser may make hydration JSON available, but DOM images/backgrounds are
        // not evidence of post membership or media type. Reuse the same typed parser.
        private val JS_EXTRACTOR = """
            (function(){ setTimeout(function(){
                Android.onResult(JSON.stringify({html:document.documentElement.outerHTML}));
            }, 1000); })();
        """.trimIndent()
    }

    private data class FetchRequest(
        val label: String,
        val url: String,
        val userAgent: String,
        val headers: Map<String, String> = emptyMap()
    )

    data class ScrapeResult(
        /** Photo slides, in payload order. */
        val imageUrls: List<String>,
        /** Video slides of the same post, in payload order. */
        val videoUrls: List<String> = emptyList(),
        /** Poster frame per entry of [videoUrls] (same indexes, blank when unknown). */
        val videoPosters: List<String> = emptyList(),
        /** The post's slides in payload order, typed photo or video (empty when unknown). */
        val slides: List<InstagramMediaParser.Slide> = emptyList(),
        /** Slides the payload declared (0 = unknown). */
        val expectedCount: Int,
        val shortcode: String,
        /** Which endpoints answered, for logging/diagnostics. */
        val sources: List<String>,
        /** Post caption, when the payload carried one. */
        val caption: String = "",
        val note: String? = null
    ) {
        /** Photos + videos the scrape could read. */
        val mediaCount: Int get() = imageUrls.size + videoUrls.size

        /**
         * True when the post's own media begins with a video — a single-video post or a reel. The
         * picker puts that first instead of leading with the page's pictures.
         */
        val primaryIsVideo: Boolean
            get() = slides.firstOrNull()?.isVideo ?: (imageUrls.isEmpty() && videoUrls.isNotEmpty())

        /** Ordered slides, falling back to photos-then-videos when the order was lost. */
        fun orderedSlides(): List<InstagramMediaParser.Slide> =
            if (slides.isNotEmpty()) slides else
                imageUrls.map { InstagramMediaParser.Slide(it, isVideo = false) } +
                    videoUrls.mapIndexed { index, url ->
                        InstagramMediaParser.Slide(
                            url,
                            isVideo = true,
                            posterUrl = videoPosters.getOrNull(index).orEmpty()
                        )
                    }

        val isComplete: Boolean
            get() = mediaCount > 0 && (expectedCount == 0 || mediaCount >= expectedCount)

        val isPartial: Boolean
            get() = mediaCount > 0 && !isComplete

        fun posterFor(index: Int): String = videoPosters.getOrNull(index).orEmpty()
    }

    /** Compatibility helper: the ordered list of readable image URLs. */
    suspend fun scrape(url: String): List<String> = scrapeDetailed(url).imageUrls

    suspend fun scrapeDetailed(postUrl: String): ScrapeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val parsed = InstagramUrl.parse(postUrl)
        val shortcode = parsed?.shortcode.orEmpty()
        // The kind is kept: a reel asked for as /p/<code>/ answers with the share redirect page,
        // which carries no media, and that is why reels used to read as empty.
        val canonical = parsed?.canonicalUrl ?: postUrl
        val mediaId = InstagramMediaParser.mediaIdFromShortcode(shortcode)
        val merged = LinkedHashMap<String, String>()
        val mergedVideos = LinkedHashMap<String, String>()
        val mergedPosters = LinkedHashMap<String, String>()
        var expected = 0
        var caption = ""
        var note: String? = null
        val sources = mutableListOf<String>()
        val orderedSlides = LinkedHashMap<String, InstagramMediaParser.Slide>()

        fun total(): Int = merged.size + mergedVideos.size

        fun absorb(result: InstagramMediaParser.Result?, label: String) {
            if (result == null) return
            // Different endpoints expose different renditions of the SAME slides.
            // Unioning URL sets invents duplicates and can make partial reads look complete.
            if (result.mediaCount > total() ||
                (result.mediaCount == total() && result.expectedCount > expected)) {
                merged.clear()
                mergedVideos.clear()
                mergedPosters.clear()
                orderedSlides.clear()
                result.orderedSlides().forEach { slide ->
                    val key = InstagramMediaParser.mediaKey(slide.url)
                    orderedSlides[key] = slide
                    if (slide.isVideo) {
                        mergedVideos[key] = slide.url
                        mergedPosters[key] = slide.posterUrl
                    } else merged[key] = slide.url
                }
            }
            expected = maxOf(expected, result.expectedCount)
            if (caption.isBlank()) caption = result.caption
            if (!result.note.isNullOrBlank()) note = result.note
            if (result.imageUrls.isNotEmpty() || result.videoUrls.isNotEmpty()) {
                sources += "$label(${result.imageUrls.size}+${result.videoUrls.size}${if (result.expectedCount > 0) "/${result.expectedCount}" else ""})"
            } else {
                sources += "$label(0)"
            }
        }

        // `>= 2` used to be part of this test, which meant a one-slide post never counted as
        // finished and always paid for the extra endpoint round. A post that declares one slide and
        // yields one slide is complete.
        fun complete(): Boolean = total() > 0 && (expected == 0 || total() >= expected)

        fun budgetLeft(): Boolean = System.currentTimeMillis() - startedAt < OVERALL_HTTP_BUDGET_MS

        // The endpoints below all describe the same post, so they are fetched
        // together: waiting for one before starting the next only added latency
        // (five round trips, each with its own timeout, before anything appeared).
        // Whatever answers is merged, and the first payload that declares the full
        // slide count ends the search.
        val apiHeaders = linkedMapOf(
            "X-IG-App-ID" to IG_APP_ID,
            "Accept" to "*/*",
            "Referer" to "https://www.instagram.com/",
            "X-Requested-With" to "XMLHttpRequest"
        )
        val apiUrl = if (mediaId > 0) "https://www.instagram.com/api/v1/media/$mediaId/info/" else null
        val embedUrl = if (shortcode.isNotEmpty()) {
            "https://www.instagram.com/p/$shortcode/embed/captioned/"
        } else null

        // 1-3. Post page (desktop + mobile) and the public web API.
        // Read responses as they arrive: a complete API payload should not wait for a
        // slower browser-UA page request to hit its timeout.
        if (!complete()) {
            val firstRound = buildList {
                add(FetchRequest("page", canonical, DESKTOP_UA))
                add(FetchRequest("page-mobile", canonical, MOBILE_UA))
                apiUrl?.let { add(FetchRequest("web-api", it, MOBILE_UA, apiHeaders)) }
            }
            fetchInParallel(firstRound, shouldStop = { complete() }) { label, body ->
                if (body == null) {
                    sources += "$label(http-error)"
                } else {
                    val parsed = if (label == "web-api") {
                        InstagramMediaParser.parseJson(body, shortcode = shortcode)
                    } else {
                        InstagramMediaParser.parseHtml(body, canonical)
                    }
                    absorb(parsed, label)
                    if (label == "page" && parsed.imageUrls.isEmpty() &&
                        parsed.videoUrls.isEmpty() && InstagramMediaParser.looksLikeLoginWall(body)
                    ) {
                        note = "Instagram showed a login wall"
                    }
                }
            }
        }

        // 4-5. Embed page and legacy JSON, in parallel, only if the first round did
        // not yield a complete post (or a carousel with at least two slides).
        if (!complete() && budgetLeft()) {
            // The legacy `?__a=1&__d=dis` JSON endpoint is gone from this list on purpose: it has
            // answered 404 to logged-out requests for years, and requesting it cost a full
            // connect/read timeout on the one post type (a single slide) that needed the wait least.
            val secondRound = buildList {
                embedUrl?.let { add(FetchRequest("embed", it, DESKTOP_UA)) }
            }
            fetchInParallel(secondRound, shouldStop = { complete() }) { label, body ->
                if (body == null) {
                    sources += "$label(http-error)"
                } else {
                    absorb(InstagramMediaParser.parseHtml(body, canonical), label)
                }
            }
        }


        // 6. WebView rendering — only when plain HTTP read nothing at all. A real browser
        // render is the one thing that still reaches a logged-out page when every plain
        // request comes back empty. It is slow, so a partial read is never retried here.
        if (total() == 0 && !CookieManager.getInstance().hasCookies()) {
            val rendered = scrapeWithWebView(canonical)
            val renderedCount = rendered.mediaCount
            if (renderedCount > 0) {
                absorb(rendered, "webview")
                sources += "webview($renderedCount)"
                if (expected == 0) expected = renderedCount
            } else {
                sources += "webview(0)"
            }
        } else {
            // Something was read; topping it up is not worth another 20 seconds.
            sources += "webview(skipped-partial-read)"
        }

        val videos = mergedVideos.values.toList()
        val posters = videos.map { mergedPosters[InstagramMediaParser.mediaKey(it)].orEmpty() }
        val videoKeys = mergedVideos.keys
        val posterKeys = mergedPosters.values.filter { it.isNotBlank() }
            .map { InstagramMediaParser.mediaKey(it) }.toSet()
        val urls = merged.values.filterNot { url ->
            val key = InstagramMediaParser.mediaKey(url)
            videoKeys.contains(key) || posterKeys.contains(key)
        }
        val readCount = urls.size + videos.size
        if (readCount == 0) {
            Log.w(TAG, "No Instagram media found for $canonical (${sources.joinToString()})")
            if (note == null) note = "Instagram returned no readable media for this post"
        } else {
            Log.i(TAG, "Instagram media: ${urls.size} photo(s) + ${videos.size} video(s) " +
                "of ${if (expected > 0) expected else "?"} via ${sources.joinToString()}")
            // Only the CDN path is logged: the query string of these URLs is a signed token, and a
            // logcat dump must not hand one out.
            urls.forEachIndexed { index, url -> Log.i(TAG, "  [photo $index] ${redactUrl(url)}") }
            videos.forEachIndexed { index, url -> Log.i(TAG, "  [video $index] ${redactUrl(url)}") }
        }
        // Only slides that survived the filtering below are ordered; a slide that was dropped (a
        // video's poster frame, for instance) must not reappear as an item of its own.
        val keptKeys = (urls + videos).mapTo(LinkedHashSet()) { InstagramMediaParser.mediaKey(it) }
        val ordered = orderedSlides.values.filter { keptKeys.contains(InstagramMediaParser.mediaKey(it.url)) }

        ScrapeResult(
            imageUrls = urls,
            videoUrls = videos,
            videoPosters = posters,
            slides = ordered,
            expectedCount = if (expected > 0) expected else readCount,
            shortcode = shortcode,
            sources = sources,
            caption = caption,
            note = note
        )
    }

    /** Consume completed endpoint responses as soon as they arrive and cancel the rest once enough is known. */
    private suspend fun fetchInParallel(
        requests: List<FetchRequest>,
        shouldStop: () -> Boolean,
        onResponse: (String, String?) -> Unit
    ) = coroutineScope {
        if (requests.isEmpty()) return@coroutineScope
        val responses = Channel<Pair<String, String?>>(capacity = requests.size)
        val jobs = requests.map { request ->
            launch(Dispatchers.IO) {
                val body = try {
                    fetchText(request.url, request.userAgent, request.headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "${request.label} request failed: ${e.message}")
                    null
                }
                responses.send(request.label to body)
            }
        }

        try {
            var remaining = jobs.size
            while (remaining > 0 && !shouldStop()) {
                val (label, body) = responses.receive()
                remaining--
                onResponse(label, body)
            }
        } finally {
            jobs.forEach { it.cancel() }
            responses.close()
        }
    }

    private suspend fun fetchText(
        url: String,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        val requestJob = CoroutineScope(Dispatchers.IO).launch {
            var connection: HttpURLConnection? = null
            try {
                val openedConnection = URL(url).openConnection() as HttpURLConnection
                connection = openedConnection
                activeConnection.set(openedConnection)
                if (!continuation.isActive) return@launch

                openedConnection.apply {
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
                    instanceFollowRedirects = true
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                }
                val code = openedConnection.responseCode
                if (code !in 200..299) {
                    Log.i(TAG, "GET ${redactUrl(url)} -> $code")
                    if (continuation.isActive) continuation.resume(null)
                } else {
                    val body = BufferedReader(InputStreamReader(openedConnection.inputStream, Charsets.UTF_8)).use { reader ->
                        val body = reader.readTextLimited(2_000_000)
                        body
                    }
                    if (continuation.isActive) continuation.resume(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "GET ${redactUrl(url)} failed: ${e.message}")
                if (continuation.isActive) continuation.resume(null)
            } finally {
                connection?.disconnect()
                activeConnection.set(null)
            }
        }
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
            requestJob.cancel()
        }
    }

    /** Returns the rendered photo URLs and video URLs. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithWebView(url: String): InstagramMediaParser.Result =
        withContext(Dispatchers.Main) {
            Log.i(TAG, "Starting WebView scrape for ${redactUrl(url)}")
            var webView: WebView? = null
            val result = try {
                withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                    suspendCancellableCoroutine<InstagramMediaParser.Result> { cont ->
                        val activity = context as? Activity
                        val container = activity?.let { act ->
                            FrameLayout(context).apply {
                                layoutParams = FrameLayout.LayoutParams(1080, 4000)
                                setBackgroundColor(Color.TRANSPARENT)
                                (act.window.decorView as? ViewGroup)?.addView(this)
                            }
                        }
                        val detached = container == null

                        webView = WebView(context).apply {
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                            settings.userAgentString = MOBILE_UA
                            alpha = 0.01f

                            // This scraper only handles public posts. Do not reuse a
                            // WebView login session while probing the logged-out page.
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onResult(json: String) {
                                    Log.i(TAG, "WebView JS returned ${json.length} chars")
                                    if (cont.isActive) cont.resume(parseExtractorPayload(json, url))
                                }

                                @JavascriptInterface
                                fun log(message: String) {
                                    Log.i(TAG, "JS: $message")
                                }
                            }, "Android")

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                    if (msg != null) Log.i(TAG, "JS[${msg.messageLevel()}]: ${msg.message()}")
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                    Log.i(TAG, "WebView page started: $pageUrl")
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    Log.i(TAG, "WebView page finished, injecting extractor")
                                    view?.evaluateJavascript(JS_EXTRACTOR, null)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    Log.w(TAG, "WebView error: ${error?.description}")
                                }
                            }
                        }

                        if (detached) {
                            // Nothing to attach to (application context). Size the view by
                            // hand, otherwise the page renders at 0x0 and never loads images.
                            runCatching { webView?.layout(0, 0, 1080, 4000) }
                        }
                        container?.addView(webView)
                        webView?.loadUrl(url)
                    }
                } ?: InstagramMediaParser.Result(emptyList())
            } finally {
                webView?.let { wv ->
                    wv.stopLoading()
                    wv.removeJavascriptInterface("Android")
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
                Log.i(TAG, "WebView cleaned up")
            }
            result
        }

    /**
     * Reads the extractor payload: `{"images":[…],"videos":[…],"posters":[…],"html":"…"}`
     * from the current script, and the bare `[…]` array older builds may still return.
     */
    private fun parseExtractorPayload(json: String, canonicalUrl: String = ""): InstagramMediaParser.Result {
        val html = runCatching { org.json.JSONObject(json).optString("html") }.getOrDefault("")
        return InstagramMediaParser.parseHtml(html, canonicalUrl)
    }

    /**
     * Whether this URL names an Instagram post the scraper can read. Reels and `/tv/` links are
     * included: excluding them is what made single-video posts skip the whole public-page path.
     */
    fun canExtract(url: String): Boolean = InstagramUrl.isMediaUrl(url)

    private fun java.io.Reader.readTextLimited(limit: Int): String {
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return text.toString()
            if (text.length + count > limit) throw java.io.IOException("Instagram payload exceeded size limit")
            text.append(buffer, 0, count)
        }
    }

    /** A CDN URL without its signed query string, for logging. */
    private fun redactUrl(url: String): String = url.substringBefore('?')
}
