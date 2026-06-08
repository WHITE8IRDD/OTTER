package com.otter.app.data.repository

import android.content.Context
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.db.AppDatabase
import com.otter.app.data.db.PausedDownload
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadState
import com.otter.app.data.model.VideoInfo
import com.otter.app.engine.Downloader
import com.otter.app.engine.FormatParser
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.downloadDao()
    private val pausedDao = db.pausedDownloadDao()
    val preferences = UserPreferences(context)
    private val formatParser = FormatParser(context)
    private val downloader = Downloader(context, formatParser)

    val allDownloads: Flow<List<DownloadItem>> = dao.getAllDownloads()
    val pausedDownloads: Flow<List<PausedDownload>> = pausedDao.getAll()
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
        subtitleFormat: String = "srt",
        autoCaptionLangs: String = "",
        title: String = ""
    ) {
        downloader.download(url, formatId, outputDir, onProgress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs, title)
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
        subtitleFormat: String = "srt",
        autoCaptionLangs: String = "",
        title: String = ""
    ) {
        downloader.downloadWithThumbnail(url, formatId, outputDir, onProgress, subtitleLangs, embedSubtitles, subtitleFormat, autoCaptionLangs, title)
    }

    suspend fun downloadImages(
        imageUrls: List<String>,
        baseName: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) {
        downloader.downloadImages(imageUrls, baseName, outputDir, onProgress)
    }

    fun pauseDownload(url: String): Downloader.ActiveDownload? {
        return downloader.pauseDownload(url)
    }

    suspend fun savePausedDownload(item: PausedDownload) {
        pausedDao.insert(item)
    }

    suspend fun getPausedByUrl(url: String): PausedDownload? {
        return pausedDao.getByUrl(url)
    }

    suspend fun deletePausedDownload(item: PausedDownload) {
        downloader.cleanupPartialFiles(item.outputDir)
        pausedDao.delete(item)
    }

    suspend fun deletePausedByUrl(url: String) {
        pausedDao.deleteByUrl(url)
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

    fun stopDownload(url: String) {
        downloader.stopDownload(url)
    }

    fun wasIntentionallyCancelled(url: String): Boolean {
        return downloader.wasIntentionallyCancelled(url)
    }

    fun getActiveDownload(url: String): Downloader.ActiveDownload? {
        return downloader.getActiveDownload(url)
    }

    fun cleanupPartialFiles(outputDir: String) {
        downloader.cleanupPartialFiles(outputDir)
    }

    fun detectResolvedFilename(outputDir: String): String? {
        return downloader.detectResolvedFilename(outputDir)
    }

    fun checkPartFileExists(outputDir: String, resolvedFilename: String): Boolean {
        return downloader.checkPartFileExists(outputDir, resolvedFilename)
    }
}
