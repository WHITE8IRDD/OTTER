package com.otter.app.util

import android.content.Context
import coil.ImageLoader
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Instagram's CDN answers 403 to requests that do not look like a browser on
 * instagram.com. Coil is used for the post picker thumbnails, so the shared
 * ImageLoader adds the browser user agent and the instagram.com referer for
 * Instagram/Facebook CDN hosts. Without this the picker showed blank tiles even
 * for perfectly valid, freshly signed image URLs.
 */
object MediaImageHeaders {

    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val INSTAGRAM_REFERER = "https://www.instagram.com/"

    fun needsBrowserHeaders(url: String): Boolean {
        val host = runCatching { java.net.URL(url).host.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return host.endsWith("cdninstagram.com") ||
            host.endsWith("fbcdn.net") ||
            host.endsWith("instagram.com")
    }

    fun refererFor(url: String): String? =
        if (needsBrowserHeaders(url)) INSTAGRAM_REFERER else null

    fun buildImageLoader(context: Context): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                if (!needsBrowserHeaders(request.url.toString())) {
                    return@Interceptor chain.proceed(request)
                }
                val builder = request.newBuilder()
                if (request.header("User-Agent") == null) builder.header("User-Agent", BROWSER_UA)
                if (request.header("Referer") == null) builder.header("Referer", INSTAGRAM_REFERER)
                chain.proceed(builder.build())
            })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
