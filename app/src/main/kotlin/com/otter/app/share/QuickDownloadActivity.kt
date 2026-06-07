package com.otter.app.share

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.otter.app.MainActivity
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.service.DownloadService
import com.otter.app.util.extractUrlFromText
import com.otter.app.util.isValidUrl
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuickDownloadActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickDownloadActivity"
        private const val MAX_INIT_RETRIES = 10
        private const val INIT_RETRY_DELAY_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private suspend fun ensureEngineReady() = withContext(Dispatchers.IO) {
        for (i in 1..MAX_INIT_RETRIES) {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                Log.i(TAG, "Engine ready after attempt $i")
                return@withContext
            } catch (e: YoutubeDLException) {
                if (i < MAX_INIT_RETRIES) {
                    Log.w(TAG, "Engine not ready (attempt $i), retrying...")
                    delay(INIT_RETRY_DELAY_MS)
                } else {
                    throw e
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)

        if (sharedUrl != null && sharedUrl.isValidUrl()) {
            Log.i(TAG, "QuickDownload received URL: $sharedUrl")
            Log.i(TAG, "URL is TikTok? ${isTikTokUrl(sharedUrl)}")

            // TikTok redirect: Quick Download doesn't work reliably for TikTok,
            // so redirect to the normal share flow with format sheet
            if (isTikTokUrl(sharedUrl)) {
                Log.i(TAG, "Redirecting TikTok URL to normal flow via MainActivity")
                Toast.makeText(this, "Quick Download not available for TikTok — opening normal flow", Toast.LENGTH_SHORT).show()
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ShareActivity.EXTRA_SHARED_URL, sharedUrl)
                }
                startActivity(mainIntent)
                finish()
                return
            }

            Log.i(TAG, "Non-TikTok URL, proceeding with Quick Download")
            scope.launch {
                try {
                    ensureEngineReady()
                    val outputDir = withContext(Dispatchers.IO) {
                        DownloadRepository(applicationContext).downloadPath.first()
                    }
                    val prefs = com.otter.app.data.datastore.UserPreferences(applicationContext)
                    val dlSubtitles = withContext(Dispatchers.IO) { prefs.getDownloadSubtitlesOnce() }
                    val subLang = withContext(Dispatchers.IO) { if (dlSubtitles) prefs.getSubtitleLangOnce() else "" }

                    val serviceIntent = Intent(this@QuickDownloadActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_URL, sharedUrl)
                        putExtra(DownloadService.EXTRA_FORMAT_ID, "bestvideo+bestaudio/best")
                        putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir)
                        putExtra(DownloadService.EXTRA_TITLE, "Quick Download")
                        putExtra(DownloadService.EXTRA_THUMBNAIL_URL, "")
                        putExtra(DownloadService.EXTRA_SAVE_THUMBNAIL, false)
                        putExtra(DownloadService.EXTRA_SUBTITLE_LANGS, subLang)
                        putExtra(DownloadService.EXTRA_AUTO_CAPTION_LANGS, "")
                        putExtra(DownloadService.EXTRA_EMBED_SUBTITLES, dlSubtitles)
                        putExtra(DownloadService.EXTRA_SUBTITLE_FORMAT, "srt")
                    }
                    Log.i(TAG, "QuickDownload starting service...")
                    startForegroundService(serviceIntent)
                    Log.i(TAG, "QuickDownload service started, finishing activity")
                    Toast.makeText(this@QuickDownloadActivity, "Quick download started", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@QuickDownloadActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                finish()
            }
        } else {
            Toast.makeText(this, "No valid URL found", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    extractUrlFromText(text)
                }
            }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }

    private fun extractUrlFromText(text: String): String? {
        return text.extractUrlFromText()
    }

    private fun isTikTokUrl(url: String): Boolean {
        return url.contains("tiktok.com", ignoreCase = true) ||
               url.contains("vm.tiktok.com", ignoreCase = true) ||
               url.contains("vt.tiktok.com", ignoreCase = true)
    }
}
