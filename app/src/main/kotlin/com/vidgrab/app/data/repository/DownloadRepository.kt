package com.vidgrab.app.data.repository

import android.content.Context
import com.vidgrab.app.data.datastore.UserPreferences
import com.vidgrab.app.data.db.AppDatabase
import com.vidgrab.app.data.model.DownloadItem
import com.vidgrab.app.data.model.DownloadState
import com.vidgrab.app.data.model.VideoInfo
import com.vidgrab.app.engine.Downloader
import com.vidgrab.app.engine.FormatParser
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.downloadDao()
    val preferences = UserPreferences(context)
    private val downloader = Downloader(context)
    private val formatParser = FormatParser(context)

    val allDownloads: Flow<List<DownloadItem>> = dao.getAllDownloads()
    val downloadCount: Flow<Int> = dao.getDownloadCount()
    val downloadPath: Flow<String> = preferences.downloadPath
    val defaultQuality: Flow<String> = preferences.defaultQuality
    val autoDownload: Flow<Boolean> = preferences.autoDownload

    suspend fun fetchVideoInfo(
        url: String,
        onRetryMessage: ((String) -> Unit)? = null
    ): Result<VideoInfo> {
        return formatParser.fetchVideoInfo(url, onRetryMessage)
    }

    suspend fun startDownload(
        url: String,
        formatId: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit,
        subtitleLangs: String = "",
        embedSubtitles: Boolean = false,
        subtitleFormat: String = "srt"
    ) {
        downloader.download(url, formatId, outputDir, onProgress, subtitleLangs, embedSubtitles, subtitleFormat)
    }

    suspend fun downloadThumbnailOnly(
        url: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) {
        downloader.downloadThumbnail(url, outputDir, onProgress)
    }

    suspend fun downloadWithThumbnail(
        url: String,
        formatId: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit,
        subtitleLangs: String = "",
        embedSubtitles: Boolean = false,
        subtitleFormat: String = "srt"
    ) {
        downloader.downloadWithThumbnail(url, formatId, outputDir, onProgress, subtitleLangs, embedSubtitles, subtitleFormat)
    }

    suspend fun downloadImages(
        imageUrls: List<String>,
        baseName: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) {
        downloader.downloadImages(imageUrls, baseName, outputDir, onProgress)
    }

    suspend fun saveDownloadItem(item: DownloadItem): Long {
        return dao.insert(item)
    }

    suspend fun deleteDownloadItem(item: DownloadItem) {
        dao.delete(item)
    }

    suspend fun clearAllDownloads() {
        dao.clearAll()
    }

    fun cancelDownload(url: String) {
        downloader.cancelDownload(url)
    }
}
