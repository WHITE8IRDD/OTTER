package com.otter.app.engine

import android.content.Context
import android.util.Log
import com.otter.app.R
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.model.DownloadPhase
import com.otter.app.data.model.DownloadState
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class Downloader(private val context: Context) {

    companion object {
        private const val TAG = "Downloader"
        private const val OUTPUT_TEMPLATE_DEFAULT = "%(title).200B [%(id)s].%(ext)s"

        private val progressRegex = Regex(
            """\[download\]\s+(\d+\.?\d*)%\s+of\s+~?\s*(\d+\.?\d*)([KMG]i?B|B)\s+at\s+(\d+\.?\d*)([KMG]i?B|B)/s\s+ETA\s+(\d+):(\d+)(?::(\d+))?"""
        )

        private data class ParsedProgress(
            val percent: Float,
            val totalBytes: Long,
            val speedBytesPerSec: Long,
            val etaSeconds: Long
        )

        private fun parseProgressLine(line: String): ParsedProgress? {
            val m = progressRegex.find(line) ?: return null
            val pct = m.groupValues[1].toFloatOrNull() ?: return null
            val sizeVal = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val sizeUnit = m.groupValues[3]
            val speedVal = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val speedUnit = m.groupValues[5]
            val h = if (m.groupValues[8].isNotEmpty()) m.groupValues[6].toInt() else 0
            val mn = if (m.groupValues[8].isNotEmpty()) m.groupValues[7].toInt() else m.groupValues[6].toInt()
            val s = if (m.groupValues[8].isNotEmpty()) m.groupValues[8].toInt() else m.groupValues[7].toInt()
            return ParsedProgress(
                percent = pct,
                totalBytes = parseSize(sizeVal, sizeUnit),
                speedBytesPerSec = parseSize(speedVal, speedUnit),
                etaSeconds = (h * 3600L + mn * 60L + s)
            )
        }

        private fun parseSize(value: Double, unit: String): Long {
            return when {
                unit.uppercase().startsWith("K") -> (value * 1024).toLong()
                unit.uppercase().startsWith("M") -> (value * 1024 * 1024).toLong()
                unit.uppercase().startsWith("G") -> (value * 1024 * 1024 * 1024).toLong()
                unit.uppercase().startsWith("T") -> (value * 1024L * 1024 * 1024 * 1024).toLong()
                unit.uppercase() == "B" -> value.toLong()
                else -> value.toLong()
            }
        }
    }

    private val preferences = UserPreferences(context)
    private val activeDownloads = ConcurrentHashMap<String, String>()
    private val activeDownloadInfo = ConcurrentHashMap<String, ActiveDownload>()

    data class ActiveDownload(
        val processId: String,
        val url: String,
        val formatId: String,
        val outputDir: String,
        val title: String,
        val uploader: String = "",
        val thumbnailUrl: String? = null,
        var lastProgress: Float = 0f,
        var lastDownloadedBytes: Long = 0L,
        var lastTotalBytes: Long = 0L
    )

    private val speedSamples = ArrayDeque<Long>(10)
    private var lastEmitTime = 0L
    private var lastReportedProgress = 0f
    private val emitIntervalMs = 250L

    private fun resetSmoothing() {
        speedSamples.clear()
        lastEmitTime = 0L
        lastReportedProgress = 0f
    }

    private fun smoothedSpeed(rawBps: Long): Long {
        if (rawBps > 0) speedSamples.addLast(rawBps)
        if (speedSamples.size > 10) speedSamples.removeFirst()
        return if (speedSamples.isEmpty()) rawBps else speedSamples.average().toLong()
    }

    private fun monotonicProgress(raw: Float): Float {
        val result = maxOf(raw, lastReportedProgress)
        lastReportedProgress = result
        return result
    }

    private data class DownloadSettings(
        val playlist: Boolean,
        val extractAudio: Boolean,
        val audioFormat: String,
        val embedThumbnail: Boolean,
        val embedMetadata: Boolean,
        val rateLimit: Boolean,
        val maxRate: String,
        val concurrentFragments: Int,
        val retries: Int
    )

    private suspend fun readSettings(): DownloadSettings = DownloadSettings(
        playlist = preferences.playlist.first(),
        extractAudio = preferences.extractAudio.first(),
        audioFormat = preferences.audioFormat.first(),
        embedThumbnail = preferences.embedThumbnail.first(),
        embedMetadata = preferences.embedMetadata.first(),
        rateLimit = preferences.rateLimit.first(),
        maxRate = preferences.maxRate.first(),
        concurrentFragments = preferences.concurrentFragments.first(),
        retries = preferences.retries.first()
    )

    private fun applySettings(request: YoutubeDLRequest, settings: DownloadSettings) {
        if (settings.playlist) {
            request.addOption("--yes-playlist")
        } else {
            request.addOption("--no-playlist")
        }
        if (settings.extractAudio) {
            request.addOption("-x")
            request.addOption("--audio-format", settings.audioFormat)
        }
        if (settings.embedThumbnail) {
            request.addOption("--write-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        }
        if (settings.embedMetadata) {
            request.addOption("--add-metadata")
            request.addOption("--embed-chapters")
            request.addOption("--no-embed-info-json")
        }
        if (settings.rateLimit) {
            request.addOption("--limit-rate", settings.maxRate)
        }
        request.addOption("--concurrent-fragments", settings.concurrentFragments.toString())
    }

    private val recoverablePatterns = listOf(
        Regex("timeout", RegexOption.IGNORE_CASE),
        Regex("connection reset", RegexOption.IGNORE_CASE),
        Regex("connection refused", RegexOption.IGNORE_CASE),
        Regex("HTTPSConnectionPool", RegexOption.IGNORE_CASE),
        Regex("fragment not found", RegexOption.IGNORE_CASE),
        Regex("unable to extract", RegexOption.IGNORE_CASE),
        Regex("HTTP Error 5\\d{2}", RegexOption.IGNORE_CASE),
        Regex("throttl", RegexOption.IGNORE_CASE),
        Regex("network error", RegexOption.IGNORE_CASE),
        Regex("socket", RegexOption.IGNORE_CASE),
        Regex("read timed out", RegexOption.IGNORE_CASE),
        Regex("reset by peer", RegexOption.IGNORE_CASE),
        Regex("TLS", RegexOption.IGNORE_CASE),
        Regex("EOF", RegexOption.IGNORE_CASE),
        Regex("extractor.*fail", RegexOption.IGNORE_CASE),
        Regex("unexpected error", RegexOption.IGNORE_CASE)
    )

    private val nonRecoverablePatterns = listOf(
        Regex("video unavailable", RegexOption.IGNORE_CASE),
        Regex("private video", RegexOption.IGNORE_CASE),
        Regex("requires login", RegexOption.IGNORE_CASE),
        Regex("copyright", RegexOption.IGNORE_CASE),
        Regex("takedown", RegexOption.IGNORE_CASE),
        Regex("HTTP Error 4\\d{2}", RegexOption.IGNORE_CASE),
        Regex("This video is not available", RegexOption.IGNORE_CASE),
        Regex("removed by the uploader", RegexOption.IGNORE_CASE),
        Regex("account terminated", RegexOption.IGNORE_CASE),
        Regex("No video formats found", RegexOption.IGNORE_CASE),
        Regex("Usage:", RegexOption.IGNORE_CASE)
    )

    private fun isRecoverableError(errorMessage: String): Boolean {
        if (nonRecoverablePatterns.any { it.containsMatchIn(errorMessage) }) return false
        return recoverablePatterns.any { it.containsMatchIn(errorMessage) }
    }

    private fun getWorkingDir(): File {
        return File(context.cacheDir, "ytdlp").also { it.mkdirs() }
    }

    private fun copyToExternal(internalPath: String, outputDir: String): String {
        val srcFile = File(internalPath)
        val destFile = File(outputDir, srcFile.name)
        srcFile.copyTo(destFile, overwrite = true)
        com.otter.app.util.FileHelper.scanFile(context, destFile.absolutePath)
        return destFile.absolutePath
    }

    private fun cleanup() {
        getWorkingDir().deleteRecursively()
    }

    private fun addCookies(request: YoutubeDLRequest) {
        val cookieFile = File(context.filesDir, "cookies.txt")
        if (cookieFile.exists() && cookieFile.length() > 0) {
            request.addOption("--cookies", cookieFile.absolutePath)
        }
    }

    private fun addSubtitleFlags(request: YoutubeDLRequest, manualLangs: String, autoLangs: String, embed: Boolean, format: String) {
        val manual = manualLangs.split(",").filter { it.isNotBlank() }
        val auto = autoLangs.split(",").filter { it.isNotBlank() }

        val hasManual = manual.isNotEmpty()
        val hasAuto = auto.isNotEmpty()
        if (!hasManual && !hasAuto) return

        val allLangs = (manual + auto).distinct().joinToString(",")
        Log.i(TAG, "Applying subtitle flags: manual=$manualLangs auto=$autoLangs format=$format embed=$embed")

        if (hasManual) request.addOption("--write-subs")
        if (hasAuto) request.addOption("--write-auto-subs")

        request.addOption("--sub-langs", allLangs)
        request.addOption("--sub-format", "$format/best")
        request.addOption("--convert-subs", format)
        if (embed && hasManual) {
            request.addOption("--embed-subs")
        }
    }

    suspend fun download(
        url: String,
        formatId: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit,
        subtitleLangs: String = "",
        embedSubtitles: Boolean = false,
        subtitleFormat: String = "srt",
        autoCaptionLangs: String = "",
        title: String = ""
    ) = withContext(Dispatchers.IO) {
        val settings = readSettings()
        var attempt = 0
        var lastError: Exception? = null
        Log.i(TAG, "download START url=$url formatId=$formatId outputDir=$outputDir title=$title")

        while (attempt <= settings.retries) {
            attempt++
            if (attempt > 1) {
                val waitMs = listOf(1000L, 2000L, 4000L, 8000L, 16000L).getOrElse(attempt - 2) { 16000L }
                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
                Log.i(TAG, "Retry $attempt/${settings.retries} for $url (waiting ${waitMs}ms)")
                delay(waitMs)
            }

            try {
                val workingDir = getWorkingDir()

                val outputFile = File(outputDir)
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw IOException(context.getString(R.string.creating_output_dir_failed) + ": $outputDir")
                }

                val request = YoutubeDLRequest(url).apply {
                    addCookies(this)
                    addOption("-f", formatId)
                    addOption("-P", workingDir.absolutePath)
                    addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                    addOption("--no-mtime")
                    addOption("--continue")
                    addOption("--socket-timeout", "15")
                    applySettings(this, settings)
                    addSubtitleFlags(this, subtitleLangs, autoCaptionLangs, embedSubtitles, subtitleFormat)
                }

                val processId = url.hashCode().toUInt().toString()
                activeDownloads[url] = processId
                val activeInfo = ActiveDownload(processId, url, formatId, outputDir, title)
                activeDownloadInfo[url] = activeInfo
                resetSmoothing()

                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))

                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime < emitIntervalMs) return@execute
                    lastEmitTime = now

                    val monoProgress = monotonicProgress(progress / 100f)
                    val parsed = parseProgressLine(line ?: "")
                    val rawBytes = if (parsed != null && parsed.totalBytes > 0) (parsed.totalBytes * monoProgress).toLong() else 0L
                    val rawSpeed = parsed?.speedBytesPerSec ?: 0L
                    val smoothSpeed = smoothedSpeed(rawSpeed)
                    val totalBytes = parsed?.totalBytes ?: 0L
                    val etaSec = if (smoothSpeed > 0 && totalBytes > 0) ((totalBytes - rawBytes) / smoothSpeed) else etaInSeconds

                    activeInfo.lastProgress = monoProgress
                    activeInfo.lastDownloadedBytes = rawBytes
                    activeInfo.lastTotalBytes = totalBytes

                    onProgress(
                        DownloadState.Downloading(
                            progress = monoProgress,
                            downloadedBytes = rawBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = smoothSpeed,
                            etaSeconds = etaSec,
                            phase = DownloadPhase.DOWNLOADING
                        )
                    )
                }

                activeDownloads.remove(url)
                activeDownloadInfo.remove(url)

                val downloadedFile = findDownloadedFile(workingDir.absolutePath, url)
                if (subtitleLangs.isNotBlank()) {
                    copySubtitleFiles(workingDir.absolutePath, outputDir, subtitleLangs)
                }
                if (downloadedFile != null) {
                    val fileSize = File(downloadedFile).length()
                    Log.i(TAG, "download VERIFIED file=$downloadedFile size=${fileSize} bytes")
                    val externalPath = copyToExternal(downloadedFile, outputDir)
                    cleanup()
                    Log.i(TAG, "download COMPLETE externalPath=$externalPath")
                    onProgress(DownloadState.Success(externalPath))
                } else {
                    Log.e(TAG, "download claimed success but no file found in $outputDir")
                    onProgress(DownloadState.Error(context.getString(R.string.download_file_missing)))
                }
                return@withContext

            } catch (e: Exception) {
                lastError = e
                activeDownloads.remove(url)
                val msg = e.message ?: ""

                if (!isRecoverableError(msg) || attempt > settings.retries) {
                    Log.e(TAG, "Non-recoverable or max retries reached for $url: $msg")
                    onProgress(DownloadState.Error(
                        if (attempt > 1) context.getString(R.string.failed_after_retries, settings.retries, e.message ?: context.getString(R.string.download_failed_text))
                        else e.message ?: context.getString(R.string.download_failed_text)
                    ))
                    return@withContext
                }

                Log.w(TAG, "Attempt $attempt failed for $url, will retry. Error: $msg")
                cleanup()
            }
        }

    }

    suspend fun downloadThumbnail(
        url: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = readSettings()
        try {
            val workingDir = getWorkingDir()

            val outputFile = File(outputDir)
            if (!outputFile.exists() && !outputFile.mkdirs()) {
                throw IOException("Failed to create output directory: $outputDir")
            }

            val request = YoutubeDLRequest(url).apply {
                addCookies(this)
                addOption("--skip-download")
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("-P", workingDir.absolutePath)
                addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                if (!settings.playlist) {
                    addOption("--no-playlist")
                }
            }

            val processId = "thumb_${url.hashCode().toUInt()}"
            activeDownloads[url] = processId

            onProgress(DownloadState.Downloading(0.5f, phase = DownloadPhase.DOWNLOADING))

            YoutubeDL.getInstance().execute(request, processId) { _, _, _ -> }

            activeDownloads.remove(url)

            val thumbFile = findThumbnailFile(workingDir.absolutePath, url)
            if (thumbFile != null) {
                val externalPath = copyToExternal(thumbFile, outputDir)
                cleanup()
                onProgress(DownloadState.Success(externalPath))
            } else {
                onProgress(DownloadState.Success(outputDir))
            }

        } catch (e: Exception) {
            activeDownloads.remove(url)
            onProgress(DownloadState.Error(e.message ?: context.getString(R.string.thumbnail_download_failed)))
        }
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
    ) = withContext(Dispatchers.IO) {
        val settings = readSettings()
        var attempt = 0
        var lastError: Exception? = null

        while (attempt <= settings.retries) {
            attempt++
            if (attempt > 1) {
                val waitMs = listOf(1000L, 2000L, 4000L, 8000L, 16000L).getOrElse(attempt - 2) { 16000L }
                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
                delay(waitMs)
            }

            try {
                val workingDir = getWorkingDir()

                val outputFile = File(outputDir)
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw IOException(context.getString(R.string.creating_output_dir_failed) + ": $outputDir")
                }

                val request = YoutubeDLRequest(url).apply {
                    addCookies(this)
                    addOption("--no-mtime")
                    addOption("-f", formatId)
                    addOption("--merge-output-format", "mp4")
                    addOption("--audio-quality", "0")
                    addOption("--write-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("-P", workingDir.absolutePath)
                    addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                    addOption("--continue")
                    addOption("--socket-timeout", "15")
                    applySettings(this, settings)
                    addSubtitleFlags(this, subtitleLangs, autoCaptionLangs, embedSubtitles, subtitleFormat)
                }

                val processId = url.hashCode().toUInt().toString()
                activeDownloads[url] = processId
                val activeInfo = ActiveDownload(processId, url, formatId, outputDir, title)
                activeDownloadInfo[url] = activeInfo
                resetSmoothing()

                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))

                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime < emitIntervalMs) return@execute
                    lastEmitTime = now

                    val monoProgress = monotonicProgress(progress / 100f)
                    val parsed = parseProgressLine(line ?: "")
                    val rawBytes = if (parsed != null && parsed.totalBytes > 0) (parsed.totalBytes * monoProgress).toLong() else 0L
                    val rawSpeed = parsed?.speedBytesPerSec ?: 0L
                    val smoothSpeed = smoothedSpeed(rawSpeed)
                    val totalBytes = parsed?.totalBytes ?: 0L
                    val etaSec = if (smoothSpeed > 0 && totalBytes > 0) ((totalBytes - rawBytes) / smoothSpeed) else etaInSeconds

                    activeInfo.lastProgress = monoProgress
                    activeInfo.lastDownloadedBytes = rawBytes
                    activeInfo.lastTotalBytes = totalBytes

                    onProgress(
                        DownloadState.Downloading(
                            progress = monoProgress,
                            downloadedBytes = rawBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = smoothSpeed,
                            etaSeconds = etaSec,
                            phase = DownloadPhase.DOWNLOADING
                        )
                    )
                }

                activeDownloads.remove(url)
                activeDownloadInfo.remove(url)

                val downloadedFile = findDownloadedFile(workingDir.absolutePath, url)
                if (subtitleLangs.isNotBlank()) {
                    copySubtitleFiles(workingDir.absolutePath, outputDir, subtitleLangs)
                }
                if (downloadedFile != null) {
                    val fileSize = File(downloadedFile).length()
                    Log.i(TAG, "downloadWithThumbnail VERIFIED file=$downloadedFile size=${fileSize} bytes")
                    val externalPath = copyToExternal(downloadedFile, outputDir)
                    val thumbFile = findThumbnailFile(workingDir.absolutePath, url)
                    if (thumbFile != null) {
                        copyToExternal(thumbFile, outputDir)
                    }
                    cleanup()
                    Log.i(TAG, "downloadWithThumbnail COMPLETE externalPath=$externalPath")
                    onProgress(DownloadState.Success(externalPath))
                } else {
                    Log.e(TAG, "downloadWithThumbnail claimed success but no file found in $outputDir")
                    onProgress(DownloadState.Error(context.getString(R.string.download_file_missing)))
                }
                return@withContext

            } catch (e: Exception) {
                lastError = e
                activeDownloads.remove(url)
                val msg = e.message ?: ""

                if (!isRecoverableError(msg) || attempt > settings.retries) {
                    val errorMsg = if (attempt > 1) context.getString(R.string.failed_after_retries, settings.retries, e.message ?: context.getString(R.string.download_failed_text))
                    else e.message ?: context.getString(R.string.download_failed_text)
                    onProgress(DownloadState.Error(errorMsg))
                    return@withContext
                }

                Log.w(TAG, "Attempt $attempt failed for $url, will retry. Error: $msg")
                cleanup()
            }
        }

    }

    suspend fun downloadWithOptions(
        url: String,
        formatId: String,
        outputDir: String,
        options: Map<String, String>,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = readSettings()
        try {
            val workingDir = getWorkingDir()

            val outputFile = File(outputDir)
            if (!outputFile.exists()) outputFile.mkdirs()

            val request = YoutubeDLRequest(url).apply {
                addCookies(this)
                addOption("-f", formatId)
                addOption("--merge-output-format", "mp4")
                addOption("--audio-quality", "0")
                addOption("-P", workingDir.absolutePath)
                addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                addOption("--no-mtime")
                applySettings(this, settings)
                for ((key, value) in options) {
                    addOption(key, value)
                }
            }

            val processId = url.hashCode().toString()
            activeDownloads[url] = processId
            resetSmoothing()
            onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))

            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val now = System.currentTimeMillis()
                if (now - lastEmitTime < emitIntervalMs) return@execute
                lastEmitTime = now

                val monoProgress = monotonicProgress(progress / 100f)
                val parsed = parseProgressLine(line ?: "")
                val rawBytes = if (parsed != null && parsed.totalBytes > 0) (parsed.totalBytes * monoProgress).toLong() else 0L
                val rawSpeed = parsed?.speedBytesPerSec ?: 0L
                val smoothSpeed = smoothedSpeed(rawSpeed)
                val totalBytes = parsed?.totalBytes ?: 0L
                val etaSec = if (smoothSpeed > 0 && totalBytes > 0) ((totalBytes - rawBytes) / smoothSpeed) else etaInSeconds

                onProgress(
                    DownloadState.Downloading(
                        progress = monoProgress,
                        downloadedBytes = rawBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = smoothSpeed,
                        etaSeconds = etaSec,
                        phase = DownloadPhase.DOWNLOADING
                    )
                )
            }

            activeDownloads.remove(url)
            val downloadedFile = findDownloadedFile(workingDir.absolutePath, url)
            if (downloadedFile != null) {
                val fileSize = File(downloadedFile).length()
                Log.i(TAG, "downloadWithOptions VERIFIED file=$downloadedFile size=${fileSize} bytes")
                val externalPath = copyToExternal(downloadedFile, outputDir)
                cleanup()
                onProgress(DownloadState.Success(externalPath))
            } else {
                Log.e(TAG, "downloadWithOptions claimed success but no file found in $outputDir")
                onProgress(DownloadState.Error(context.getString(R.string.download_file_missing)))
            }

        } catch (e: Exception) {
            activeDownloads.remove(url)
            onProgress(DownloadState.Error(e.message ?: context.getString(R.string.download_failed_text)))
        }
    }

    suspend fun downloadImages(
        imageUrls: List<String>,
        baseName: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outputFile = File(outputDir)
        if (!outputFile.exists() && !outputFile.mkdirs()) {
            onProgress(DownloadState.Error(context.getString(R.string.creating_output_dir_failed)))
            return@withContext
        }

        val safeBase = baseName.replace(Regex("""[<>:"/\\|?*]"""), "_").take(100)
        val total = imageUrls.size
        val savedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val savedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicInteger(0)

        // Pre-resolve all hostnames in parallel (one-shot DNS prewarm) before opening sockets
        runCatching {
            val hosts = imageUrls.mapNotNull { runCatching { URL(it).host }.getOrNull() }.distinct()
            hosts.map { host ->
                async(Dispatchers.IO) {
                    runCatching { java.net.InetAddress.getAllByName(host) }
                }
            }.awaitAll()
        }

        // Pre-open N connections in parallel for max throughput
        val concurrency = minOf(6, total)
        val semaphore = Semaphore(concurrency)

        val jobs = imageUrls.mapIndexed { index, imageUrl ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                try {
                    val fileName = "${safeBase}_${index + 1}.jpg"
                    val destFile = File(outputDir, fileName)

                    val url = URL(imageUrl)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("Connection", "keep-alive")
                        setRequestProperty("Cache-Control", "no-cache")
                        useCaches = false
                        try {
                            setFixedLengthStreamingMode(-1)
                        } catch (e: Exception) {
                            Log.v(TAG, "setFixedLengthStreamingMode not supported: ${e.message}")
                        }
                        connect()

                        if (responseCode !in 200..299) {
                            throw IOException("HTTP $responseCode")
                        }

                        val contentLength = contentLengthLong.takeIf { it > 0 } ?: 0L
                        inputStream.use { input ->
                            FileOutputStream(destFile).use { output ->
                                val buf = ByteArray(262_144)
                                var copied = 0L
                                var lastReport = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    output.write(buf, 0, n)
                                    copied += n
                                }
                            }
                        }
                    }
                    connection.disconnect()

                    com.otter.app.util.FileHelper.scanFile(context, destFile.absolutePath)
                    savedPaths.add(destFile.absolutePath)
                    val done = savedCount.incrementAndGet()

                    val progress = done.toFloat() / total.toFloat()
                    onProgress(DownloadState.Downloading(progress, phase = DownloadPhase.DOWNLOADING))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download image $index: $imageUrl", e)
                    errors.incrementAndGet()
                }
                }
            }
        }
        awaitAll(*jobs.toTypedArray())

        if (savedPaths.isEmpty()) {
            onProgress(DownloadState.Error(context.getString(R.string.failed_all_images)))
        } else if (savedPaths.size == 1) {
            onProgress(DownloadState.Success(savedPaths.first()))
        } else {
            onProgress(DownloadState.Success(savedPaths.sorted().joinToString("\n")))
        }
    }

    fun pauseDownload(url: String): ActiveDownload? {
        Log.i(TAG, "pauseDownload: called for url=$url")
        val info = activeDownloadInfo[url]
        if (info == null) {
            Log.w(TAG, "pauseDownload: no active download found for $url")
            return null
        }
        // Snapshot state BEFORE killing the process
        val snapshot = info.copy()
        Log.i(TAG, "pauseDownload: snapshot taken progress=${snapshot.lastProgress * 100}% bytes=${snapshot.lastDownloadedBytes}/${snapshot.lastTotalBytes}")
        // Kill the process
        try {
            YoutubeDL.getInstance().destroyProcessById(snapshot.processId)
            Log.i(TAG, "pauseDownload: process ${snapshot.processId} destroyed for $url")
        } catch (e: Throwable) {
            Log.w(TAG, "pauseDownload: destroyProcessById threw: ${e.message}")
        }
        activeDownloads.remove(url)
        activeDownloadInfo.remove(url)
        Log.i(TAG, "pauseDownload: complete for $url")
        return snapshot
    }

    fun stopDownload(url: String) {
        Log.i(TAG, "stopDownload: called for url=$url")
        val info = activeDownloadInfo[url]
        val processId = activeDownloads[url]
        if (processId != null) {
            try {
                YoutubeDL.getInstance().destroyProcessById(processId)
                Log.i(TAG, "stopDownload: process $processId destroyed")
            } catch (e: Throwable) {
                Log.w(TAG, "stopDownload: destroyProcessById threw: ${e.message}")
            }
        }
        activeDownloads.remove(url)
        activeDownloadInfo.remove(url)
        if (info != null) {
            cleanupPartialFiles(info.outputDir)
            Log.i(TAG, "stopDownload: partial files cleaned up")
        }
        Log.i(TAG, "stopDownload: complete for $url")
    }

    fun cancelDownload(url: String) {
        stopDownload(url)
    }

    fun cleanupPartialFiles(outputDir: String) {
        try {
            val dir = File(outputDir)
            dir.listFiles { f -> f.name.endsWith(".part") || f.name.endsWith(".ytdl") }
                ?.forEach { it.delete(); Log.i(TAG, "Deleted partial: ${it.name}") }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupPartialFiles error: ${e.message}")
        }
    }

    private fun findDownloadedFile(outputDir: String, url: String): String? {
        return try {
            val dir = File(outputDir)
            val files = dir.listFiles()?.filter { it.isFile && (it.extension in listOf("mp4", "m4a", "webm", "mp3", "opus", "mkv")) }
            if (files.isNullOrEmpty()) {
                Log.w(TAG, "findDownloadedFile: no media files in $outputDir")
                return null
            }
            val best = files.maxByOrNull { it.lastModified() }
            Log.i(TAG, "findDownloadedFile: found ${files.size} files, best=${best?.name}")
            best?.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "findDownloadedFile error: ${e.message}")
            null
        }
    }

    private fun copySubtitleFiles(workingDir: String, outputDir: String, subtitleLangs: String) {
        try {
            val srcDir = File(workingDir)
            val langs = subtitleLangs.split(",").map { it.trim() }
            val subExtensions = setOf("srt", "vtt", "ass", "ssa", "lrc")
            val subFiles = srcDir.listFiles()?.filter { f ->
                f.isFile && f.extension.lowercase() in subExtensions &&
                langs.any { lang -> f.nameWithoutExtension.endsWith(".$lang") }
            } ?: emptyList()
            for (file in subFiles) {
                val dest = File(outputDir, file.name)
                file.copyTo(dest, overwrite = true)
                com.otter.app.util.FileHelper.scanFile(context, dest.absolutePath)
                Log.i(TAG, "Copied subtitle: ${file.name} -> $dest")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy subtitle files", e)
        }
    }

    private fun findThumbnailFile(outputDir: String, url: String): String? {
        return try {
            val dir = File(outputDir)
            dir.listFiles()
                ?.filter { it.isFile && it.extension in listOf("jpg", "jpeg", "png", "webp") }
                ?.maxByOrNull { it.lastModified() }
                ?.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
