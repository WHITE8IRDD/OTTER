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
import com.otter.app.data.db.PausedDownload
import com.otter.app.data.model.DownloadPhase
import com.otter.app.data.model.DownloadState
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.R
import com.otter.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DownloadRepository
    private var lastNotificationUpdate = 0L
    private var lastStateUpdate = 0L
    private var thumbnailBitmap: Bitmap? = null

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START_DOWNLOAD = "com.otter.app.START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD = "com.otter.app.PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.otter.app.RESUME_DOWNLOAD"
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
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_START_DOWNLOAD: missing URL, ignoring")
                    return START_NOT_STICKY
                }
                val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "best"
                val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.default_video_title)
                val thumbUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)
                val saveThumbnail = intent.getBooleanExtra(EXTRA_SAVE_THUMBNAIL, false)
                val subtitleLangs = intent.getStringExtra(EXTRA_SUBTITLE_LANGS) ?: ""
                val autoCaptionLangs = intent.getStringExtra(EXTRA_AUTO_CAPTION_LANGS) ?: ""
                val embedSubtitles = intent.getBooleanExtra(EXTRA_EMBED_SUBTITLES, false)
                val subtitleFormat = intent.getStringExtra(EXTRA_SUBTITLE_FORMAT) ?: "srt"

                if (!thumbUrl.isNullOrEmpty()) {
                    serviceScope.launch(Dispatchers.IO) {
                        try { thumbnailBitmap = loadThumbnail(thumbUrl) } catch (e: Throwable) { Log.w(TAG, "thumbnail load failed", e) }
                    }
                }

                Log.i(TAG, "DOWNLOAD START url=$url formatId=$formatId outputDir=$outputDir title=$title")

                try {
                    val initialNotification = NotificationHelper.buildProgressNotification(
                        this, title, DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING), url = url
                    )
                    startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, initialNotification)
                    DownloadStateHolder.update(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
                } catch (e: Throwable) {
                    Log.e(TAG, "ACTION_START_DOWNLOAD: startForeground failed", e)
                }

                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val downloadFn: suspend (String, String, (DownloadState) -> Unit) -> Unit =
                            { u, f, progress ->
                                try {
                                    if (saveThumbnail) {
                                        repository.downloadWithThumbnail(u, f, outputDir, progress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs, title)
                                    } else {
                                        repository.startDownload(u, f, outputDir, progress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs, title)
                                    }
                                } catch (e: Throwable) {
                                    Log.e(TAG, "download function threw for $u", e)
                                    progress(DownloadState.Error(e.message ?: "Download failed"))
                                }
                            }

                        downloadFn(url, formatId) { state ->
                            val now = System.currentTimeMillis()
                            if (state is DownloadState.Downloading && now - lastStateUpdate < 200L) return@downloadFn
                            if (state is DownloadState.Downloading) lastStateUpdate = now
                            try {
                                DownloadStateHolder.update(state)
                            } catch (e: Throwable) {
                                Log.w(TAG, "state update threw", e)
                            }
                            when (state) {
                                is DownloadState.Downloading -> {
                                    if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return@downloadFn
                                    lastNotificationUpdate = now
                                    try {
                                        val notification = NotificationHelper.buildProgressNotification(
                                            this@DownloadService, title, state, thumbnailBitmap, url = url
                                        )
                                        NotificationManagerCompat.from(this@DownloadService)
                                            .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "progress notification update threw", e)
                                    }
                                }
                                is DownloadState.Paused -> {
                                    try {
                                        val notification = NotificationHelper.buildPausedNotification(
                                            this@DownloadService, title, state, thumbnailBitmap
                                        )
                                        NotificationManagerCompat.from(this@DownloadService)
                                            .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "paused notification update threw", e)
                                    }
                                }
                                is DownloadState.Success -> {
                                    try {
                                        NotificationHelper.showCompleteNotification(
                                            this@DownloadService, title, state.filePath, thumbnailBitmap
                                        )
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "complete notification threw", e)
                                    }
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                                is DownloadState.Error -> {
                                    try {
                                        NotificationHelper.showErrorNotification(
                                            this@DownloadService, title, state.message
                                        )
                                    } catch (e: Throwable) {
                                        Log.e(TAG, "error notification threw", e)
                                    }
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "ACTION_START_DOWNLOAD: unexpected error", e)
                    }
                }
            }
            ACTION_PAUSE_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_PAUSE_DOWNLOAD: missing URL, ignoring")
                    return START_NOT_STICKY
                }
                Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: URL=$url")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val activeInfo = repository.pauseDownload(url)
                        if (activeInfo == null) {
                            Log.w(TAG, "ACTION_PAUSE_DOWNLOAD: no active download for $url")
                            return@launch
                        }
                        Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: paused at ${activeInfo.lastProgress * 100}%")
                        // Save to Room database
                        try {
                            val pausedItem = PausedDownload(
                                url = url,
                                title = activeInfo.title,
                                uploader = activeInfo.uploader,
                                thumbnailUrl = activeInfo.thumbnailUrl,
                                formatId = activeInfo.formatId,
                                outputDir = activeInfo.outputDir,
                                progress = activeInfo.lastProgress,
                                downloadedBytes = activeInfo.lastDownloadedBytes,
                                totalBytes = activeInfo.lastTotalBytes
                            )
                            repository.savePausedDownload(pausedItem)
                            Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: saved to DB")
                        } catch (e: Throwable) {
                            Log.e(TAG, "ACTION_PAUSE_DOWNLOAD: DB save failed", e)
                        }
                        // Update in-app state
                        try {
                            val pausedState = DownloadState.Paused(
                                progress = activeInfo.lastProgress,
                                downloadedBytes = activeInfo.lastDownloadedBytes,
                                totalBytes = activeInfo.lastTotalBytes,
                                url = url,
                                formatId = activeInfo.formatId,
                                outputDir = activeInfo.outputDir,
                                title = activeInfo.title
                            )
                            DownloadStateHolder.update(pausedState)
                            Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: state updated")
                        } catch (e: Throwable) {
                            Log.e(TAG, "ACTION_PAUSE_DOWNLOAD: state update failed", e)
                        }
                        // Update notification to paused
                        try {
                            val pausedNotification = NotificationHelper.buildPausedNotification(
                                this@DownloadService,
                                activeInfo.title,
                                DownloadState.Paused(
                                    progress = activeInfo.lastProgress,
                                    downloadedBytes = activeInfo.lastDownloadedBytes,
                                    totalBytes = activeInfo.lastTotalBytes,
                                    url = url,
                                    formatId = activeInfo.formatId,
                                    outputDir = activeInfo.outputDir,
                                    title = activeInfo.title
                                )
                            )
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, pausedNotification)
                            Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: notification updated")
                        } catch (e: Throwable) {
                            Log.e(TAG, "ACTION_PAUSE_DOWNLOAD: notification update failed", e)
                        }
                        // Detach from foreground so the notification becomes a regular paused notification
                        try {
                            stopForeground(STOP_FOREGROUND_DETACH)
                            Log.i(TAG, "ACTION_PAUSE_DOWNLOAD: foreground detached")
                        } catch (e: Throwable) {
                            Log.w(TAG, "ACTION_PAUSE_DOWNLOAD: stopForeground threw", e)
                        }
                        Log.i(TAG, "PAUSE COMPLETE for $url")
                    } catch (e: Throwable) {
                        Log.e(TAG, "ACTION_PAUSE_DOWNLOAD: unexpected error", e)
                    }
                }
            }
            ACTION_RESUME_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_RESUME_DOWNLOAD: missing URL, ignoring")
                    return START_NOT_STICKY
                }
                Log.i(TAG, "ACTION_RESUME_DOWNLOAD: URL=$url")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val pausedItem = repository.getPausedByUrl(url)
                        if (pausedItem == null) {
                            Log.w(TAG, "ACTION_RESUME_DOWNLOAD: no paused entry for $url")
                            return@launch
                        }
                        Log.i(TAG, "ACTION_RESUME_DOWNLOAD: found paused entry at ${pausedItem.progress * 100}%")
                        // Remove the paused entry
                        try {
                            repository.deletePausedByUrl(url)
                            Log.i(TAG, "ACTION_RESUME_DOWNLOAD: paused entry deleted")
                        } catch (e: Throwable) {
                            Log.e(TAG, "ACTION_RESUME_DOWNLOAD: delete paused entry failed", e)
                        }
                        // Load thumbnail on main thread (Coil needs main)
                        try {
                            thumbnailBitmap = pausedItem.thumbnailUrl?.let { loadThumbnail(it) }
                        } catch (e: Throwable) {
                            Log.w(TAG, "ACTION_RESUME_DOWNLOAD: thumbnail load failed", e)
                        }
                        // Show initial notification
                        try {
                            val initialNotification = NotificationHelper.buildProgressNotification(
                                this@DownloadService, pausedItem.title,
                                DownloadState.Downloading(pausedItem.progress, phase = DownloadPhase.PREPARING),
                                url = url
                            )
                            startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, initialNotification)
                            Log.i(TAG, "ACTION_RESUME_DOWNLOAD: foreground started")
                        } catch (e: Throwable) {
                            Log.e(TAG, "ACTION_RESUME_DOWNLOAD: startForeground failed", e)
                            return@launch
                        }
                        // Start the download
                        repository.startDownload(
                            url = pausedItem.url,
                            formatId = pausedItem.formatId,
                            outputDir = pausedItem.outputDir,
                            onProgress = { state ->
                                val now = System.currentTimeMillis()
                                if (state is DownloadState.Downloading && now - lastStateUpdate < 200L) return@startDownload
                                if (state is DownloadState.Downloading) lastStateUpdate = now
                                DownloadStateHolder.update(state)
                                when (state) {
                                    is DownloadState.Downloading -> {
                                        if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return@startDownload
                                        lastNotificationUpdate = now
                                        val notification = NotificationHelper.buildProgressNotification(
                                            this@DownloadService, pausedItem.title, state, thumbnailBitmap, url = url
                                        )
                                        NotificationManagerCompat.from(this@DownloadService)
                                            .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                                    }
                                    is DownloadState.Success -> {
                                        try {
                                            NotificationHelper.showCompleteNotification(
                                                this@DownloadService, pausedItem.title, state.filePath, thumbnailBitmap
                                            )
                                        } catch (e: Throwable) {
                                            Log.e(TAG, "ACTION_RESUME_DOWNLOAD: complete notification failed", e)
                                        }
                                        stopForeground(STOP_FOREGROUND_REMOVE)
                                        stopSelf()
                                    }
                                    is DownloadState.Error -> {
                                        try {
                                            NotificationHelper.showErrorNotification(
                                                this@DownloadService, pausedItem.title, state.message
                                            )
                                        } catch (e: Throwable) {
                                            Log.e(TAG, "ACTION_RESUME_DOWNLOAD: error notification failed", e)
                                        }
                                        stopForeground(STOP_FOREGROUND_REMOVE)
                                        stopSelf()
                                    }
                                    else -> {}
                                }
                            },
                            title = pausedItem.title
                        )
                        Log.i(TAG, "ACTION_RESUME_DOWNLOAD: download started for $url")
                    } catch (e: Throwable) {
                        Log.e(TAG, "ACTION_RESUME_DOWNLOAD: unexpected error", e)
                    }
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrBlank()) {
                    Log.e(TAG, "ACTION_CANCEL_DOWNLOAD: missing URL, ignoring")
                    return START_NOT_STICKY
                }
                Log.i(TAG, "ACTION_CANCEL_DOWNLOAD: URL=$url")
                try {
                    repository.stopDownload(url)
                    Log.i(TAG, "ACTION_CANCEL_DOWNLOAD: download stopped")
                } catch (e: Throwable) {
                    Log.e(TAG, "ACTION_CANCEL_DOWNLOAD: stopDownload threw", e)
                }
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        repository.getPausedByUrl(url)?.let { paused ->
                            repository.deletePausedDownload(paused)
                            Log.i(TAG, "ACTION_CANCEL_DOWNLOAD: paused entry deleted with partials")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "ACTION_CANCEL_DOWNLOAD: paused cleanup failed", e)
                    }
                }
                try {
                    DownloadStateHolder.update(DownloadState.Idle)
                } catch (e: Throwable) {
                    Log.e(TAG, "ACTION_CANCEL_DOWNLOAD: state update failed", e)
                }
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Throwable) {
                    Log.e(TAG, "ACTION_CANCEL_DOWNLOAD: stopForeground failed", e)
                }
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
