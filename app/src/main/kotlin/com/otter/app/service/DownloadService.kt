package com.otter.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.otter.app.data.model.DownloadState
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DownloadRepository
    private var lastNotificationUpdate = 0L
    private var lastStateUpdate = 0L
    private var thumbnailBitmap: Bitmap? = null

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START_DOWNLOAD = "com.otter.app.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.otter.app.CANCEL_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT_ID = "extra_format_id"
        const val EXTRA_OUTPUT_DIR = "extra_output_dir"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_THUMBNAIL_URL = "extra_thumbnail_url"
        const val EXTRA_SAVE_THUMBNAIL = "extra_save_thumbnail"
        const val EXTRA_SUBTITLE_LANGS = "extra_subtitle_langs"
        const val EXTRA_AUTO_CAPTION_LANGS = "extra_auto_caption_langs"
        const val EXTRA_EMBED_SUBTITLES = "extra_embed_subtitles"
        const val EXTRA_SUBTITLE_FORMAT = "extra_subtitle_format"
        private const val NOTIFICATION_THROTTLE_MS = 500L
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(applicationContext)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "best"
                val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
                val thumbUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)
                val saveThumbnail = intent.getBooleanExtra(EXTRA_SAVE_THUMBNAIL, false)
                val subtitleLangs = intent.getStringExtra(EXTRA_SUBTITLE_LANGS) ?: ""
                val autoCaptionLangs = intent.getStringExtra(EXTRA_AUTO_CAPTION_LANGS) ?: ""
                val embedSubtitles = intent.getBooleanExtra(EXTRA_EMBED_SUBTITLES, false)
                val subtitleFormat = intent.getStringExtra(EXTRA_SUBTITLE_FORMAT) ?: "srt"

                // Load thumbnail in background if available
                if (!thumbUrl.isNullOrEmpty()) {
                    serviceScope.launch {
                        thumbnailBitmap = loadThumbnail(thumbUrl)
                    }
                }

                val initialNotification = NotificationHelper.buildProgressNotification(
                    this, title, 0, "", "", url = url
                )
                startForeground(
                    NotificationHelper.NOTIFICATION_ID_DOWNLOAD,
                    initialNotification
                )
                DownloadStateHolder.update(DownloadState.Downloading(0f, "", ""))

                Log.i(TAG, "DownloadService START url=$url formatId=$formatId outputDir=$outputDir")
                serviceScope.launch {
                    withContext(Dispatchers.IO) {
                        val downloadFn: suspend (String, String, (DownloadState) -> Unit) -> Unit =
                            { u, f, progress ->
                                if (saveThumbnail) {
                                    repository.downloadWithThumbnail(u, f, outputDir, progress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs)
                                } else {
                                    repository.startDownload(u, f, outputDir, progress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs)
                                }
                            }

                        downloadFn(url, formatId) { state ->
                            val now = System.currentTimeMillis()
                            if (state is DownloadState.Downloading && now - lastStateUpdate < 100L) return@downloadFn
                            if (state is DownloadState.Downloading) lastStateUpdate = now
                            DownloadStateHolder.update(state)
                            when (state) {
                                is DownloadState.Downloading -> {
                                    val now = System.currentTimeMillis()
                                    if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return@downloadFn
                                    lastNotificationUpdate = now

                                    val progressPercent = (state.progress * 100).toInt()
                                    val notification = NotificationHelper.buildProgressNotification(
                                        this@DownloadService,
                                        title,
                                        progressPercent,
                                        state.speedText,
                                        state.etaText,
                                        thumbnailBitmap,
                                        url = url
                                    )
                                    NotificationManagerCompat.from(this@DownloadService)
                                        .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                                }
                                is DownloadState.Success -> {
                                    NotificationHelper.showCompleteNotification(
                                        this@DownloadService, title, state.filePath, thumbnailBitmap
                                    )
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                                is DownloadState.Error -> {
                                    NotificationHelper.showErrorNotification(
                                        this@DownloadService, title, state.message
                                    )
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                repository.cancelDownload(url)
                DownloadStateHolder.update(DownloadState.Idle)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun loadThumbnail(url: String): Bitmap? {
        return try {
            val request = ImageRequest.Builder(this)
                .data(url)
                .allowHardware(false)
                .build()
            val result = Coil.imageLoader(this).execute(request)
            if (result is SuccessResult) {
                (result.drawable as? BitmapDrawable)?.bitmap
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail", e)
            null
        }
    }
}
