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
import com.otter.app.data.cookies.CookieManager as AppCookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads the pictures of an Instagram post / carousel.
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
 */
class InstagramImageScraper(private val context: Context) {

    companion object {
        private const val TAG = "InstaScraper"
        private const val HTTP_TIMEOUT_MS = 15_000
        private const val WEBVIEW_TIMEOUT_MS = 30_000L
        private const val OVERALL_HTTP_BUDGET_MS = 50_000L

        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val IG_APP_ID = "936619743392459"

        /** WebView renderer: walks the gallery, keeping the full signed URLs. */
        private val JS_EXTRACTOR = """
(function(){
    var SEEN={}, ORDER=[], MAX=24, MS=1400, C=0;
    function key(u){ return u.split('?')[0]; }
    function norm(u){
        if(!u) return '';
        return u.replace(/&amp;/g,'&').replace(/\\u0026/g,'&').replace(/\\\//g,'/');
    }
    function add(u){
        u = norm(u);
        if(!u || u.indexOf('http')!==0) return;
        var k = key(u);
        if(k.indexOf('scontent')<0 && k.indexOf('cdninstagram')<0 && k.indexOf('fbcdn')<0) return;
        if(k.indexOf('/rsrc.php')>=0 || k.indexOf('/v/t51.2885-19/')>=0) return;
        if(SEEN[k]) return;
        SEEN[k]=true; ORDER.push(u);
    }
    function scanText(txt){
        if(!txt) return;
        var re=/https?:[^"'\\\s<>]*?(?:scontent|cdninstagram|fbcdn)[^"'\\\s<>]*/g, m;
        while((m=re.exec(txt))!==null){ add(m[0]); }
    }
    function collect(){
        var before=ORDER.length;
        document.querySelectorAll('img').forEach(function(i){
            add(i.currentSrc || i.src);
            var ss=i.getAttribute('srcset')||'';
            if(ss){ ss.split(',').forEach(function(p){ add(p.trim().split(' ')[0]); }); }
        });
        document.querySelectorAll('video').forEach(function(v){ if(v.poster) add(v.poster); });
        document.querySelectorAll('*').forEach(function(el){
            var bg=window.getComputedStyle(el).backgroundImage;
            if(bg && bg.indexOf('url(')>=0){
                var m=bg.match(/url\(["']?([^"')]+)["']?\)/);
                if(m) add(m[1]);
            }
        });
        document.querySelectorAll('script[type="application/json"]').forEach(function(sc){
            scanText(sc.textContent);
        });
        return ORDER.length-before;
    }
    function nextBtn(){
        var a=document.querySelector('button[aria-label="Next"],button[aria-label*="Next" i],button[aria-label*="next" i]');
        if(a && a.offsetParent!==null) return a;
        var s=document.querySelector('svg[aria-label*="Next" i]');
        if(s){ var p=s.closest('button')||s.parentElement; if(p && p.offsetParent!==null) return p; }
        return null;
    }
    function click(b){
        ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){
            try{ b.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window})); }catch(e){}
        });
        try{ b.click(); }catch(e){}
    }
    function tick(){
        C++;
        var added=collect();
        Android.log(C+'/'+MAX+': +'+added+' total='+ORDER.length);
        if(C>=MAX || ORDER.length>=20){ done(); return; }
        var n=nextBtn();
        if(n){ click(n); setTimeout(tick,MS); }
        else if(C===1){ window.scrollTo(0,document.body.scrollHeight); setTimeout(function(){ window.scrollTo(0,0); collect(); done(); },1500); }
        else { done(); }
    }
    function done(){
        collect();
        Android.onResult(JSON.stringify(ORDER));
    }
    setTimeout(tick,2000);
})();
"""
    }

    data class ScrapeResult(
        val imageUrls: List<String>,
        /** Slides the payload declared (0 = unknown). */
        val expectedCount: Int,
        val shortcode: String,
        /** Which endpoints answered, for logging/diagnostics. */
        val sources: List<String>,
        val note: String? = null
    ) {
        val isComplete: Boolean
            get() = imageUrls.isNotEmpty() && (expectedCount == 0 || imageUrls.size >= expectedCount)

        val isPartial: Boolean
            get() = imageUrls.isNotEmpty() && !isComplete
    }

    /** Compatibility helper: the ordered list of readable image URLs. */
    suspend fun scrape(url: String): List<String> = scrapeDetailed(url).imageUrls

    suspend fun scrapeDetailed(postUrl: String): ScrapeResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val shortcode = InstagramMediaParser.shortcodeFromUrl(postUrl)
        val canonical = if (shortcode.isNotEmpty()) {
            "https://www.instagram.com/p/$shortcode/"
        } else {
            postUrl
        }
        val mediaId = InstagramMediaParser.mediaIdFromShortcode(shortcode)
        val cookies = loadCookiesForDomain("instagram.com")
        val csrf = extractCookieValue(cookies, "csrftoken")

        val merged = LinkedHashMap<String, String>()
        var expected = 0
        var note: String? = null
        val sources = mutableListOf<String>()

        fun absorb(result: InstagramMediaParser.Result?, label: String) {
            if (result == null) return
            result.imageUrls.forEach { url ->
                merged.putIfAbsent(InstagramMediaParser.mediaKey(url), url)
            }
            if (result.expectedCount > expected) expected = result.expectedCount
            if (!result.note.isNullOrBlank()) note = result.note
            if (result.imageUrls.isNotEmpty()) {
                sources += "$label(${result.imageUrls.size}${if (result.expectedCount > 0) "/${result.expectedCount}" else ""})"
            } else {
                sources += "$label(0)"
            }
        }

        fun complete(): Boolean =
            merged.isNotEmpty() && (expected == 0 && merged.size >= 2 || expected > 0 && merged.size >= expected)

        fun budgetLeft(): Boolean = System.currentTimeMillis() - startedAt < OVERALL_HTTP_BUDGET_MS

        // 1. Post page: all recent payload variants are embedded in the HTML.
        if (!complete()) {
            val page = fetchText(canonical, cookies, DESKTOP_UA)
            if (page != null) {
                val parsed = InstagramMediaParser.parseHtml(page, canonical)
                absorb(parsed, "page")
                if (parsed.imageUrls.isEmpty() && InstagramMediaParser.looksLikeLoginWall(page)) {
                    note = "Instagram showed a login wall"
                }
            } else {
                sources += "page(http-error)"
            }
        }

        // 2. Mobile page: some payloads are only served to mobile user agents.
        if (!complete() && budgetLeft()) {
            val page = fetchText(canonical, cookies, MOBILE_UA)
            if (page != null) absorb(InstagramMediaParser.parseHtml(page, canonical), "page-mobile")
        }

        // 3. Web API with the public app id.
        if (!complete() && mediaId > 0 && budgetLeft()) {
            val headers = linkedMapOf(
                "X-IG-App-ID" to IG_APP_ID,
                "Accept" to "*/*",
                "Referer" to "https://www.instagram.com/",
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (csrf.isNotEmpty()) headers["X-CSRFToken"] = csrf
            val body = fetchText(
                "https://www.instagram.com/api/v1/media/$mediaId/info/",
                cookies,
                MOBILE_UA,
                headers
            )
            if (body != null) absorb(InstagramMediaParser.parseJson(body), "web-api")
        }

        // 4. Embed page (lighter, often served without a session).
        if (!complete() && shortcode.isNotEmpty() && budgetLeft()) {
            val embed = fetchText(
                "https://www.instagram.com/p/$shortcode/embed/captioned/",
                cookies,
                DESKTOP_UA
            )
            if (embed != null) absorb(InstagramMediaParser.parseHtml(embed, canonical), "embed")
        }

        // 5. Legacy `?__a=1` JSON.
        if (!complete() && budgetLeft()) {
            val body = fetchText("$canonical?__a=1&__d=dis", cookies, MOBILE_UA)
            if (body != null) absorb(InstagramMediaParser.parseJson(body), "a1")
        }

        // 6. WebView rendering — only when the static payloads could not be read fully.
        if (!complete() && context is Activity) {
            val rendered = scrapeWithWebView(canonical)
            if (rendered.isNotEmpty()) {
                rendered.forEach { url -> merged.putIfAbsent(InstagramMediaParser.mediaKey(url), url) }
                sources += "webview(${rendered.size})"
                if (expected == 0) expected = rendered.size
            } else {
                sources += "webview(0)"
            }
        } else if (!complete()) {
            sources += "webview(skipped-no-activity)"
        }

        val urls = merged.values.toList()
        if (urls.isEmpty()) {
            Log.w(TAG, "No Instagram images found for $canonical (${sources.joinToString()})")
            if (note == null) note = "Instagram returned no readable media for this post"
        } else {
            Log.i(TAG, "Instagram images: ${urls.size} of ${if (expected > 0) expected else "?"} " +
                "via ${sources.joinToString()}")
            urls.forEachIndexed { index, url -> Log.i(TAG, "  [$index] ${url.take(160)}") }
        }
        ScrapeResult(
            imageUrls = urls,
            expectedCount = if (expected > 0) expected else urls.size,
            shortcode = shortcode,
            sources = sources,
            note = note
        )
    }

    private fun fetchText(
        url: String,
        cookies: String,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.w(TAG, "Bad URL $url: ${e.message}")
            return null
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
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "GET $url -> $code")
                null
            } else {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithWebView(url: String): List<String> = withContext(Dispatchers.Main) {
        Log.i(TAG, "Starting WebView scrape for $url")
        var webView: WebView? = null
        val result = try {
            withTimeoutOrNull(WEBVIEW_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<String>> { cont ->
                    val activity = context as? Activity
                    val container = activity?.let { act ->
                        FrameLayout(context).apply {
                            layoutParams = FrameLayout.LayoutParams(1080, 4000)
                            setBackgroundColor(Color.TRANSPARENT)
                            (act.window.decorView as? ViewGroup)?.addView(this)
                        }
                    }

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

                        CookieManager.getInstance().setAcceptCookie(true)
                        injectWebViewCookies()

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onResult(json: String) {
                                Log.i(TAG, "WebView JS returned ${json.length} chars")
                                if (cont.isActive) cont.resume(parseJsonArray(json))
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

                    container?.addView(webView)
                    webView?.loadUrl(url)
                }
            } ?: emptyList()
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

    private fun injectWebViewCookies() {
        try {
            val content = AppCookieManager(context).load() ?: return
            val manager = CookieManager.getInstance()
            val domain = "https://www.instagram.com"
            var count = 0
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 7) {
                    val name = parts[5]
                    val value = parts.getOrElse(6) { "" }
                    if (name.isNotEmpty()) {
                        manager.setCookie(domain, "$name=$value; Domain=${parts[0]}; Path=${parts[2]}")
                        count++
                    }
                }
            }
            manager.flush()
            Log.i(TAG, "Injected $count cookies into WebView")
        } catch (e: Exception) {
            Log.w(TAG, "Cookie injection failed: ${e.message}")
        }
    }

    private fun parseJsonArray(json: String): List<String> = runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            InstagramMediaParser.normalizeUrl(array.optString(index, ""))
        }
    }.getOrDefault(emptyList())

    private fun extractCookieValue(cookies: String, name: String): String {
        val pattern = Regex("""(?:^|;\s*)${Regex.escape(name)}=([^;]+)""")
        return pattern.find(cookies)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun loadCookiesForDomain(domain: String): String {
        return try {
            val content = AppCookieManager(context).load() ?: return ""
            val pairs = mutableListOf<String>()
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 7) {
                    val cookieDomain = parts[0].lowercase(Locale.ROOT)
                    if (cookieDomain.endsWith(domain) || cookieDomain == ".$domain") {
                        val name = parts[5]
                        val value = parts.getOrElse(6) { "" }
                        if (name.isNotEmpty()) pairs.add("$name=$value")
                    }
                }
            }
            pairs.joinToString("; ")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cookies: ${e.message}")
            ""
        }
    }

    fun canExtract(url: String): Boolean =
        MediaUrlNormalizer.hostMatches(url, "instagram.com") &&
            !url.contains("/reel/", ignoreCase = true)
}
