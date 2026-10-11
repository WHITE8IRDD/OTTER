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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

        const val DESKTOP_UA = InstagramPostFetcher.DESKTOP_UA
        const val MOBILE_UA = InstagramPostFetcher.MOBILE_UA
        const val IG_APP_ID = InstagramPostFetcher.IG_APP_ID

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
        val cookies = loadCookiesForDomain("instagram.com")
        val fetched = InstagramPostFetcher().fetch(postUrl, cookies)

        val merged = LinkedHashMap<String, String>()
        fetched.imageUrls.forEach { url -> merged[InstagramMediaParser.mediaKey(url)] = url }
        val sources = fetched.sources.toMutableList()
        var expected = fetched.expectedCount

        // WebView rendering stays as the last resort for pages whose payload the
        // static endpoints did not expose (and only when an Activity is available).
        val needsWebView = merged.isEmpty() ||
            (expected > 0 && merged.size < expected) ||
            (expected == 0 && merged.size < 2)
        if (needsWebView && context is Activity) {
            val canonical = if (fetched.shortcode.isNotEmpty()) {
                "https://www.instagram.com/p/${fetched.shortcode}/"
            } else {
                postUrl
            }
            val rendered = scrapeWithWebView(canonical)
            if (rendered.isNotEmpty()) {
                rendered.forEach { url -> merged.putIfAbsent(InstagramMediaParser.mediaKey(url), url) }
                sources += "webview(${rendered.size})"
                if (expected < rendered.size) expected = rendered.size
            } else {
                sources += "webview(0)"
            }
        } else if (needsWebView) {
            sources += "webview(no-activity)"
        }

        val urls = merged.values.toList()
        if (urls.isEmpty()) {
            Log.w(TAG, "No Instagram images for ${fetched.shortcode.ifEmpty { postUrl }} " +
                "(${sources.joinToString()} ${fetched.httpStatuses.joinToString()})")
        } else {
            Log.i(TAG, "Instagram images: ${urls.size} of ${if (expected > 0) expected else "?"} " +
                "via ${sources.joinToString()}")
            urls.forEachIndexed { index, url -> Log.i(TAG, "  [$index] ${url.take(160)}") }
        }
        ScrapeResult(
            imageUrls = urls,
            expectedCount = if (expected > 0) expected else urls.size,
            shortcode = fetched.shortcode,
            sources = sources,
            note = fetched.note
        )
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
