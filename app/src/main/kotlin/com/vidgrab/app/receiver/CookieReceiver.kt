package com.vidgrab.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.vidgrab.app.data.cookies.CookieManager

class CookieReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_COOKIES) return
        val content = intent.getStringExtra(EXTRA_COOKIES_CONTENT)
        if (content.isNullOrBlank()) return
        val isBase64 = intent.getBooleanExtra(EXTRA_IS_BASE64, false)
        val finalContent = if (isBase64) {
            String(Base64.decode(content, Base64.DEFAULT))
        } else {
            content
        }
        CookieManager(context).save(finalContent)
    }

    companion object {
        const val ACTION_SET_COOKIES = "com.vidgrab.app.SET_COOKIES"
        const val EXTRA_COOKIES_CONTENT = "cookies_content"
        const val EXTRA_IS_BASE64 = "is_base64"
    }
}
