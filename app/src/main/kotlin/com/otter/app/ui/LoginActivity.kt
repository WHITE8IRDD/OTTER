package com.otter.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.otter.app.R
import com.otter.app.data.cookies.CookieManager
import com.otter.app.util.CookieHelper
import java.net.URL
import java.net.URLDecoder

class LoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        const val EXTRA_TARGET_URL = "extra_target_url"
        const val EXTRA_PLATFORM = "extra_platform"

        const val PLATFORM_INSTAGRAM = "instagram"
        const val PLATFORM_YOUTUBE = "youtube"
        const val PLATFORM_TIKTOK = "tiktok"
        const val PLATFORM_FACEBOOK = "facebook"

        const val URL_INSTAGRAM = "https://www.instagram.com/accounts/login/"
        const val URL_YOUTUBE = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"
        const val URL_TIKTOK = "https://www.tiktok.com/login"
        const val URL_FACEBOOK = "https://m.facebook.com/login/"
    }

    private lateinit var webView: WebView
    private var captured = false
    private var targetUrl: String = URL_INSTAGRAM

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val passedUrl = intent.getStringExtra(EXTRA_TARGET_URL)
        val passedPlatform = intent.getStringExtra(EXTRA_PLATFORM)

        targetUrl = when {
            !passedUrl.isNullOrBlank() -> passedUrl
            passedPlatform == PLATFORM_YOUTUBE -> URL_YOUTUBE
            passedPlatform == PLATFORM_TIKTOK -> URL_TIKTOK
            passedPlatform == PLATFORM_FACEBOOK -> URL_FACEBOOK
            else -> URL_INSTAGRAM
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.cookies_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val saveButton = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { checkAndCapture(force = true) }
        }

        topBar.addView(titleView)
        topBar.addView(saveButton)
        rootLayout.addView(topBar)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.i(TAG, "Page started: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "Page finished: $url")
                    view?.postDelayed({ checkAndCapture(force = false) }, 1500)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg?.let { Log.i(TAG, "JS[${it.messageLevel()}]: ${it.message()}") }
                    return true
                }
            }

            loadUrl(targetUrl)
        }

        rootLayout.addView(webView)
        setContentView(rootLayout)
    }

    private fun checkAndCapture(force: Boolean) {
        if (captured) return
        val currentUrl = webView.url ?: targetUrl
        val cm = android.webkit.CookieManager.getInstance()
        cm.flush()
        val cookieStr = cm.getCookie(currentUrl) ?: ""
        Log.i(TAG, "Checking cookies for $currentUrl: ${cookieStr.take(120)}")

        val pairs = cookieStr.split(";").map { it.trim() }.filter { it.contains("=") }
        val hasSession = pairs.any { p ->
            val key = p.substringBefore("=").lowercase()
            key in setOf(
                "sessionid", "ds_user_id", // Instagram
                "ttwid", "sid_guard", // TikTok
                "c_user", "xs", // Facebook
                "sapisid", "ssid", "hsid", "login_info", "sid", "__secure-3psid" // YouTube/Google
            )
        }

        if (hasSession || force) {
            saveCapturedCookies(currentUrl, pairs)
            captured = true
            runOnUiThread {
                Toast.makeText(this, getString(R.string.login_cookies_saved), Toast.LENGTH_LONG).show()
            }
            webView.postDelayed({ finish() }, 1000)
        }
    }

    private fun saveCapturedCookies(currentUrl: String, pairs: List<String>) {
        val host = runCatching { URL(currentUrl).host }.getOrDefault("instagram.com")
        val domain = if (host.startsWith(".")) host else ".$host"

        val dbContentResult = CookieHelper.getCookiesContentFromDatabase(this)
        val newLines = mutableListOf<String>()

        if (dbContentResult.isSuccess && dbContentResult.getOrNull()?.isNotBlank() == true) {
            newLines.add(dbContentResult.getOrNull()!!)
        } else {
            newLines.add("# Netscape HTTP Cookie File")
            newLines.add("# Auto-generated by Otter Web Session Capture")
            for (pair in pairs) {
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val name = parts[0].trim()
                    val value = try { URLDecoder.decode(parts[1].trim(), "UTF-8") } catch (_: Exception) { parts[1].trim() }
                    newLines.add("$domain\tTRUE\t/\tTRUE\t2147483647\t$name\t$value")
                }
            }
        }

        val existing = CookieManager(this).load().orEmpty()
        val combined = if (existing.isNotBlank()) {
            "$existing\n${newLines.joinToString("\n")}"
        } else {
            newLines.joinToString("\n")
        }

        CookieManager(this).save(combined)
        CookieHelper.writeCookiesFile(this, combined)
        Log.i(TAG, "Saved session cookies for $domain")
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
