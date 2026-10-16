package com.otter.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fetches the public TikTok page payload used by [TikTokPhotoParser]. No cookies are read or sent. */
class TikTokPhotoScraper {
    companion object {
        private const val TAG = "TikTokPhotoScraper"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 7_000
        private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    suspend fun scrape(url: String): TikTokPhotoParser.Result? = withContext(Dispatchers.IO) {
        val html = fetchPublicPage(url) ?: return@withContext null
        TikTokPhotoParser.parseHtml(html, url)
    }

    private fun fetchPublicPage(url: String): String? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrElse {
                Log.w(TAG, "Invalid TikTok page URL", it)
                return null
            }
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.setRequestProperty("Referer", "https://www.tiktok.com/")
            connection.setRequestProperty("Cache-Control", "no-cache")

            val status = connection.responseCode
            if (status !in 200..299) {
                Log.i(TAG, "TikTok page returned HTTP $status")
                return null
            }

            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var remaining = MAX_PAGE_BYTES
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            Log.w(TAG, "Public TikTok page fetch failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
