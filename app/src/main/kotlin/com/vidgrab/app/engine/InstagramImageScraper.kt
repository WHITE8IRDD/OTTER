package com.vidgrab.app.engine

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
import com.vidgrab.app.data.cookies.CookieManager as AppCookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class InstagramImageScraper(private val context: Context) {

    companion object {
        private const val TAG = "InstaScraper"
        private const val TIMEOUT_MS = 25_000L
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val JS_EXTRACTOR = """
(function(){
    var SEEN={}, MAX=10, MS=1500, C=0;

    function collect(){
        var r=[];
        document.querySelectorAll('img[src*="scontent"], img[src*="cdninstagram"]').forEach(function(i){
            var u=i.src.split('?')[0];
            if(u&&!SEEN[u]){SEEN[u]=true;r.push(u);}
        });
        document.querySelectorAll('[style*="background-image"],[style*="url("]').forEach(function(el){
            var m=el.style.backgroundImage||'';
            var u=m.replace(/url\(["']?/,'').replace(/["']?\)/,'');
            if(u.indexOf('scontent')>=0&&!SEEN[u]){SEEN[u]=true;r.push(u);}
        });
        // Try all computed background images (broader)
        document.querySelectorAll('*').forEach(function(el){
            var bg=window.getComputedStyle(el).backgroundImage;
            if(bg&&bg!=='none'&&bg.indexOf('url')>=0){
                var u=bg.replace(/url\(["']?/,'').replace(/["']?\)/,'').split('?')[0];
                if(u.indexOf('scontent')>=0&&!SEEN[u]){SEEN[u]=true;r.push(u);}
            }
        });
        // Try JSON scripts for display_url
        document.querySelectorAll('script[type="application/json"]').forEach(function(sc){
            try{
                var d=JSON.parse(sc.textContent);
                (function _(o){
                    if(!o||typeof o!=='object')return;
                    if(o.display_url&&typeof o.display_url==='string'&&o.display_url.indexOf('scontent')>=0&&!SEEN[o.display_url.split('?')[0]]){SEEN[o.display_url.split('?')[0]]=true;r.push(o.display_url.split('?')[0]);}
                    if(o.display_src&&typeof o.display_src==='string'&&o.display_src.indexOf('scontent')>=0&&!SEEN[o.display_src.split('?')[0]]){SEEN[o.display_src.split('?')[0]]=true;r.push(o.display_src.split('?')[0]);}
                    if(Array.isArray(o))o.forEach(_);else for(var k in o)_(o[k]);
                })(d);
            }catch(e){}
        });
        // Try og:image
        var og=document.querySelector('meta[property="og:image"]');
        if(og){var u=og.content.split('?')[0];if(u.indexOf('scontent')>=0&&!SEEN[u]){SEEN[u]=true;r.push(u);}}
        return r;
    }

    function nextBtn(){
        var a=document.querySelector('button[aria-label="Next"],button[aria-label*="next"],button[aria-label*="Next"]');
        if(a&&a.offsetParent!==null)return a;
        var s=document.querySelector('svg[aria-label*="Next"],svg[aria-label*="next"]');
        if(s){var p=s.closest('button')||s.parentElement;if(p&&p.offsetParent!==null)return p;}
        return null;
    }

    function click(b){
        ['pointerdown','pointerup','mousedown','mouseup','click'].forEach(function(t){
            b.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));
        });
        try{b.click()}catch(e){}
    }

    function tick(){
        C++;var added=collect();
        Android.log(C+'/'+MAX+': +'+added.length+' tot='+Object.keys(SEEN).length);
        if(C>=MAX)return done();
        var n=nextBtn();
        if(n){Android.log('->next');click(n);setTimeout(tick,MS);}
        else if(C===1){window.scrollTo(0,document.body.scrollHeight);setTimeout(function(){window.scrollTo(0,0);collect();done();},2000);}
        else done();
    }

    function done(){window.scrollTo(0,0);collect();Android.onResult(JSON.stringify(Object.keys(SEEN)));}

    setTimeout(function(){Android.log('start:'+Object.keys(SEEN).length);tick();},2000);
})();
"""
    }

    suspend fun scrape(url: String): List<String> = withContext(Dispatchers.IO) {
        val cleanUrl = url.split("?").first().trimEnd('/')
        Log.i(TAG, "Scraping Instagram post: $cleanUrl")

        val html = fetchHtml(cleanUrl)
        if (html != null) {
            val htmlImages = extractImagesFromHtml(html)
            if (htmlImages.size >= 2) {
                Log.i(TAG, "HTML path: ${htmlImages.size} images")
                htmlImages.forEachIndexed { i, img -> Log.i(TAG, "  [$i] $img") }
                return@withContext htmlImages
            }
            Log.i(TAG, "HTML path: ${htmlImages.size} image(s), trying API")
        }

        val apiImages = fetchFromApi(cleanUrl)
        if (apiImages.isNotEmpty()) {
            Log.i(TAG, "API path: ${apiImages.size} images")
            apiImages.forEachIndexed { i, img -> Log.i(TAG, "  [$i] $img") }
            return@withContext apiImages
        }
        Log.i(TAG, "API path: ${apiImages.size} image(s), trying ?__a=1 JSON")

        val aOneImages = fetchFromA1(cleanUrl)
        if (aOneImages.isNotEmpty()) {
            Log.i(TAG, "?__a=1 path: ${aOneImages.size} images")
            aOneImages.forEachIndexed { i, img -> Log.i(TAG, "  [$i] $img") }
            return@withContext aOneImages
        }
        Log.i(TAG, "?__a=1 path: ${aOneImages.size} image(s), trying embed page")

        val embedImages = fetchFromEmbed(cleanUrl)
        if (embedImages.isNotEmpty()) {
            Log.i(TAG, "Embed path: ${embedImages.size} images")
            embedImages.forEachIndexed { i, img -> Log.i(TAG, "  [$i] $img") }
            return@withContext embedImages
        }
        Log.i(TAG, "Embed path: ${embedImages.size} image(s), trying WebView")

        scrapeWithWebView(cleanUrl)
    }

    private suspend fun fetchHtml(url: String): String? {
        val cookies = loadCookiesForDomain("instagram.com")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.apply {
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                setRequestProperty("Cookie", cookies)
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val responseCode = connection.responseCode
            Log.i(TAG, "HTTP response: $responseCode")
            if (responseCode != 200) return null
            return BufferedReader(InputStreamReader(connection.inputStream)).readText()
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed: ${e.message}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractImagesFromHtml(html: String): List<String> {
        val images = mutableSetOf<String>()
        val sharedDataPattern = """window\._sharedData\s*=\s*(\{.*?\});</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        sharedDataPattern.find(html)?.let { match ->
            try {
                val root = JSONObject(match.groupValues[1])
                val postPage = root.optJSONObject("entry_data")
                    ?.optJSONArray("PostPage")?.optJSONObject(0)
                    ?.optJSONObject("graphql")?.optJSONObject("shortcode_media")
                if (postPage != null) {
                    val mainUrl = postPage.optString("display_url", "")
                    if (mainUrl.contains("scontent")) images.add(cleanUrl(mainUrl))
                    val children = postPage.optJSONObject("edge_sidecar_to_children")
                        ?.optJSONArray("edges")
                    if (children != null) {
                        for (i in 0 until children.length()) {
                            val url = children.optJSONObject(i)
                                ?.optJSONObject("node")
                                ?.optString("display_url", "")
                            if (!url.isNullOrEmpty() && url.contains("scontent")) images.add(cleanUrl(url))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "extractImagesFromHtml: sharedData parse failed", e)
            }
        }
        if (images.size < 2) {
            val scontentPattern = """https?://scontent[^"'\s<>]+\.(?:jpg|jpeg|png|webp)""".toRegex(RegexOption.IGNORE_CASE)
            scontentPattern.findAll(html).forEach { match ->
                val url = cleanUrl(match.value)
                if (url.contains("scontent") && !url.contains("/rsrc.php") && !url.contains("/v/t51.2885-19/") && !url.contains("profile_pic")) {
                    images.add(url)
                }
            }
        }
        if (images.size < 2) {
            val metaPattern = """<meta\s+property="og:image"\s+content="([^"]+)"""".toRegex()
            metaPattern.find(html)?.let {
                val url = cleanUrl(it.groupValues[1])
                if (url.contains("scontent")) images.add(url)
            }
        }
        return images.toList().distinct()
    }

    private fun extractShortcode(url: String): String {
        val m = """instagram\.com/p/([^/?]+)""".toRegex().find(url)
        return m?.groupValues?.getOrNull(1) ?: ""
    }

    /** Decode a base64-url shortcode to Instagram's numeric media ID. */
    private fun shortcodeToMediaId(shortcode: String): Long {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (c in shortcode) {
            id = id * 64 + alphabet.indexOf(c)
        }
        return id
    }

    private fun extractCookieValue(cookies: String, name: String): String {
        val pattern = """(?:^|;\s*)$name=([^;]+)""".toRegex()
        return pattern.find(cookies)?.groupValues?.getOrNull(1) ?: ""
    }

    private suspend fun fetchFromApi(url: String): List<String> {
        val shortcode = extractShortcode(url)
        if (shortcode.isEmpty()) return emptyList()
        val mediaId = shortcodeToMediaId(shortcode)
        Log.i(TAG, "Fetching API: shortcode=$shortcode mediaId=$mediaId")

        val cookies = loadCookiesForDomain("instagram.com")
        val csrf = extractCookieValue(cookies, "csrftoken")
        Log.i(TAG, "Cookie len=${cookies.length}, csrf=${csrf.take(10)}...")
        val apiUrl = "https://i.instagram.com/api/v1/media/$mediaId/info/"
        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            connection.apply {
                setRequestProperty("User-Agent", "Instagram 100.0.0.19.120 Android (28/9; 420dpi; 1080x2400; Xiaomi; Mi 11; msm_8998; en_US)")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US")
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty("X-CSRFToken", csrf)
                setRequestProperty("Referer", "https://www.instagram.com/")
                setRequestProperty("Cookie", cookies)
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = connection.responseCode
            Log.i(TAG, "API response: $code")
            if (code != 200) {
                val err = try { BufferedReader(InputStreamReader(connection.errorStream)).readText().take(500) } catch (_: Exception) { "" }
                Log.w(TAG, "API error body (first 500): $err")
                return emptyList()
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
            Log.i(TAG, "API raw (first 400): ${body.take(400)}")
            return parseApiResponse(body)
        } catch (e: Exception) {
            Log.w(TAG, "API call failed: ${e.message}")
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** Try the public `?__a=1` JSON endpoint (returns carousel data without auth in some cases). */
    private suspend fun fetchFromA1(url: String): List<String> {
        val cookies = loadCookiesForDomain("instagram.com")
        val csrf = extractCookieValue(cookies, "csrftoken")
        val a1Url = "$url/?__a=1&__d=dis"
        Log.i(TAG, "Trying ?__a=1: $a1Url")
        val connection = URL(a1Url).openConnection() as HttpURLConnection
        return try {
            connection.apply {
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("X-IG-App-ID", "936619743392459")
                setRequestProperty("X-CSRFToken", csrf)
                setRequestProperty("Referer", "https://www.instagram.com/")
                setRequestProperty("Cookie", cookies)
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = connection.responseCode
            Log.i(TAG, "?__a=1 response: $code")
            if (code != 200) {
                Log.w(TAG, "?__a=1 returned HTTP $code, skipping")
                return emptyList()
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
            Log.i(TAG, "?__a=1 raw (first 300): ${body.take(300)}")
            parseGraphqlResponse(body)
        } catch (e: Exception) {
            Log.w(TAG, "?__a=1 failed: ${e.message}")
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** Try the embed page — it's lighter weight and often renders without login. */
    private suspend fun fetchFromEmbed(url: String): List<String> {
        val shortcode = extractShortcode(url)
        if (shortcode.isEmpty()) return emptyList()
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/"
        Log.i(TAG, "Trying embed: $embedUrl")
        val cookies = loadCookiesForDomain("instagram.com")
        val connection = URL(embedUrl).openConnection() as HttpURLConnection
        return try {
            connection.apply {
                setRequestProperty("User-Agent", MOBILE_UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.5")
                setRequestProperty("Cookie", cookies)
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val code = connection.responseCode
            Log.i(TAG, "Embed response: $code")
            if (code != 200) return emptyList()
            val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
            extractImagesFromHtml(body)
        } catch (e: Exception) {
            Log.w(TAG, "Embed fetch failed: ${e.message}")
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** Parse `?__a=1` GraphQL-style response (uses shortcode_media layout). */
    private fun parseGraphqlResponse(json: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    extractMediaUrls(items.optJSONObject(i) ?: continue, urls)
                }
            } else {
                val media = root.optJSONObject("graphql")
                    ?.optJSONObject("shortcode_media")
                    ?: root.optJSONObject("shortcode_media")
                if (media != null) {
                    val children = media.optJSONObject("edge_sidecar_to_children")
                        ?.optJSONArray("edges")
                    if (children != null) {
                        for (i in 0 until children.length()) {
                            val node = children.optJSONObject(i)?.optJSONObject("node")
                            if (node != null) {
                                val u = node.optString("display_url", "")
                                if (u.isNotEmpty() && u.contains("scontent")) urls.add(cleanUrl(u))
                            }
                        }
                    } else {
                        val u = media.optString("display_url", "")
                        if (u.isNotEmpty() && u.contains("scontent")) urls.add(cleanUrl(u))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "?__a=1 parse failed: ${e.message}")
        }
        Log.i(TAG, "?__a=1 parsed ${urls.size} URLs: ${urls.take(5)}")
        return urls.filter { it.contains("scontent") && !it.contains("/rsrc.php") }
    }

    private fun parseApiResponse(json: String): List<String> {
        val urls = mutableListOf<String>()
        try {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: return emptyList()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                extractMediaUrls(item, urls)
            }
        } catch (e: Exception) {
            Log.w(TAG, "API parse failed: ${e.message}")
        }
        Log.i(TAG, "API parsed ${urls.size} URLs: ${urls.take(5)}")
        return urls.filter { isValidImageUrl(it) }
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val u = url.lowercase()
        if (u.contains("/rsrc.php") || u.contains("/v/t51.2885-19/") || u.contains("profile_pic")) return false
        if (!(u.contains("scontent") || u.contains("fbcdn.net") || u.contains("cdninstagram"))) return false
        if (!(u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") || u.endsWith(".webp") || u.contains(".jpg?") || u.contains(".webp?"))) return false
        return true
    }

    private fun extractMediaUrls(media: JSONObject, out: MutableList<String>) {
        when (media.optInt("media_type", -1)) {
            8 -> { // carousel container
                val arr = media.optJSONArray("carousel_media") ?: return
                for (i in 0 until arr.length()) {
                    val child = arr.optJSONObject(i) ?: continue
                    val url = bestMediaUrl(child)
                    if (url != null) out.add(url)
                }
            }
            else -> { // single image (1) or video (2)
                val url = bestMediaUrl(media)
                if (url != null) out.add(url)
            }
        }
    }

    private fun bestMediaUrl(node: JSONObject): String? {
        // Video slide → highest-res video_versions
        val vv = node.optJSONArray("video_versions")
        if (vv != null && vv.length() > 0) {
            val best = pickBestByDimension(vv) ?: vv.optJSONObject(0)
            return best?.optString("url", "")?.let { cleanUrl(it) }
        }
        // Image → image_versions2.candidates
        val iv = node.optJSONObject("image_versions2") ?: return null
        val candidates = iv.optJSONArray("candidates") ?: return null
        val best = pickBestByDimension(candidates) ?: return null
        return cleanUrl(best.optString("url", ""))
    }

    private fun pickBestByDimension(arr: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val w = obj.optInt("width", 0)
            // Prefer highest-resolution candidate
            if (w > bestScore) {
                bestScore = w
                best = obj
            }
        }
        return best
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithWebView(url: String): List<String> = withContext(Dispatchers.Main) {
        val shortcode = extractShortcode(url)
        val targetUrl = if (shortcode.isNotEmpty()) "https://www.instagram.com/p/$shortcode/embed/" else url
        Log.i(TAG, "Starting WebView scrape")
        var webView: WebView? = null

        val result = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine<List<String>> { cont ->

                    val activity = context as? Activity
                    val container = if (activity != null) {
                        FrameLayout(context).apply {
                            layoutParams = FrameLayout.LayoutParams(1080, 4000)
                            setBackgroundColor(Color.TRANSPARENT)
                            visibility = View.VISIBLE
                            val decorView = activity.window.decorView as? ViewGroup
                            decorView?.addView(this)
                            Log.i(TAG, "Container attached to decorView (1080x4000)")
                        }
                    } else {
                        Log.w(TAG, "No Activity context - WebView may not render on MIUI")
                        null
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
                        layout(0, 0, 1080, 1920)
                        measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                        alpha = 0.01f

                        CookieManager.getInstance().setAcceptCookie(true)
                        injectWebViewCookies()

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onResult(json: String) {
                                Log.i(TAG, "JS bridge received data")
                                if (cont.isActive) {
                                    cont.resume(parseJsonArray(json))
                                }
                            }

                            @JavascriptInterface
                            fun log(msg: String) {
                                Log.i(TAG, "JS: $msg")
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
                                Log.i(TAG, "Page started: $pageUrl")
                            }
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                Log.i(TAG, "Page finished, injecting JS (polling will wait for render)")
                                evaluateJavascript(JS_EXTRACTOR, android.webkit.ValueCallback { value ->
                                    Log.i(TAG, "JS eval result: $value")
                                })
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                Log.w(TAG, "WebView error: ${error?.description}")
                            }
                        }
                    }

                    container?.addView(webView)

                    cont.invokeOnCancellation {
                        Log.i(TAG, "Scrape cancelled")
                    }

                    webView!!.loadUrl(targetUrl)
                }
            } ?: emptyList()
        } finally {
            webView?.let { wv ->
                wv.stopLoading()
                wv.removeJavascriptInterface("Android")
                wv.destroy()
            }
            val container = webView?.parent as? ViewGroup
            val wrapper = container?.parent as? ViewGroup
            wrapper?.removeView(container)
            container?.removeView(webView)
            Log.i(TAG, "WebView cleaned up")
        }
        Log.i(TAG, "WebView scrape: ${result.size} images")
        result.forEachIndexed { i, img -> Log.i(TAG, "  [$i] $img") }
        result
    }

    private fun injectWebViewCookies() {
        try {
            val appCookieManager = AppCookieManager(context)
            val content = appCookieManager.load() ?: return
            val lines = content.lines()
            val cm = CookieManager.getInstance()
            val domain = "https://www.instagram.com"
            var count = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 7) {
                    val name = parts[5]
                    val value = parts.getOrElse(6) { "" }
                    if (name.isNotEmpty()) {
                        cm.setCookie(domain, "$name=$value; Domain=${parts[0]}; Path=${parts[2]}")
                        count++
                    }
                }
            }
            cm.flush()
            Log.i(TAG, "Injected $count cookies")
        } catch (e: Exception) {
            Log.w(TAG, "Cookie injection failed: ${e.message}")
        }
    }

    private fun parseJsonArray(json: String): List<String> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList()).filter { it.contains("scontent") && !it.contains("/rsrc.php") && !it.contains("/v/t51.2885-19/") }

    private fun loadCookiesForDomain(domain: String): String {
        try {
            val appCookieManager = AppCookieManager(context)
            val content = appCookieManager.load() ?: return ""
            val lines = content.lines()
            val pairs = mutableListOf<String>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) continue
                val parts = trimmed.split("\t")
                if (parts.size >= 7 && (parts[0].endsWith(domain) || parts[0] == ".$domain")) {
                    val name = parts[5]
                    val value = parts.getOrElse(6) { "" }
                    if (name.isNotEmpty()) pairs.add("$name=$value")
                }
            }
            return pairs.joinToString("; ")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cookies: ${e.message}")
            return ""
        }
    }

    private fun cleanUrl(url: String): String {
        return url.replace("\\u0026", "&").replace("\\/", "/").replace("\\\\", "").trim().split("?")[0]
    }

    fun canExtract(url: String): Boolean {
        return url.contains("instagram.com", ignoreCase = true) && !url.contains("/reel/", ignoreCase = true)
    }
}
