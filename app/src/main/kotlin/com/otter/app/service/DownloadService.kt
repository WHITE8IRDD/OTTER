package com.otter.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.otter.app.data.db.PausedDownload
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadPhase
import com.otter.app.data.model.DownloadState
import com.otter.app.data.model.DownloadStatus
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.engine.MediaSlide
import com.otter.app.R
import com.otter.app.util.FileHelper
import com.otter.app.util.NotificationHelper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@SuppressLint("MissingPermission")
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DownloadRepository
    private var lastNotificationUpdate = 0L
    private var lastStateUpdate = 0L
    private var thumbnailBitmap: Bitmap? = null

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_START_DOWNLOAD = "com.otter.app.START_DOWNLOAD"
        /** One job of picked gallery files (photos *and* clips) fetched straight from their CDN URLs. */
        const val ACTION_START_MEDIA_BATCH = "com.otter.app.START_MEDIA_BATCH"
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
        /** Parallel lists for [ACTION_START_MEDIA_BATCH]: the files and their kind (0 = picture, 1 = video). */
        const val EXTRA_MEDIA_URLS = "extra_media_urls"
        const val EXTRA_MEDIA_KINDS = "extra_media_kinds"
        // Metadata kept with the history entry so it can be shown and copied later.
        const val EXTRA_UPLOADER = "extra_uploader"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_HASHTAGS = "extra_hashtags"
        const val EXTRA_DURATION = "extra_duration"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
        const val EXTRA_HAS_SUBTITLES = "extra_has_subtitles"
        /** Set when the caller does not want this download recorded in History. */
        const val EXTRA_SKIP_HISTORY = "extra_skip_history"
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val PREFS_ACTIVE_DOWNLOAD = "active_download_service"
        private const val PREF_ACTIVE_URL = "active_url"
        private const val PREF_ACTIVE_ACTION = "active_action"
        private const val PREF_ACTION_SINGLE = "single"
        private const val PREF_ACTION_BATCH = "batch"
        private const val PREF_ACTIVE_MEDIA_URLS = "active_media_urls"
        private const val PREF_ACTIVE_MEDIA_KINDS = "active_media_kinds"
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(applicationContext)
    }

    // Metadata of the download currently being handled (single download at a time).
    private var pendingUploader = ""
    private var pendingThumbnailUrl: String? = null
    private var pendingDescription = ""
    private var pendingHashtags: List<String> = emptyList()
    private var pendingDuration = 0L
    private var pendingFormatId = ""
    private var pendingIsAudioOnly = false
    private var pendingHasSubtitles = false
    private var pendingSkipHistory = false

@SuppressLint("MissingPermission")
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action} url=${intent?.getStringExtra(EXTRA_URL)}")
        if (intent == null) {
            val pending = restorePendingDownloadIntent()
            if (pending == null) {
                Log.i(TAG, "No active download to restore after service restart")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            Log.i(TAG, "Restoring interrupted foreground download from its saved request")
            handleStart(pending)
            return START_STICKY
        }

        return when (intent.action) {
            ACTION_START_DOWNLOAD -> {
                handleStart(intent)
                START_STICKY
            }
            ACTION_START_MEDIA_BATCH -> {
                handleStartMediaBatch(intent)
                START_STICKY
            }
            ACTION_PAUSE_DOWNLOAD -> {
                handlePause(intent)
                START_NOT_STICKY
            }
            ACTION_RESUME_DOWNLOAD -> {
                handleResume(intent)
                START_STICKY
            }
            ACTION_CANCEL_DOWNLOAD -> {
                handleCancel(intent)
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Save enough of an active request to restart it if Android reclaims this process. */
    private fun persistPendingDownload(intent: Intent) {
        val preferences = getSharedPreferences(PREFS_ACTIVE_DOWNLOAD, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        val stringExtras = listOf(
            EXTRA_URL, EXTRA_FORMAT_ID, EXTRA_OUTPUT_DIR, EXTRA_TITLE, EXTRA_THUMBNAIL_URL,
            EXTRA_SUBTITLE_LANGS, EXTRA_AUTO_CAPTION_LANGS, EXTRA_SUBTITLE_FORMAT,
            EXTRA_UPLOADER, EXTRA_DESCRIPTION, EXTRA_HASHTAGS
        )
        stringExtras.forEach { key ->
            if (intent.hasExtra(key)) editor.putString(key, intent.getStringExtra(key))
            else editor.remove(key)
        }
        listOf(
            EXTRA_SAVE_THUMBNAIL, EXTRA_EMBED_SUBTITLES, EXTRA_IS_AUDIO_ONLY,
            EXTRA_HAS_SUBTITLES, EXTRA_SKIP_HISTORY
        ).forEach { key -> editor.putBoolean(key, intent.getBooleanExtra(key, false)) }
        editor.putLong(EXTRA_DURATION, intent.getLongExtra(EXTRA_DURATION, 0L))
        editor.putString(PREF_ACTIVE_URL, intent.getStringExtra(EXTRA_URL))
        // A batch carries its files as parallel lists; one line each survives a
        // process death (URLs and 0/1 markers never contain a newline).
        editor.putString(
            PREF_ACTIVE_MEDIA_URLS,
            intent.getStringArrayListExtra(EXTRA_MEDIA_URLS)?.joinToString("\n")
        )
        editor.putString(
            PREF_ACTIVE_MEDIA_KINDS,
            intent.getIntegerArrayListExtra(EXTRA_MEDIA_KINDS)?.joinToString("\n")
        )
        editor.putString(
            PREF_ACTIVE_ACTION,
            if (intent.action == ACTION_START_MEDIA_BATCH) PREF_ACTION_BATCH else PREF_ACTION_SINGLE
        )
        // The small request record is committed before yt-dlp starts. If Android
        // kills the process, --continue can resume the partial file on service restart.
        editor.commit()
    }

    private fun restorePendingDownloadIntent(): Intent? {
        val preferences = getSharedPreferences(PREFS_ACTIVE_DOWNLOAD, Context.MODE_PRIVATE)
        val url = preferences.getString(PREF_ACTIVE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val isBatch = preferences.getString(PREF_ACTIVE_ACTION, PREF_ACTION_SINGLE) == PREF_ACTION_BATCH
        val batchUrls = preferences.getString(PREF_ACTIVE_MEDIA_URLS, null)
            ?.split('\n')?.filter { it.isNotBlank() }
        if (isBatch && batchUrls.isNullOrEmpty()) return null
        return Intent(this, DownloadService::class.java).apply {
            action = if (isBatch) ACTION_START_MEDIA_BATCH else ACTION_START_DOWNLOAD
            listOf(
                EXTRA_URL, EXTRA_FORMAT_ID, EXTRA_OUTPUT_DIR, EXTRA_TITLE, EXTRA_THUMBNAIL_URL,
                EXTRA_SUBTITLE_LANGS, EXTRA_AUTO_CAPTION_LANGS, EXTRA_SUBTITLE_FORMAT,
                EXTRA_UPLOADER, EXTRA_DESCRIPTION, EXTRA_HASHTAGS
            ).forEach { key ->
                if (preferences.contains(key)) {
                    preferences.getString(key, null)?.let { putExtra(key, it) }
                }
            }
            listOf(
                EXTRA_SAVE_THUMBNAIL, EXTRA_EMBED_SUBTITLES, EXTRA_IS_AUDIO_ONLY,
                EXTRA_HAS_SUBTITLES, EXTRA_SKIP_HISTORY
            ).forEach { key -> putExtra(key, preferences.getBoolean(key, false)) }
            putExtra(EXTRA_DURATION, preferences.getLong(EXTRA_DURATION, 0L))
            putExtra(EXTRA_URL, url)
            if (isBatch) {
                putStringArrayListExtra(EXTRA_MEDIA_URLS, ArrayList(batchUrls.orEmpty()))
                val kinds = preferences.getString(PREF_ACTIVE_MEDIA_KINDS, null)
                    ?.split('\n')
                    ?.map { it.trim().toIntOrNull() ?: 0 }
                    .orEmpty()
                // Kinds are parallel to the URLs; anything missing reads as a picture.
                putIntegerArrayListExtra(
                    EXTRA_MEDIA_KINDS,
                    ArrayList(List(batchUrls!!.size) { index -> kinds.getOrElse(index) { 0 } })
                )
            }
        }
    }

    private fun clearPendingDownload(url: String? = null) {
        val preferences = getSharedPreferences(PREFS_ACTIVE_DOWNLOAD, Context.MODE_PRIVATE)
        if (url != null && preferences.getString(PREF_ACTIVE_URL, null) != url) return
        preferences.edit().clear().commit()
    }

    // ─── START ────────────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        persistPendingDownload(intent)
        val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: "best"
        var outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: ""
        if (outputDir.isBlank()) {
            val savedPath = runBlocking(Dispatchers.IO) { repository.downloadPath.first() }
            outputDir = if (savedPath.isNotBlank()) savedPath
                else "${getExternalFilesDir(null)?.parentFile?.parentFile?.absolutePath}/Download/Otter/"
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.default_video_title)
        val thumbUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)
        val saveThumbnail = intent.getBooleanExtra(EXTRA_SAVE_THUMBNAIL, false)
        val subtitleLangs = intent.getStringExtra(EXTRA_SUBTITLE_LANGS) ?: ""
        val autoCaptionLangs = intent.getStringExtra(EXTRA_AUTO_CAPTION_LANGS) ?: ""
        val embedSubtitles = intent.getBooleanExtra(EXTRA_EMBED_SUBTITLES, false)
        val subtitleFormat = intent.getStringExtra(EXTRA_SUBTITLE_FORMAT) ?: "srt"

        // History metadata: description and hashtags travel with the download so the
        // finished entry can show (and copy) them.
        pendingUploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()
        pendingThumbnailUrl = thumbUrl
        pendingDescription = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        pendingHashtags = intent.getStringExtra(EXTRA_HASHTAGS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        pendingDuration = intent.getLongExtra(EXTRA_DURATION, 0L)
        pendingFormatId = formatId
        pendingIsAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        pendingHasSubtitles = intent.getBooleanExtra(EXTRA_HAS_SUBTITLES, false)
        pendingSkipHistory = intent.getBooleanExtra(EXTRA_SKIP_HISTORY, false)

        if (!thumbUrl.isNullOrEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try { thumbnailBitmap = loadThumbnail(thumbUrl) } catch (e: Throwable) { Log.w(TAG, "START: thumbnail load failed", e) }
            }
        }
        Log.i(TAG, "[START-1] DOWNLOAD START url=$url formatId=$formatId outputDir=$outputDir title=$title")

        try {
            val initialNotification = NotificationHelper.buildProgressNotification(
                this, title, DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING), url = url
            )
            startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, initialNotification)
            DownloadStateHolder.update(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
            ActiveDownloadStateHolder.updateState(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING), title, url)
        } catch (e: Throwable) {
            Log.e(TAG, "[START-2] startForeground failed", e)
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
                            Log.e(TAG, "[START-3] download function threw for $u", e)
                            when {
                                repository.wasIntentionallyCancelled(u) -> {
                                    Log.i(TAG, "[START-3b] suppressed — intentional cancel")
                                }
                                e is CancellationException -> {
                                    Log.w(TAG, "[START-3c] CancellationException from non-user source")
                                    progress(DownloadState.Error("Download interrupted unexpectedly. Tap Retry to try again."))
                                }
                                else -> {
                                    progress(DownloadState.Error(e.message ?: "Download failed"))
                                }
                            }
                        }
                    }

                downloadFn(url, formatId) { state ->
                    if (!handleCommonState(state, url, title, fromResume = false)) return@downloadFn
                }
                Log.i(TAG, "[START-4] download completed (possibly cancelled)")
            } catch (e: CancellationException) {
                Log.i(TAG, "[START-5] job cancelled (service stopping)")
            } catch (e: Throwable) {
                Log.e(TAG, "[START-6] unexpected error", e)
            } finally {
                clearPendingDownload(url)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ActiveDownloadStateHolder.clear()
            }
        }
    }

    // ─── MEDIA BATCH (quick-download gallery picks) ───────────────────────────

    /**
     * One job of picked gallery files — photos and clips together — fetched straight
     * from the signed CDN URLs the post handed out. This is what the Quick Download
     * window sends: the window closes back into Instagram while this service keeps
     * the files coming and records one History row for the post.
     */
    private fun handleStartMediaBatch(intent: Intent) {
        val sourceUrl = intent.getStringExtra(EXTRA_URL) ?: return
        val mediaUrls = intent.getStringArrayListExtra(EXTRA_MEDIA_URLS)
            .orEmpty().filter { it.isNotBlank() }
        val kinds = intent.getIntegerArrayListExtra(EXTRA_MEDIA_KINDS).orEmpty()
        if (mediaUrls.isEmpty()) {
            Log.w(TAG, "[BATCH-1] no media URLs in batch request")
            stopSelf()
            return
        }
        persistPendingDownload(intent)
        var outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: ""
        if (outputDir.isBlank()) {
            val savedPath = runBlocking(Dispatchers.IO) { repository.downloadPath.first() }
            outputDir = if (savedPath.isNotBlank()) savedPath
            else "${getExternalFilesDir(null)?.parentFile?.parentFile?.absolutePath}/Download/Otter/"
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.default_video_title)
        val thumbUrl = intent.getStringExtra(EXTRA_THUMBNAIL_URL)

        pendingUploader = intent.getStringExtra(EXTRA_UPLOADER).orEmpty()
        pendingThumbnailUrl = thumbUrl
        pendingDescription = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        pendingHashtags = intent.getStringExtra(EXTRA_HASHTAGS)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        pendingDuration = intent.getLongExtra(EXTRA_DURATION, 0L)
        pendingFormatId = ""
        pendingIsAudioOnly = false
        pendingHasSubtitles = false
        pendingSkipHistory = intent.getBooleanExtra(EXTRA_SKIP_HISTORY, false)

        if (!thumbUrl.isNullOrEmpty()) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    thumbnailBitmap = loadThumbnail(thumbUrl)
                } catch (e: Throwable) {
                    Log.w(TAG, "[BATCH] thumbnail load failed", e)
                }
            }
        }
        Log.i(TAG, "[BATCH-2] MEDIA BATCH START files=${mediaUrls.size} outputDir=$outputDir title=$title")

        try {
            val initialNotification = NotificationHelper.buildProgressNotification(
                this, title, DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING), url = sourceUrl
            )
            startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, initialNotification)
            DownloadStateHolder.update(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
            ActiveDownloadStateHolder.updateState(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING), title, sourceUrl)
        } catch (e: Throwable) {
            Log.e(TAG, "[BATCH-3] startForeground failed", e)
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val slides = mediaUrls.mapIndexed { index, url ->
                    MediaSlide(url = url, isVideo = kinds.getOrElse(index) { 0 } != 0)
                }
                val summary = repository.downloadMedia(
                    slides = slides,
                    baseName = title,
                    outputDir = outputDir,
                    onProgress = { state ->
                        // Success/Error are folded into the summary this call returns;
                        // forwarding them here would record a History row for a
                        // newline-joined path list instead of one finished post.
                        if (state is DownloadState.Downloading) {
                            handleCommonState(state, sourceUrl, title, fromResume = false)
                        }
                    },
                    refererUrl = sourceUrl
                )
                if (summary.savedCount > 0) {
                    val firstPath = summary.savedPaths.first()
                    Log.i(
                        TAG,
                        "[BATCH-4] saved ${summary.savedCount}/${slides.size} file(s)" +
                            if (summary.failedCount > 0) " (${summary.failedCount} failed: ${summary.firstFailure})" else ""
                    )
                    val finalState = DownloadState.Success(firstPath)
                    DownloadStateHolder.update(finalState)
                    ActiveDownloadStateHolder.updateState(finalState, title, sourceUrl)
                    try {
                        NotificationHelper.showCompleteNotification(
                            this@DownloadService, title, firstPath, thumbnailBitmap
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "[BATCH-4b] complete notification threw", e)
                    }
                    if (!pendingSkipHistory) {
                        try {
                            val record = buildBatchRecord(sourceUrl, title, summary.savedPaths)
                            runBlocking(Dispatchers.IO) { repository.saveDownloadItem(record) }
                            Log.i(TAG, "[BATCH-4c] history record saved for the batch")
                        } catch (e: Throwable) {
                            Log.e(TAG, "[BATCH-4d] failed to save history record", e)
                        }
                    }
                } else {
                    val message = summary.firstFailure.ifBlank { getString(R.string.failed_all_images) }
                    Log.w(TAG, "[BATCH-5] no files saved: $message")
                    val finalState = DownloadState.Error(message)
                    DownloadStateHolder.update(finalState)
                    ActiveDownloadStateHolder.updateState(finalState, title, sourceUrl)
                    try {
                        NotificationHelper.showErrorNotification(this@DownloadService, title, message)
                    } catch (e: Throwable) {
                        Log.e(TAG, "[BATCH-5b] error notification threw", e)
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "[BATCH-6] job cancelled (service stopping)")
            } catch (e: Throwable) {
                Log.e(TAG, "[BATCH-7] unexpected error", e)
                try {
                    NotificationHelper.showErrorNotification(
                        this@DownloadService, title, e.message ?: "Download failed"
                    )
                } catch (ignored: Throwable) {
                }
            } finally {
                clearPendingDownload(sourceUrl)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ActiveDownloadStateHolder.clear()
            }
        }
    }

    /** One History row for a finished gallery batch: the post, sized by its files together. */
    private fun buildBatchRecord(sourceUrl: String, title: String, savedPaths: List<String>): DownloadItem {
        val firstPath = savedPaths.first()
        val totalSize = savedPaths.sumOf { path ->
            runCatching { FileHelper.getFileSizeBytes(path) }.getOrDefault(0L)
        }
        val extension = firstPath.substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
        return DownloadItem(
            url = sourceUrl,
            title = title,
            uploader = pendingUploader,
            thumbnailUrl = pendingThumbnailUrl ?: firstPath,
            filePath = firstPath,
            fileSize = totalSize,
            duration = pendingDuration,
            formatId = "",
            ext = extension,
            isAudioOnly = false,
            description = pendingDescription,
            hashtags = pendingHashtags,
            status = DownloadStatus.COMPLETED.name
        )
    }

    // ─── PAUSE ────────────────────────────────────────────────────────────────

    private fun handlePause(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            Log.e(TAG, "handlePause: missing URL")
            return
        }
        Log.i(TAG, "[PAUSE-1] PAUSE TAPPED: url=$url")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val activeInfo = repository.pauseDownload(url)
                if (activeInfo == null) {
                    Log.w(TAG, "[PAUSE-2] no active download for $url")
                    return@launch
                }
                Log.i(TAG, "[PAUSE-3] Snapshot captured: progress=${activeInfo.lastProgress} bytes=${activeInfo.lastDownloadedBytes}/${activeInfo.lastTotalBytes}")

                // Save to DB
                try {
                    val pausedItem = PausedDownload(
                        url = url, title = activeInfo.title, uploader = activeInfo.uploader,
                        thumbnailUrl = activeInfo.thumbnailUrl, formatId = activeInfo.formatId,
                        outputDir = activeInfo.outputDir, progress = activeInfo.lastProgress,
                        downloadedBytes = activeInfo.lastDownloadedBytes, totalBytes = activeInfo.lastTotalBytes
                    )
                    repository.savePausedDownload(pausedItem)
                    clearPendingDownload(url)
                    Log.i(TAG, "[PAUSE-4] PausedDownload saved to DB")
                } catch (e: Throwable) {
                    Log.e(TAG, "[PAUSE-5] DB save failed", e)
                }

                // Cancel any stale error notification
                NotificationHelper.cancelErrorNotification(this@DownloadService)

                // Update in-app state
                try {
                    val pausedState = DownloadState.Paused(
                        progress = activeInfo.lastProgress, downloadedBytes = activeInfo.lastDownloadedBytes,
                        totalBytes = activeInfo.lastTotalBytes, url = url, formatId = activeInfo.formatId,
                        outputDir = activeInfo.outputDir, title = activeInfo.title
                    )
                    DownloadStateHolder.update(pausedState)
                    ActiveDownloadStateHolder.updateState(pausedState, activeInfo.title, url)
                    Log.i(TAG, "[PAUSE-6] in-app state updated to Paused")
                } catch (e: Throwable) {
                    Log.e(TAG, "[PAUSE-7] state update failed", e)
                }

                // Update notification to paused
                try {
                    val pausedNotification = NotificationHelper.buildPausedNotification(
                        this@DownloadService, activeInfo.title,
                        DownloadState.Paused(progress = activeInfo.lastProgress, downloadedBytes = activeInfo.lastDownloadedBytes,
                            totalBytes = activeInfo.lastTotalBytes, url = url, formatId = activeInfo.formatId,
                            outputDir = activeInfo.outputDir, title = activeInfo.title)
                    )
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, pausedNotification)
                    Log.i(TAG, "[PAUSE-8] notification rebuilt as PAUSED with Resume+Stop buttons")
                } catch (e: Throwable) {
                    Log.e(TAG, "[PAUSE-9] notification update failed", e)
                }

                // Detach from foreground
                try {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    Log.i(TAG, "[PAUSE-10] foreground detached")
                } catch (e: Throwable) {
                    Log.w(TAG, "[PAUSE-11] stopForeground threw", e)
                }
                Log.i(TAG, "[PAUSE-12] PAUSE COMPLETE — no errors")
            } catch (e: Throwable) {
                Log.e(TAG, "[PAUSE-13] unexpected error", e)
            }
        }
    }

    // ─── RESUME ───────────────────────────────────────────────────────────────

    private fun handleResume(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            Log.e(TAG, "handleResume: missing URL")
            return
        }
        Log.i(TAG, "[RESUME-1] RESUME TAPPED: source=Notification url=$url")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val pausedItem = repository.getPausedByUrl(url)
                if (pausedItem == null) {
                    Log.w(TAG, "[RESUME-2] no paused entry for $url")
                    showSoftResumeError(url, null)
                    return@launch
                }
                Log.i(TAG, "[RESUME-3] Fetched paused record: progress=${pausedItem.progress} bytes=${pausedItem.downloadedBytes}/${pausedItem.totalBytes}")
                // Treat a resumed download like a durable start request before removing
                // its paused DB row. A process restart then retries with yt-dlp --continue.
                persistPendingDownload(Intent(this@DownloadService, DownloadService::class.java).apply {
                    action = ACTION_START_DOWNLOAD
                    putExtra(EXTRA_URL, pausedItem.url)
                    putExtra(EXTRA_FORMAT_ID, pausedItem.formatId)
                    putExtra(EXTRA_OUTPUT_DIR, pausedItem.outputDir)
                    putExtra(EXTRA_TITLE, pausedItem.title)
                    putExtra(EXTRA_THUMBNAIL_URL, pausedItem.thumbnailUrl)
                    putExtra(EXTRA_UPLOADER, pausedItem.uploader)
                    putExtra(EXTRA_SUBTITLE_LANGS, pausedItem.subtitleLangs)
                    putExtra(EXTRA_AUTO_CAPTION_LANGS, pausedItem.autoCaptionLangs)
                    putExtra(EXTRA_EMBED_SUBTITLES, pausedItem.embedSubtitles)
                    putExtra(EXTRA_SUBTITLE_FORMAT, pausedItem.subtitleFormat)
                    putExtra(EXTRA_HAS_SUBTITLES, pausedItem.hasSubtitles)
                })

                // Cancel stale error notification
                NotificationHelper.cancelErrorNotification(this@DownloadService)

                // Show "Resuming..." notification
                try {
                    val resumingNotif = NotificationHelper.buildResumingNotification(
                        this@DownloadService, pausedItem.title, thumbnailBitmap
                    )
                    startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, resumingNotif)
                    Log.i(TAG, "[RESUME-4] notification updated to 'Resuming...'")
                } catch (e: Throwable) {
                    Log.e(TAG, "[RESUME-5] startForeground failed", e)
                    return@launch
                }

                // Delete paused entry
                try {
                    repository.deletePausedByUrl(url)
                    Log.i(TAG, "[RESUME-6] paused entry deleted from DB")
                } catch (e: Throwable) {
                    Log.e(TAG, "[RESUME-7] delete paused entry failed", e)
                }

                // Load thumbnail
                try {
                    thumbnailBitmap = pausedItem.thumbnailUrl?.let { loadThumbnail(it) }
                } catch (e: Throwable) {
                    Log.w(TAG, "[RESUME-8] thumbnail load failed", e)
                }

                // Set initial progress to paused value (prevents 0% flash)
                val initialBytes = pausedItem.downloadedBytes
                val initialTotal = pausedItem.totalBytes
                val initialProgress = pausedItem.progress
                val resumingState = DownloadState.Downloading(
                    progress = initialProgress, downloadedBytes = initialBytes,
                    totalBytes = initialTotal, phase = DownloadPhase.PREPARING
                )
                DownloadStateHolder.update(resumingState)
                ActiveDownloadStateHolder.updateState(resumingState, pausedItem.title, url)
                Log.i(TAG, "[RESUME-9] initial state set to paused progress=$initialProgress")

                // Start the download
                Log.i(TAG, "[RESUME-10] calling repository.startDownload with --continue")
                repository.startDownload(
                    url = pausedItem.url, formatId = pausedItem.formatId,
                    outputDir = pausedItem.outputDir,
                    onProgress = { state ->
                        if (!handleCommonState(state, url, pausedItem.title, fromResume = true, pausedProgress = pausedItem.progress, pausedBytes = pausedItem.downloadedBytes, pausedTotal = pausedItem.totalBytes)) return@startDownload
                    },
                    title = pausedItem.title
                )
                Log.i(TAG, "[RESUME-11] startDownload returned")
            } catch (e: CancellationException) {
                if (!repository.wasIntentionallyCancelled(url)) {
                    Log.w(TAG, "[RESUME-12a] CancellationException from non-user source during resume")
                    showSoftResumeError(url, "Download interrupted unexpectedly. Tap Retry to try again.")
                } else {
                    Log.i(TAG, "[RESUME-12b] suppressed CancellationException — intentional cancel")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[RESUME-13] unexpected error", e)
                showSoftResumeError(url, e.message)
            } finally {
                clearPendingDownload(url)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ActiveDownloadStateHolder.clear()
            }
        }
    }

    // ─── CANCEL (STOP) ────────────────────────────────────────────────────────

    private fun handleCancel(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        Log.i(TAG, "[STOP-1] STOP TAPPED: url=$url")

        // Capture state BEFORE killing (from intent extras, paused entry, or active download)
        var progress = 0f
        var downloaded = 0L
        var total = 0L
        var title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.default_video_title)
        var formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: ""
        var uploader = ""
        var thumbnailUrl: String? = null
        var outputDir = ""

        // Get paused entry info before we kill anything
        val pausedItem = runCatching { runBlocking(Dispatchers.IO) { repository.getPausedByUrl(url) } }.getOrNull()
        if (pausedItem != null) {
            progress = pausedItem.progress
            downloaded = pausedItem.downloadedBytes
            total = pausedItem.totalBytes
            title = pausedItem.title
            formatId = pausedItem.formatId
            uploader = pausedItem.uploader
            thumbnailUrl = pausedItem.thumbnailUrl
            outputDir = pausedItem.outputDir
        } else {
            // Try active download info as fallback
            val activeInfo = repository.getActiveDownload(url)
            if (activeInfo != null) {
                progress = activeInfo.lastProgress
                downloaded = activeInfo.lastDownloadedBytes
                total = activeInfo.lastTotalBytes
                title = activeInfo.title
                formatId = activeInfo.formatId
                uploader = activeInfo.uploader
                thumbnailUrl = activeInfo.thumbnailUrl
                outputDir = activeInfo.outputDir
            }
        }

        // Detect resolved filename from .part files in output dir
        val resolvedFilename = if (outputDir.isNotBlank()) {
            runCatching { repository.detectResolvedFilename(outputDir) }.getOrNull() ?: ""
        } else ""

        // Kill the process (preserves .part files for potential resume)
        try {
            repository.cancelDownload(url)
            Log.i(TAG, "[STOP-2] yt-dlp process killed (.part files preserved)")
        } catch (e: Throwable) {
            Log.e(TAG, "[STOP-3] stopDownload threw", e)
        }

        // Check if .part file actually exists (allows resume later)
        val partFileExists = repository.checkPartFileExists(outputDir, resolvedFilename)
        Log.i(TAG, "[STOP-4] resolvedFilename=$resolvedFilename outputDir=$outputDir canResume=$partFileExists")

        // Remove paused entry from DB
        if (pausedItem != null) {
            try {
                runBlocking(Dispatchers.IO) { repository.deletePausedDownload(pausedItem) }
                Log.i(TAG, "[STOP-5] paused entry deleted")
            } catch (e: Throwable) {
                Log.e(TAG, "[STOP-5b] paused cleanup failed", e)
            }
        }

        // Save stopped record to History with full resume metadata
        try {
            val stoppedRecord = DownloadItem(
                url = url, title = title, uploader = uploader, thumbnailUrl = thumbnailUrl,
                filePath = "", fileSize = 0, duration = 0, formatId = formatId, ext = "",
                isAudioOnly = false, status = DownloadStatus.STOPPED.name,
                progressAtStop = progress, downloadedBytesAtStop = downloaded, totalBytesAtStop = total,
                outputDir = outputDir, resolvedFilename = resolvedFilename, canResume = partFileExists
            )
            runBlocking(Dispatchers.IO) { repository.saveDownloadItem(stoppedRecord) }
            Log.i(TAG, "[STOP-6] stopped record saved to History at ${(progress * 100).toInt()}% canResume=$partFileExists")
        } catch (e: Throwable) {
            Log.e(TAG, "[STOP-7] failed to save stopped record", e)
        }

        // Update state
        try {
            DownloadStateHolder.update(DownloadState.Idle)
            ActiveDownloadStateHolder.clear()
        } catch (e: Throwable) {
            Log.e(TAG, "[STOP-8] state update failed", e)
        }

        // Cancel all notifications and stop (now safe — DB ops complete)
        try {
            NotificationHelper.cancelErrorNotification(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.e(TAG, "[STOP-9] cleanup failed", e)
        }
        clearPendingDownload(url)
        Log.i(TAG, "[STOP-10] STOP COMPLETE")
        stopSelf()
    }

    // ─── SHARED STATE HANDLER ─────────────────────────────────────────────────

    /** Returns true if the caller should continue (allow more callbacks), false to throttle. */
    private fun handleCommonState(
        state: DownloadState,
        url: String,
        title: String,
        fromResume: Boolean,
        pausedProgress: Float = 0f,
        pausedBytes: Long = 0L,
        pausedTotal: Long = 0L
    ): Boolean {
        val now = System.currentTimeMillis()

        // Throttle downloading state updates (200ms)
        if (state is DownloadState.Downloading && now - lastStateUpdate < 200L) return false
        if (state is DownloadState.Downloading) lastStateUpdate = now

        when (state) {
            is DownloadState.Downloading -> {
                // On resume, don't let progress drop below paused value
                val adjustedState = if (fromResume && state.progress < pausedProgress) {
                    Log.d(TAG, "[COMMON] clamping progress from ${state.progress} up to paused $pausedProgress")
                    state.copy(
                        progress = maxOf(state.progress, pausedProgress),
                        downloadedBytes = maxOf(state.downloadedBytes, pausedBytes),
                        totalBytes = maxOf(state.totalBytes, pausedTotal)
                    )
                } else state

                try {
                    DownloadStateHolder.update(adjustedState)
                    ActiveDownloadStateHolder.updateState(adjustedState, title, url)
                } catch (e: Throwable) {
                    Log.w(TAG, "[COMMON] state update threw", e)
                }

                if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS) return false
                lastNotificationUpdate = now
                try {
                    val notification = NotificationHelper.buildProgressNotification(
                        this@DownloadService, title, adjustedState, thumbnailBitmap, url = url
                    )
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                } catch (e: Throwable) {
                    Log.w(TAG, "[COMMON] notification update threw", e)
                }
            }

            is DownloadState.Paused -> {
                NotificationHelper.cancelErrorNotification(this)
                try {
                    val notification = NotificationHelper.buildPausedNotification(
                        this@DownloadService, title, state, thumbnailBitmap
                    )
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
                } catch (e: Throwable) {
                    Log.w(TAG, "[COMMON] paused notification update threw", e)
                }
                try {
                    DownloadStateHolder.update(state)
                    ActiveDownloadStateHolder.updateState(state, title, url)
                } catch (e: Throwable) {
                    Log.w(TAG, "[COMMON] paused state update threw", e)
                }
            }

            is DownloadState.Success -> {
                Log.i(TAG, "[COMMON] download SUCCESS for $url")
                DownloadStateHolder.update(state)
                ActiveDownloadStateHolder.updateState(state, title, url)
                try {
                    NotificationHelper.showCompleteNotification(
                        this@DownloadService, title, state.filePath, thumbnailBitmap
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "[COMMON] complete notification threw", e)
                }
                // The finished file is recorded here (unless the caller opted out) so
                // every path — in-app, retry, resume, quick download — has history.
                if (!pendingSkipHistory) {
                    try {
                        val record = buildCompletedRecord(url, title, state.filePath)
                        runBlocking(Dispatchers.IO) { repository.saveDownloadItem(record) }
                        Log.i(TAG, "[COMMON] history record saved for $url")
                    } catch (e: Throwable) {
                        Log.e(TAG, "[COMMON] failed to save history record", e)
                    }
                } else {
                    Log.i(TAG, "[COMMON] history record skipped for $url")
                }
            }

            is DownloadState.Error -> {
                Log.w(TAG, "[COMMON] download ERROR for $url: ${state.message}")
                // Check if this was an intentional cancellation
                if (repository.wasIntentionallyCancelled(url)) {
                    Log.i(TAG, "[COMMON] suppressing error notification — intentional cancel")
                    DownloadStateHolder.update(DownloadState.Idle)
                    ActiveDownloadStateHolder.clear()
                    return false
                }
                DownloadStateHolder.update(state)
                ActiveDownloadStateHolder.updateState(state, title, url)
                try {
                    NotificationHelper.showErrorNotification(
                        this@DownloadService, title, state.message
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "[COMMON] error notification threw", e)
                }
            }

            else -> {} // Idle, FetchingInfo, InfoReady, etc — no-op
        }
        return true
    }

    // ─── HELPERS ──────────────────────────────────────────────────────────────

    /** Builds the History row for a finished download, metadata included. */
    private fun buildCompletedRecord(url: String, title: String, filePath: String): DownloadItem {
        val extension = filePath.substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
        val size = runCatching { FileHelper.getFileSizeBytes(filePath) }.getOrDefault(0L)
        return DownloadItem(
            url = url,
            title = title,
            uploader = pendingUploader,
            thumbnailUrl = pendingThumbnailUrl,
            filePath = filePath,
            fileSize = size,
            duration = pendingDuration,
            formatId = pendingFormatId,
            ext = extension,
            isAudioOnly = pendingIsAudioOnly,
            hasSubtitles = pendingHasSubtitles,
            description = pendingDescription,
            hashtags = pendingHashtags,
            status = DownloadStatus.COMPLETED.name
        )
    }

    private fun showSoftResumeError(url: String?, message: String?) {
        try {
            NotificationHelper.cancelErrorNotification(this)
            val notif = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.resume_error_title))
                .setContentText(message ?: getString(R.string.resume_error_unknown))
                .setOngoing(false)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notif)
        } catch (e: Throwable) {
            Log.e(TAG, "showSoftResumeError failed", e)
        }
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
