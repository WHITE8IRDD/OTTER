package com.otter.app.ui.screens.history

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.data.db.PausedDownload
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadStatus
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.service.DownloadService
import com.otter.app.util.FileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(application)
    private val tag = "HistoryVM"

    val downloads: StateFlow<List<DownloadItem>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pausedDownloads: StateFlow<List<PausedDownload>> = repository.pausedDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        revalidateStoppedDownloads()
    }

    private fun revalidateStoppedDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val allItems = repository.allDownloads.let { flow ->
                var items = emptyList<DownloadItem>()
                flow.collect { items = it; return@collect }
                items
            }
            val stoppedItems = allItems.filter {
                runCatching { DownloadStatus.valueOf(it.status) }.getOrDefault(DownloadStatus.COMPLETED) == DownloadStatus.STOPPED
            }
            for (item in stoppedItems) {
                if (item.canResume && item.outputDir.isNotBlank() && item.resolvedFilename.isNotBlank()) {
                    val partExists = repository.checkPartFileExists(item.outputDir, item.resolvedFilename)
                    if (partExists != item.canResume) {
                        Log.i(tag, "[REVALIDATE] ${item.title}: canResume updated to $partExists")
                    }
                }
            }
            Log.i(tag, "[REVALIDATE] Checked ${stoppedItems.size} stopped items")
        }
    }

    fun deleteItem(item: DownloadItem) {
        viewModelScope.launch {
            // Clean up .part files when user explicitly removes from History
            if (item.status == DownloadStatus.STOPPED.name || item.status == DownloadStatus.FAILED.name) {
                cleanupPartialFiles(item)
            }
            repository.deleteDownloadItem(item)
        }
    }

    fun deleteItemWithFile(item: DownloadItem) {
        viewModelScope.launch {
            if (item.status == DownloadStatus.STOPPED.name || item.status == DownloadStatus.FAILED.name) {
                cleanupPartialFiles(item)
            }
            FileHelper.deleteFile(item.filePath)
            repository.deleteDownloadItem(item)
        }
    }

    private fun cleanupPartialFiles(item: DownloadItem) {
        if (item.outputDir.isNotBlank() && item.resolvedFilename.isNotBlank()) {
            Log.i(tag, "[CLEANUP] Deleting .part files for ${item.resolvedFilename}")
            val partFile = File(item.outputDir, "${item.resolvedFilename}.part")
            val ytdlFile = File(item.outputDir, "${item.resolvedFilename}.ytdl")
            if (partFile.exists()) partFile.delete()
            if (ytdlFile.exists()) ytdlFile.delete()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllDownloads()
        }
    }

    fun openFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.openFile(context, item.filePath)
    }

    fun locateFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.locateFile(context, item.filePath)
    }

    fun shareFile(context: android.content.Context, item: DownloadItem) {
        FileHelper.shareFile(context, item.filePath)
    }

    fun fileExists(item: DownloadItem): Boolean {
        return FileHelper.fileExists(item.filePath)
    }

    fun resumePausedDownload(item: PausedDownload) {
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_RESUME_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, item.url)
        }
        getApplication<Application>().startService(intent)
    }

    fun deletePausedDownload(item: PausedDownload) {
        viewModelScope.launch {
            repository.deletePausedDownload(item)
        }
    }

    fun stopPausedDownload(item: PausedDownload) {
        Log.i(tag, "STOP TAPPED: source=History url=${item.url}")
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, item.url)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_FORMAT_ID, item.formatId)
        }
        getApplication<Application>().startService(intent)
    }

    fun resumeStoppedDownload(item: DownloadItem) {
        Log.i(tag, "[HISTORY-RESUME] Resume from stopped download: ${item.title}")
        viewModelScope.launch(Dispatchers.IO) {
            // Create a PausedDownload from the DownloadItem so existing resume flow works
            val pausedItem = PausedDownload(
                url = item.url,
                title = item.title,
                uploader = item.uploader,
                thumbnailUrl = item.thumbnailUrl,
                formatId = item.formatId,
                outputDir = item.outputDir,
                progress = item.progressAtStop,
                downloadedBytes = item.downloadedBytesAtStop,
                totalBytes = item.totalBytesAtStop,
                pausedAt = item.downloadedAt
            )
            // Save as paused entry so service can find it
            repository.savePausedDownload(pausedItem)
            Log.i(tag, "[HISTORY-RESUME] PausedDownload saved from stopped record, progress=${item.progressAtStop}")

            // Remove stopped record from History (will reappear as Completed when done)
            repository.deleteDownloadItem(item)
            Log.i(tag, "[HISTORY-RESUME] Stopped record removed from history")

            // Trigger service to resume
            val intent = Intent(getApplication(), DownloadService::class.java).apply {
                action = DownloadService.ACTION_RESUME_DOWNLOAD
                putExtra(DownloadService.EXTRA_URL, item.url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplication<Application>().startForegroundService(intent)
            } else {
                getApplication<Application>().startService(intent)
            }
            Log.i(tag, "[HISTORY-RESUME] Resume intent dispatched to service")
        }
    }

    fun retryDownload(item: DownloadItem) {
        Log.i(tag, "[RETRY] starting fresh download for ${item.title}")
        // First check: is this a stopped/failed download that needs .part cleanup?
        if (item.status == DownloadStatus.STOPPED.name || item.status == DownloadStatus.FAILED.name) {
            cleanupPartialFiles(item)
            Log.i(tag, "[RETRY] Cleaned up .part files before fresh download")
        }
        viewModelScope.launch {
            repository.deleteDownloadItem(item)
        }
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, item.url)
            putExtra(DownloadService.EXTRA_FORMAT_ID, item.formatId)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, item.thumbnailUrl)
            putExtra(DownloadService.EXTRA_OUTPUT_DIR, item.outputDir)
            putExtra(DownloadService.EXTRA_SAVE_THUMBNAIL, false)
            putExtra(DownloadService.EXTRA_SUBTITLE_LANGS, "")
            putExtra(DownloadService.EXTRA_AUTO_CAPTION_LANGS, "")
            putExtra(DownloadService.EXTRA_EMBED_SUBTITLES, false)
            putExtra(DownloadService.EXTRA_SUBTITLE_FORMAT, "srt")
            // Keep the metadata the original entry had.
            putExtra(DownloadService.EXTRA_UPLOADER, item.uploader)
            putExtra(DownloadService.EXTRA_DESCRIPTION, item.description)
            putExtra(DownloadService.EXTRA_HASHTAGS, item.hashtags.joinToString(","))
            putExtra(DownloadService.EXTRA_DURATION, item.duration)
            putExtra(DownloadService.EXTRA_IS_AUDIO_ONLY, item.isAudioOnly)
            putExtra(DownloadService.EXTRA_HAS_SUBTITLES, item.hasSubtitles)
        }
        getApplication<Application>().startForegroundService(intent)
        Log.i(tag, "[RETRY] Fresh download intent dispatched")
    }
}
