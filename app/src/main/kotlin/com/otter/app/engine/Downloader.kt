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
import kotlinx.coroutines.CancellationException
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

class Downloader(private val context: Context, private val formatParser: FormatParser? = null) {

    companion object {
        private const val TAG = "Downloader"
        private const val OUTPUT_TEMPLATE_DEFAULT = "%(title).200B [%(id)s].%(ext)s"

        private val progressRegex = Regex(
            """\[download\]\s+(\d+\.?\d*)%\s+of\s+~?\s*(\d+\.?\d*)([KMG]i?B|B)(?:\s+at\s+(\d+\.?\d*)([KMG]i?B|B)/s)?(?:\s+ETA\s+(\d+):(\d+)(?::(\d+))?)?""",
            RegexOption.IGNORE_CASE
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
            val hasEta = m.groupValues.size > 7 && m.groupValues[6].isNotEmpty()
            val hasSeconds = m.groupValues.size > 8 && m.groupValues[8].isNotEmpty()
            val h = if (hasSeconds) m.groupValues[6].toIntOrNull() ?: 0 else 0
            val mn = if (hasSeconds) m.groupValues[7].toIntOrNull() ?: 0 else if (hasEta) m.groupValues[6].toIntOrNull() ?: 0 else 0
            val s = if (hasSeconds) m.groupValues[8].toIntOrNull() ?: 0 else if (hasEta) m.groupValues[7].toIntOrNull() ?: 0 else 0
            return ParsedProgress(
                percent = pct,
                totalBytes = parseSize(sizeVal, sizeUnit),
                speedBytesPerSec = if (speedUnit.isNotEmpty()) parseSize(speedVal, speedUnit) else 0L,
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

        private enum class Platform {
            YOUTUBE, TIKTOK, FACEBOOK, INSTAGRAM, TWITTER, REDDIT, TWITCH, VIMEO,
            BILIBILI, DAILYMOTION, PINTEREST, STREAMABLE, BANDCAMP, SOUNDCLOUD, SNAPCHAT, UNKNOWN
        }

        private val DL_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
        private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private fun detectPlatform(url: String): Platform {
            fun matches(vararg domains: String) = domains.any { MediaUrlNormalizer.hostMatches(url, it) }
            return when {
                matches("youtube.com", "youtu.be", "youtube-nocookie.com") -> Platform.YOUTUBE
                matches("tiktok.com", "tiktokv.com") -> Platform.TIKTOK
                matches("facebook.com", "fb.watch", "fb.com") -> Platform.FACEBOOK
                matches("instagram.com", "instagr.am") -> Platform.INSTAGRAM
                matches("twitter.com", "x.com", "t.co") -> Platform.TWITTER
                matches("reddit.com", "redd.it") -> Platform.REDDIT
                matches("twitch.tv") -> Platform.TWITCH
                matches("vimeo.com") -> Platform.VIMEO
                matches("bilibili.com") -> Platform.BILIBILI
                matches("dailymotion.com", "dai.ly") -> Platform.DAILYMOTION
                matches("pinterest.com", "pin.it") -> Platform.PINTEREST
                matches("streamable.com") -> Platform.STREAMABLE
                matches("bandcamp.com") -> Platform.BANDCAMP
                matches("soundcloud.com") -> Platform.SOUNDCLOUD
                matches("snapchat.com") -> Platform.SNAPCHAT
                else -> Platform.UNKNOWN
            }
        }

        private fun isLiveStream(url: String): Boolean {
            val parsed = runCatching { URL(url) }.getOrNull() ?: return false
            val isYouTube = MediaUrlNormalizer.hostMatches(url, "youtube.com") ||
                MediaUrlNormalizer.hostMatches(url, "youtu.be")
            val path = parsed.path.lowercase()
            return isYouTube && (path == "/live" || path.startsWith("/live/"))
        }

        private fun concurrentFragments(platform: Platform, isLive: Boolean): Int {
            if (isLive) return 1
            return when (platform) {
                Platform.YOUTUBE, Platform.FACEBOOK, Platform.INSTAGRAM, Platform.TIKTOK, Platform.VIMEO -> 8
                Platform.TWITTER, Platform.REDDIT, Platform.DAILYMOTION, Platform.SOUNDCLOUD, Platform.BANDCAMP -> 5
                Platform.BILIBILI, Platform.TWITCH, Platform.SNAPCHAT, Platform.PINTEREST -> 3
                Platform.STREAMABLE, Platform.UNKNOWN -> 3
            }
        }

        private fun applyPlatformArgs(request: YoutubeDLRequest, url: String, isLive: Boolean) {
            val platform = detectPlatform(url)
            Log.i(TAG, "[DL-PLATFORM] platform=$platform url=$url")

            if (isLive) {
                request.addOption("--concurrent-fragments", "1")
                request.addOption("--hls-use-mpegts")
                request.addOption("--downloader", "native")
                request.addOption("--extractor-retries", "5")
                Log.i(TAG, "[LIVE-STREAM] detected live stream, sequential fragments + HLS + native downloader")
            }

            if (platform != Platform.YOUTUBE && platform != Platform.UNKNOWN
                && platform != Platform.TIKTOK && platform != Platform.FACEBOOK && platform != Platform.INSTAGRAM
                && platform != Platform.REDDIT
            ) {
                request.addOption("--user-agent", DL_UA)
            }

            when (platform) {
                Platform.TIKTOK -> {
                    // Match the public web extractor used by FormatParser. A hard-coded
                    // mobile IID made metadata and downloads use different format lists.
                    request.addOption("--user-agent", MediaUrlNormalizer.TIKTOK_USER_AGENT)
                    request.addOption("--add-header", "Referer:https://www.tiktok.com/")
                    request.addOption("--extractor-retries", "5")
                }
                Platform.FACEBOOK -> {
                    request.addOption("--add-header", "Referer:https://www.facebook.com")
                    request.addOption("--extractor-retries", "5")
                }
                Platform.TWITTER -> {
                    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                    request.addOption("--extractor-retries", "5")
                }
                Platform.REDDIT -> {
                    request.addOption("--user-agent", DESKTOP_UA)
                    request.addOption("--merge-output-format", "mp4")
                }
                Platform.TWITCH -> {
                    request.addOption("--socket-timeout", "20")
                }
                Platform.VIMEO -> {
                    request.addOption("--add-header", "Referer:https://vimeo.com")
                }
                Platform.BILIBILI -> {
                    request.addOption("--add-header", "Referer:https://www.bilibili.com")
                    request.addOption("--add-header", "Accept-Language:zh-CN,zh;q=0.9,en;q=0.8")
                    request.addOption("--geo-bypass")
                }
                Platform.SNAPCHAT -> {
                    request.addOption("--add-header", "Referer:https://www.snapchat.com")
                }
                Platform.YOUTUBE -> {
                    // Let yt-dlp use its current client headers (important for Shorts and EJS challenges).
                    request.addOption("--extractor-retries", "5")
                }
                else -> {}
            }
        }


        private suspend fun refreshFormatInfo(
            formatParser: FormatParser,
            url: String,
            currentFormatId: String
        ): String? {
            return try {
                Log.w(TAG, "[FORMAT-REFRESH] Format may be expired, re-fetching for $url")
                val result = formatParser.fetchVideoInfo(url)
                if (result.isFailure) {
                    Log.w(TAG, "[FORMAT-REFRESH] Re-fetch failed: ${result.exceptionOrNull()?.message}")
                    return null
                }
                val info = result.getOrThrow()
                val matching = info.formats.find { fmt ->
                    fmt.formatId == currentFormatId || fmt.formatId.startsWith(currentFormatId.take(5))
                }
                if (matching != null) {
                    Log.i(TAG, "[FORMAT-REFRESH] Found matching format: ${matching.formatId} (was $currentFormatId)")
                    matching.formatId
                } else {
                    val firstVideo = info.formats.firstOrNull { !it.isAudioOnly }
                    if (firstVideo != null) {
                        Log.i(TAG, "[FORMAT-REFRESH] No exact match, using first video: ${firstVideo.formatId}")
                        firstVideo.formatId
                    } else {
                        Log.w(TAG, "[FORMAT-REFRESH] No formats found in refreshed info")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[FORMAT-REFRESH] Exception during refresh: ${e.message}")
                null
            }
        }
    }

    private val preferences = UserPreferences(context)
    private val activeDownloads = ConcurrentHashMap<String, String>()
    private val activeDownloadInfo = ConcurrentHashMap<String, ActiveDownload>()
    private val intentionallyCancelled = ConcurrentHashMap<String, Boolean>()

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
        val retries: Int,
        val cookiesEnabled: Boolean
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
        retries = preferences.retries.first(),
        cookiesEnabled = preferences.cookiesEnabled.first()
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
        // Deliberately not added here: the number is resolved together with the platform default in
        // the request builder, and adding it twice would make the earlier value meaningless.
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

    /** A URL without its query string: signed CDN URLs and tokens do not belong in logcat. */
    private fun redact(url: String): String = url.substringBefore('?')

    private fun getWorkingDir(): File {
        return File(context.cacheDir, "ytdlp").also { it.mkdirs() }
    }

    private fun copyToExternal(internalPath: String, outputDir: String): String {
        val srcFile = File(internalPath)
        var destFile = File(outputDir, srcFile.name)
        // Delete existing file at destination if present (more reliable than copyTo overwrite on some devices)
        if (destFile.exists()) {
            destFile.delete()
        }
        // If delete failed or file still exists, add a numeric suffix
        if (destFile.exists()) {
            var counter = 1
            val name = srcFile.nameWithoutExtension
            val ext = srcFile.extension
            while (destFile.exists()) {
                destFile = File(outputDir, "${name} ($counter).$ext")
                counter++
            }
        }
        srcFile.copyTo(destFile)
        Log.i(TAG, "copyToExternal: $internalPath -> $destFile")
        com.otter.app.util.FileHelper.scanFile(context, destFile.absolutePath)
        return destFile.absolutePath
    }

    /**
     * Clears the staging directory. Only the app's own cache is ever deleted: when a
     * download wrote straight into the download folder, that folder is not ours to
     * clear however the download ended.
     */
    private fun cleanup(dir: File = getWorkingDir()) {
        val cacheRoot = context.cacheDir.absolutePath
        if (dir.absolutePath == cacheRoot || dir.absolutePath.startsWith("$cacheRoot/")) {
            dir.deleteRecursively()
        }
    }

    private fun addCookies(request: YoutubeDLRequest, url: String, enabled: Boolean = false) {
        if (!MediaUrlNormalizer.shouldAttachSavedCookies(url, enabled)) return
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
        EngineLock.markUsed()
        var currentFormatId = formatId
        val isLive = isLiveStream(url)
        val platform = detectPlatform(url)
        val settings = readSettings()
        // A long YouTube video does not belong in the app cache: staging it there
        // needs room for the video twice over and costs a second full write when the
        // finished file is copied out. YouTube writes straight into the download
        // folder instead. Everything else keeps the staging directory, which is
        // tidier for the small files the other platforms produce.
        // Live streams are excluded and stage in the cache as before.
        val youtubeVideoId = MediaUrlNormalizer.youtubeVideoId(url)
        val writesDirectly = youtubeVideoId.isNotEmpty() && !isLive
        val maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS
        var attempt = 0
        var formatRefreshes = 0
        Log.i(TAG, "download START url=${redact(url)} formatId=$currentFormatId outputDir=$outputDir title=$title live=$isLive platform=$platform")

        // `attempt` only ever grows. The recovery below used to push it back down, which is how a
        // video whose format URLs kept answering 403 ended up retrying forever: an engine run plus a
        // full re-extraction each lap, no error shown, and a foreground service that never stopped.
        while (attempt < maxAttempts) {
            val waitMs = DownloadRetryPolicy.waitBefore(attempt + 1)
            if (attempt > 0 && waitMs > 0L) {
                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
                Log.i(TAG, "[DL-RETRY] attempt=${attempt + 1}/$maxAttempts for ${redact(url)} (waiting ${waitMs}ms)")
                delay(waitMs)
            }
            val localFormatId = currentFormatId
            attempt++
            val usedCachedExtraction = writesDirectly && attempt == 1

            try {
                val outputFile = File(outputDir)
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw IOException(context.getString(R.string.creating_output_dir_failed) + ": $outputDir")
                }
                val workingDir = if (writesDirectly) outputFile else getWorkingDir()

                val request = YoutubeDLRequest(MediaUrlNormalizer.normalizeYouTubeShortsUrl(url)).apply {
                    addCookies(this, url, settings.cookiesEnabled)
                    addOption("-f", localFormatId)
                    addOption("-P", workingDir.absolutePath)
                    addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                    addOption("--no-mtime")
                    addOption("--continue")
                    addOption(
                        "--socket-timeout",
                        EngineArgs.socketTimeoutSeconds(
                            baseSeconds = if (isLive) 30 else 15,
                            attempt = attempt,
                            isLive = isLive
                        ).toString()
                    )
                    applySettings(this, settings)
                    applyPlatformArgs(this, url, isLive)
                    addSubtitleFlags(this, subtitleLangs, autoCaptionLangs, embedSubtitles, subtitleFormat)

                    if (writesDirectly && attempt == 1) {
                        val saved = YoutubeInfoCache.file(context, youtubeVideoId)
                        if (YoutubeInfoCache.isFresh(saved)) {
                            addOption("--load-info-json", saved.absolutePath)
                            Log.i(TAG, "[DL] reusing the extraction from the format list")
                        }
                    }

                    // Emitted once, here, from both the user's setting and the platform default:
                    // both used to add the option and yt-dlp kept the last one, so the number in
                    // Settings did nothing on the platforms that have a platform default.
                    val cf = EngineArgs.concurrentFragments(
                        userValue = settings.concurrentFragments,
                        platformValue = concurrentFragments(platform, isLive)
                    )
                    addOption("--concurrent-fragments", cf.toString())
                }

                val processId = EngineArgs.processId(url)
                activeDownloads[url] = processId
                val activeInfo = ActiveDownload(processId, url, localFormatId, outputDir, title)
                activeDownloadInfo[url] = activeInfo
                resetSmoothing()

                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))

                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime < emitIntervalMs) return@execute
                    lastEmitTime = now

                    val monoProgress = monotonicProgress(progress / 100f)
                    val rawLine = line ?: ""
                    val parsed = parseProgressLine(rawLine)
                    val totalBytes = if (parsed != null && parsed.totalBytes > 0) parsed.totalBytes else activeInfo.lastTotalBytes
                    val rawBytes = if (totalBytes > 0) (totalBytes * monoProgress).toLong() else 0L
                    val rawSpeed = parsed?.speedBytesPerSec ?: 0L
                    val smoothSpeed = smoothedSpeed(rawSpeed)
                    val etaSec = if (smoothSpeed > 0 && totalBytes > 0) ((totalBytes - rawBytes) / smoothSpeed) else etaInSeconds

                    activeInfo.lastProgress = monoProgress
                    activeInfo.lastDownloadedBytes = rawBytes
                    activeInfo.lastTotalBytes = totalBytes

                    val phase = when {
                        rawLine.contains("[Merger]") || rawLine.contains("Merging formats") -> DownloadPhase.MERGING
                        rawLine.contains("[ExtractAudio]") -> DownloadPhase.POST_PROCESSING
                        monoProgress > 0f || rawLine.contains("[download]") -> DownloadPhase.DOWNLOADING
                        else -> DownloadPhase.PREPARING
                    }

                    onProgress(
                        DownloadState.Downloading(
                            progress = monoProgress,
                            downloadedBytes = rawBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = smoothSpeed,
                            etaSeconds = etaSec,
                            phase = phase
                        )
                    )
                }

                activeDownloads.remove(url)
                activeDownloadInfo.remove(url)

                val downloadedFile = findDownloadedFile(workingDir.absolutePath, url)
                if (downloadedFile != null) {
                    val expectedKind = when {
                        settings.extractAudio || currentFormatId.contains("audio") ||
                            localFormatId.contains("audio") || localFormatId.startsWith("bestaudio") ->
                            MediaFileValidator.Kind.AUDIO
                        else -> MediaFileValidator.Kind.VIDEO
                    }
                    when (val check = MediaFileValidator.validate(File(downloadedFile), expectedKind)) {
                        is MediaFileValidator.Outcome.Valid ->
                            Log.i(TAG, "download VALIDATED ${check.container} ${check.sizeBytes}B")
                        is MediaFileValidator.Outcome.Invalid -> {
                            // A file that is not what was asked for is a failure, not a success with
                            // a bad name: reporting it here is the difference between "downloaded" and
                            // "there is nothing to open".
                            Log.e(TAG, "download produced an invalid file: ${check.reason} ($downloadedFile)")
                            activeDownloads.remove(url)
                            onProgress(
                                DownloadState.Error(
                                    context.getString(R.string.download_file_invalid, check.reason)
                                )
                            )
                            return@withContext
                        }
                    }
                }
                if (subtitleLangs.isNotBlank() && !writesDirectly) {
                    copySubtitleFiles(workingDir.absolutePath, outputDir, subtitleLangs)
                }
                if (downloadedFile != null) {
                    val fileSize = File(downloadedFile).length()
                    Log.i(TAG, "download VERIFIED file=$downloadedFile size=${fileSize} bytes")
                    val externalPath = if (writesDirectly) {
                        // Already in the download folder: nothing to move.
                        com.otter.app.util.FileHelper.scanFile(context, downloadedFile)
                        downloadedFile
                    } else {
                        copyToExternal(downloadedFile, outputDir)
                    }
                    cleanup(workingDir)
                    Log.i(TAG, "download COMPLETE externalPath=$externalPath")
                    onProgress(DownloadState.Success(externalPath))
                } else {
                    Log.e(TAG, "download claimed success but no file found in $outputDir")
                    onProgress(DownloadState.Error(context.getString(R.string.download_file_missing)))
                }
                return@withContext

                } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message ?: ""
                if (intentionallyCancelled.remove(url) == true) {
                    Log.i(TAG, "download cancelled intentionally for $url (pause/stop)")
                    activeDownloads.remove(url)
                    return@withContext
                }
                activeDownloads.remove(url)

                // A cached extraction that produced this failure must not be tried again: it is the
                // expired format list, and reusing it is what just failed.
                if (usedCachedExtraction && youtubeVideoId.isNotEmpty()) {
                    YoutubeInfoCache.invalidate(YoutubeInfoCache.file(context, youtubeVideoId))
                }

                val decision = DownloadRetryPolicy.decide(
                    attempt = attempt,
                    formatRefreshes = formatRefreshes,
                    message = msg,
                    canReExtract = formatParser != null
                )
                when (decision.action) {
                    DownloadRetryPolicy.Action.FAIL -> {
                        Log.e(TAG, "download failed for $url after attempt $attempt: $msg")
                        val shown = when {
                            // A permanent answer is reported as it is: retrying will not change it.
                            DownloadRetryPolicy.isPermanent(msg) -> msg
                            attempt >= maxAttempts ->
                                context.getString(R.string.failed_after_retries, maxAttempts, msg)
                            else -> decision.reason.ifBlank { msg }
                        }
                        onProgress(DownloadState.Error(shown.ifBlank {
                            context.getString(R.string.failed_after_retries, maxAttempts, msg)
                        }))
                        return@withContext
                    }

                    DownloadRetryPolicy.Action.REFRESH_FORMAT -> {
                        formatRefreshes++
                        val newFmtId = refreshFormatInfo(formatParser!!, url, localFormatId)
                        if (newFmtId != null) {
                            currentFormatId = newFmtId
                            cleanup()
                            Log.i(TAG, "[DL-RETRY] retrying with a refreshed format $currentFormatId")
                            continue
                        }
                        // Nothing to refresh to: fall through to the ordinary failure path.
                        onProgress(
                            DownloadState.Error(
                                context.getString(R.string.failed_after_retries, maxAttempts, msg)
                            )
                        )
                        return@withContext
                    }

                    DownloadRetryPolicy.Action.RETRY -> {
                        Log.w(TAG, "[DL-RETRY] attempt $attempt/$maxAttempts failed for ${redact(url)}, will retry. Error: $msg")
                        cleanup()
                    }

                    DownloadRetryPolicy.Action.SUCCEED -> return@withContext
                }
            }
        }

    }

    suspend fun downloadThumbnail(
        url: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit
    ) = withContext(Dispatchers.IO) {
        val isLive = isLiveStream(url)
        val settings = readSettings()
        try {
            val workingDir = getWorkingDir()

            val outputFile = File(outputDir)
            if (!outputFile.exists() && !outputFile.mkdirs()) {
                throw IOException("Failed to create output directory: $outputDir")
            }

            val request = YoutubeDLRequest(MediaUrlNormalizer.normalizeYouTubeShortsUrl(url)).apply {
                addCookies(this, url)
                addOption("--skip-download")
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("-P", workingDir.absolutePath)
                addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                if (!settings.playlist) {
                    addOption("--no-playlist")
                }
                applyPlatformArgs(this, url, isLive)
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
        EngineLock.markUsed()
        var currentFormatId = formatId
        val isLive = isLiveStream(url)
        val platform = detectPlatform(url)
        val settings = readSettings()
        val youtubeVideoId = MediaUrlNormalizer.youtubeVideoId(url)
        val writesDirectly = youtubeVideoId.isNotEmpty() && !isLive
        val maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS
        var attempt = 0
        var formatRefreshes = 0
        Log.i(TAG, "downloadWithThumbnail START url=${redact(url)} formatId=$currentFormatId outputDir=$outputDir title=$title live=$isLive platform=$platform")

        while (attempt < maxAttempts) {
            val waitMs = DownloadRetryPolicy.waitBefore(attempt + 1)
            if (attempt > 0 && waitMs > 0L) {
                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))
                Log.i(TAG, "[DL-RETRY] downloadWithThumbnail attempt=${attempt + 1}/$maxAttempts for ${redact(url)} (waiting ${waitMs}ms)")
                delay(waitMs)
            }
            val localFormatId = currentFormatId
            attempt++
            val usedCachedExtraction = writesDirectly && attempt == 1

            try {
                val outputFile = File(outputDir)
                if (!outputFile.exists() && !outputFile.mkdirs()) {
                    throw IOException(context.getString(R.string.creating_output_dir_failed) + ": $outputDir")
                }
                val workingDir = if (writesDirectly) outputFile else getWorkingDir()

                val request = YoutubeDLRequest(MediaUrlNormalizer.normalizeYouTubeShortsUrl(url)).apply {
                    addCookies(this, url, settings.cookiesEnabled)
                    addOption("--no-mtime")
                    addOption("-f", localFormatId)
                    addOption("--merge-output-format", "mp4")
                    addOption("--audio-quality", "0")
                    addOption("--write-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("-P", workingDir.absolutePath)
                    addOption("-o", OUTPUT_TEMPLATE_DEFAULT)
                    addOption("--continue")
                    addOption("--socket-timeout", if (isLive) "30" else "15")
                    applySettings(this, settings)
                    applyPlatformArgs(this, url, isLive)
                    addSubtitleFlags(this, subtitleLangs, autoCaptionLangs, embedSubtitles, subtitleFormat)

                    if (writesDirectly && attempt == 1) {
                        val saved = YoutubeInfoCache.file(context, youtubeVideoId)
                        if (YoutubeInfoCache.isFresh(saved)) {
                            addOption("--load-info-json", saved.absolutePath)
                            Log.i(TAG, "[DL] reusing the extraction from the format list")
                        }
                    }

                    // Emitted once, from the user's setting and the platform default together — the
                    // duplicate that used to follow it is what made the number in Settings do nothing,
                    // and this is the path the download service actually uses.
                    val cf = EngineArgs.concurrentFragments(
                        userValue = settings.concurrentFragments,
                        platformValue = concurrentFragments(platform, isLive)
                    )
                    addOption("--concurrent-fragments", cf.toString())
                }

                val processId = EngineArgs.processId(url)
                activeDownloads[url] = processId
                val activeInfo = ActiveDownload(processId, url, localFormatId, outputDir, title)
                activeDownloadInfo[url] = activeInfo
                resetSmoothing()

                onProgress(DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING))

                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime < emitIntervalMs) return@execute
                    lastEmitTime = now

                    val monoProgress = monotonicProgress(progress / 100f)
                    val rawLine = line ?: ""
                    val parsed = parseProgressLine(rawLine)
                    val totalBytes = if (parsed != null && parsed.totalBytes > 0) parsed.totalBytes else activeInfo.lastTotalBytes
                    val rawBytes = if (totalBytes > 0) (totalBytes * monoProgress).toLong() else 0L
                    val rawSpeed = parsed?.speedBytesPerSec ?: 0L
                    val smoothSpeed = smoothedSpeed(rawSpeed)
                    val etaSec = if (smoothSpeed > 0 && totalBytes > 0) ((totalBytes - rawBytes) / smoothSpeed) else etaInSeconds

                    activeInfo.lastProgress = monoProgress
                    activeInfo.lastDownloadedBytes = rawBytes
                    activeInfo.lastTotalBytes = totalBytes

                    val phase = when {
                        rawLine.contains("[Merger]") || rawLine.contains("Merging formats") -> DownloadPhase.MERGING
                        rawLine.contains("[ExtractAudio]") -> DownloadPhase.POST_PROCESSING
                        monoProgress > 0f || rawLine.contains("[download]") -> DownloadPhase.DOWNLOADING
                        else -> DownloadPhase.PREPARING
                    }

                    onProgress(
                        DownloadState.Downloading(
                            progress = monoProgress,
                            downloadedBytes = rawBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = smoothSpeed,
                            etaSeconds = etaSec,
                            phase = phase
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
                    val check = MediaFileValidator.validate(
                        File(downloadedFile),
                        if (settings.extractAudio) MediaFileValidator.Kind.AUDIO else MediaFileValidator.Kind.VIDEO
                    )
                    if (check is MediaFileValidator.Outcome.Invalid) {
                        Log.e(TAG, "downloadWithThumbnail produced an invalid file: ${check.reason}")
                        activeDownloads.remove(url)
                        onProgress(DownloadState.Error(context.getString(R.string.download_file_invalid, check.reason)))
                        return@withContext
                    }
                    val fileSize = File(downloadedFile).length()
                    Log.i(TAG, "downloadWithThumbnail VERIFIED ${(check as MediaFileValidator.Outcome.Valid).container} size=${fileSize} bytes")
                    val externalPath = if (writesDirectly) {
                        com.otter.app.util.FileHelper.scanFile(context, downloadedFile)
                        downloadedFile
                    } else {
                        copyToExternal(downloadedFile, outputDir)
                    }
                    if (!writesDirectly) {
                        val thumbFile = findThumbnailFile(workingDir.absolutePath, url)
                        if (thumbFile != null) {
                            copyToExternal(thumbFile, outputDir)
                        }
                    }
                    cleanup(workingDir)
                    Log.i(TAG, "downloadWithThumbnail COMPLETE externalPath=$externalPath")
                    onProgress(DownloadState.Success(externalPath))
                } else {
                    Log.e(TAG, "downloadWithThumbnail claimed success but no file found in $outputDir")
                    onProgress(DownloadState.Error(context.getString(R.string.download_file_missing)))
                }
                return@withContext

                } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message ?: ""
                if (intentionallyCancelled.remove(url) == true) {
                    Log.i(TAG, "downloadWithThumbnail cancelled intentionally for $url (pause/stop)")
                    activeDownloads.remove(url)
                    return@withContext
                }
                activeDownloads.remove(url)

                if (usedCachedExtraction && youtubeVideoId.isNotEmpty()) {
                    YoutubeInfoCache.invalidate(YoutubeInfoCache.file(context, youtubeVideoId))
                }

                when (
                    DownloadRetryPolicy.decide(
                        attempt = attempt,
                        formatRefreshes = formatRefreshes,
                        message = msg,
                        canReExtract = formatParser != null
                    ).action
                ) {
                    DownloadRetryPolicy.Action.REFRESH_FORMAT -> {
                        formatRefreshes++
                        val newFmtId = formatParser?.let { refreshFormatInfo(it, url, localFormatId) }
                        if (newFmtId != null) {
                            currentFormatId = newFmtId
                            cleanup()
                            Log.i(TAG, "[DL-RETRY] downloadWithThumbnail retrying with refreshed format $currentFormatId")
                            continue
                        }
                        onProgress(
                            DownloadState.Error(context.getString(R.string.failed_after_retries, maxAttempts, msg))
                        )
                        return@withContext
                    }

                    DownloadRetryPolicy.Action.RETRY -> {
                        Log.w(TAG, "[DL-RETRY] downloadWithThumbnail attempt $attempt/$maxAttempts failed for ${redact(url)}, will retry. Error: $msg")
                        cleanup()
                    }

                    else -> {
                        Log.e(TAG, "downloadWithThumbnail failed for $url after attempt $attempt: $msg")
                        onProgress(
                            DownloadState.Error(context.getString(R.string.failed_after_retries, maxAttempts, msg))
                        )
                        return@withContext
                    }
                }
            }
        }

    }

    suspend fun downloadImages(
        imageUrls: List<String>,
        baseName: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit,
        refererUrl: String? = null
    ): ImageDownloadSummary = downloadMedia(
        slides = imageUrls.map { MediaSlide(it, isVideo = false) },
        baseName = baseName,
        outputDir = outputDir,
        onProgress = onProgress,
        refererUrl = refererUrl
    )

    /**
     * Downloads the slides of one post - photos **and** video slides - straight from
     * the CDN. Carousel media already carries the signed query string Instagram
     * requires, so a plain GET is all that is needed; sending those raw CDN links
     * through yt-dlp instead cost seconds of engine start-up per slide and failed
     * outright on the video ones.
     *
     * A few slides are fetched at a time and saved as `<base>_<n>.<ext>` in the order
     * they were handed in.
     */
    suspend fun downloadMedia(
        slides: List<MediaSlide>,
        baseName: String,
        outputDir: String,
        onProgress: (DownloadState) -> Unit,
        refererUrl: String? = null
    ): ImageDownloadSummary = withContext(Dispatchers.IO) {
        val items = slides
            .map { MediaSlide(it.url.trim(), it.isVideo) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        if (items.isEmpty()) {
            onProgress(DownloadState.Error(context.getString(R.string.failed_all_images)))
            return@withContext ImageDownloadSummary(emptyList(), 0)
        }

        val directory = File(outputDir)
        if (!directory.exists() && !directory.mkdirs()) {
            onProgress(DownloadState.Error(context.getString(R.string.creating_output_dir_failed)))
            return@withContext ImageDownloadSummary(emptyList(), items.size)
        }

        val safeBase = sanitizeBaseName(baseName)
        val savedByIndex = ConcurrentHashMap<Int, String>()
        val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstFailure = java.util.concurrent.atomic.AtomicReference<String>("")
        val semaphore = Semaphore(minOf(4, items.size))

        items.mapIndexed { index, slide ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val path = downloadSingleMedia(
                            mediaUrl = slide.url,
                            index = index,
                            baseName = safeBase,
                            outputDir = directory,
                            refererUrl = refererUrl,
                            isVideo = slide.isVideo
                        )
                        savedByIndex[index] = path
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Slide ${index + 1}/${items.size} failed: ${e.message}")
                        failedCount.incrementAndGet()
                        firstFailure.compareAndSet("", describeSlideFailure(e, slide))
                    } finally {
                        val completed = completedCount.incrementAndGet()
                        onProgress(
                            DownloadState.Downloading(
                                progress = completed.toFloat() / items.size.toFloat(),
                                phase = DownloadPhase.DOWNLOADING
                            )
                        )
                    }
                }
            }
        }.awaitAll()

        val savedPaths = savedByIndex.toSortedMap().values.toList()
        val failed = failedCount.get()
        val summary = ImageDownloadSummary(
            savedPaths = savedPaths,
            failedCount = failed,
            firstFailure = firstFailure.get()
        )
        if (savedPaths.isEmpty()) {
            val reason = firstFailure.get()
            onProgress(
                DownloadState.Error(
                    if (reason.isBlank()) context.getString(R.string.failed_all_images)
                    else context.getString(R.string.failed_all_images_with_reason, reason)
                )
            )
        } else {
            onProgress(DownloadState.Success(savedPaths.joinToString("\n")))
        }
        summary
    }

    private fun sanitizeBaseName(baseName: String): String = baseName
        .replace(Regex("""[<>:"\\|?*]"""), "_")
        .replace(Regex("""[\x00-\x1f]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim('.', ' ')
        .take(80)
        .ifBlank { "Otter media" }

    /** One slide of a gallery post: a photo or a video, fetched with the referer the CDN expects. */
    private suspend fun downloadSingleMedia(
        mediaUrl: String,
        index: Int,
        baseName: String,
        outputDir: File,
        refererUrl: String?,
        isVideo: Boolean
    ): String {
        val sourceUrl = URL(mediaUrl)
        if (sourceUrl.protocol != "http" && sourceUrl.protocol != "https") {
            throw IOException("Unsupported media URL scheme")
        }
        val referer = refererUrl?.let { runCatching { URL(it) }.getOrNull() }
            ?.takeIf { it.protocol == "http" || it.protocol == "https" }
            ?.toExternalForm()
        val accept = if (isVideo) {
            "video/webm,video/mp4,video/quicktime,video/x-m4v,video/*;q=0.9,*/*;q=0.5"
        } else {
            "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.9,*/*;q=0.5"
        }

        for (attempt in 1..3) {
            val partialFile = File(outputDir, ".otter_${java.util.UUID.randomUUID()}.part")
            var connection: HttpURLConnection? = null
            try {
                val httpConnection = sourceUrl.openConnection() as? HttpURLConnection
                    ?: throw IOException("Unsupported media connection")
                connection = httpConnection
                httpConnection.connectTimeout = 10_000
                httpConnection.readTimeout = 30_000
                httpConnection.instanceFollowRedirects = true
                httpConnection.requestMethod = "GET"
                httpConnection.setRequestProperty("User-Agent", DL_UA)
                httpConnection.setRequestProperty("Accept", accept)
                httpConnection.setRequestProperty("Accept-Encoding", "identity")
                httpConnection.setRequestProperty("Connection", "keep-alive")
                referer?.let { httpConnection.setRequestProperty("Referer", it) }

                val status = httpConnection.responseCode
                if (status !in 200..299) throw ImageHttpException(status)
                val contentType = httpConnection.contentType?.substringBefore(';')
                    ?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()

                httpConnection.inputStream.use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }

                if (partialFile.length() == 0L ||
                    !isLikelyMediaFile(partialFile, contentType, mediaUrl, isVideo)
                ) {
                    throw IOException("The server response was not a ${if (isVideo) "video" else "picture"}")
                }

                val extension = mediaExtension(contentType, mediaUrl, partialFile, isVideo)
                val destination = saveMediaWithUniqueName(partialFile, outputDir, baseName, index, extension)
                com.otter.app.util.FileHelper.scanFile(context, destination.absolutePath)
                return destination.absolutePath
            } catch (e: CancellationException) {
                partialFile.delete()
                throw e
            } catch (e: Exception) {
                partialFile.delete()
                if (attempt >= 3 || !isRetryableImageError(e)) throw e
                delay(500L * attempt)
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException(if (isVideo) "Video download failed" else "Image download failed")
    }

    /**
     * A one-line explanation of why one slide could not be saved. HTTP statuses and the media kind
     * are the two things that tell a user whether to retry, to sign in, or to leave it.
     */
    private fun describeSlideFailure(error: Exception, slide: MediaSlide): String {
        val word = if (slide.isVideo) "video" else "picture"
        return when (error) {
            is ImageHttpException -> when (error.statusCode) {
                403, 401 -> "the $word link had expired or needs a login (HTTP ${error.statusCode})"
                404 -> "the $word is no longer there (HTTP 404)"
                429 -> "the site is rate-limiting this download (HTTP 429)"
                else -> "the site answered HTTP ${error.statusCode}"
            }
            is java.net.SocketTimeoutException -> "the site took too long to answer"
            is java.net.UnknownHostException -> "the host could not be resolved"
            else -> error.message?.take(120).orEmpty().ifBlank { "the $word could not be read" }
        }
    }

    private fun isRetryableImageError(error: Exception): Boolean {
        if (error is ImageHttpException) return error.statusCode == 429 || error.statusCode >= 500
        val message = error.message.orEmpty()
        return listOf("timeout", "timed out", "connection reset", "connection refused", "network", "unexpected end", "eof")
            .any { message.contains(it, ignoreCase = true) } ||
            error is java.net.SocketTimeoutException ||
            error is java.net.UnknownHostException ||
            error is java.io.EOFException
    }

    /** Accepts an image or, for a video slide, an actual video payload. */
    private fun isLikelyMediaFile(
        file: File,
        contentType: String,
        sourceUrl: String,
        isVideo: Boolean
    ): Boolean {
        if (!isVideo) return isLikelyImageFile(file, contentType, sourceUrl)
        if (contentType.startsWith("video/")) return true
        if (sniffVideoExtension(file) != null) return true
        if (contentType.contains("html") || contentType.contains("json") ||
            contentType.startsWith("text/")
        ) return false
        return looksLikeVideoUrl(sourceUrl) && (
            contentType.isBlank() ||
                contentType == "application/octet-stream" ||
                contentType == "binary/octet-stream"
            )
    }

    /** mp4/m4v/webm/mov in the path, ignoring the signed query string. */
    private fun videoExtensionFromUrl(url: String): String? {
        val path = runCatching { URL(url).path }.getOrNull() ?: return null
        val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
            .lowercase(java.util.Locale.ROOT)
        return extension.takeIf { it in setOf("mp4", "m4v", "mov", "webm") }
    }

    private fun looksLikeVideoUrl(url: String): Boolean = videoExtensionFromUrl(url) != null

    /** Reads the container magic: an ISO base media file ("....ftyp") or a Matroska stream. */
    private fun sniffVideoExtension(file: File): String? {
        return runCatching {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") return@runCatching "mp4"
            if (count >= 4 && header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) return@runCatching "webm"
            null
        }.getOrNull()
    }

    private fun isLikelyImageFile(file: File, contentType: String, sourceUrl: String): Boolean {
        if (sniffImageExtension(file) != null) return true
        if (contentType.startsWith("image/")) return true
        if (contentType.contains("html") || contentType.contains("json") || contentType.startsWith("text/")) return false
        return MediaUrlNormalizer.isDirectImageUrl(sourceUrl) &&
            (contentType.isBlank() || contentType == "application/octet-stream" || contentType == "binary/octet-stream")
    }

    private fun mediaExtension(
        contentType: String,
        sourceUrl: String,
        file: File,
        isVideo: Boolean
    ): String {
        if (!isVideo) return imageExtension(contentType, sourceUrl, file)
        val mimeExtension = when (contentType.substringBefore(';').lowercase(java.util.Locale.ROOT)) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "video/x-m4v", "video/m4v" -> "m4v"
            else -> null
        }
        return mimeExtension
            ?: sniffVideoExtension(file)
            ?: videoExtensionFromUrl(sourceUrl)
            ?: "mp4"
    }

    private fun imageExtension(contentType: String, sourceUrl: String, file: File): String {
        val mimeExtension = when (contentType.substringBefore(';').lowercase(java.util.Locale.ROOT)) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png", "image/x-png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/avif" -> "avif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/tiff" -> "tif"
            "image/svg+xml" -> "svg"
            else -> null
        }
        return mimeExtension
            ?: sniffImageExtension(file)
            ?: MediaUrlNormalizer.imageExtensionFromUrl(sourceUrl)?.let {
                if (it == "jpeg") "jpg" else it
            }
            ?: "jpg"
    }

    private fun sniffImageExtension(file: File): String? {
        return runCatching {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return@runCatching "jpg"
            if (count >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return@runCatching "png"
            if (count >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8")) return@runCatching "gif"
            if (count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP") return@runCatching "webp"
            if (count >= 2 && String(header, 0, 2, Charsets.US_ASCII) == "BM") return@runCatching "bmp"
            if (count >= 4 && (
                    (header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == '*'.code.toByte() && header[3] == 0.toByte()) ||
                    (header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() && header[2] == 0.toByte() && header[3] == '*'.code.toByte())
                )) return@runCatching "tif"
            val prefix = String(header, 0, count.coerceAtLeast(0), Charsets.UTF_8).trimStart()
            if (prefix.startsWith("<svg", ignoreCase = true) || prefix.startsWith("<?xml", ignoreCase = true)) return@runCatching "svg"
            null
        }.getOrNull()
    }

    private fun saveMediaWithUniqueName(
        partialFile: File,
        outputDir: File,
        baseName: String,
        index: Int,
        extension: String
    ): File {
        var suffix = 0
        while (suffix < 10_000) {
            val disambiguator = if (suffix == 0) "" else " ($suffix)"
            val destination = File(outputDir, "${baseName}_${index + 1}$disambiguator.$extension")
            if (!destination.createNewFile()) {
                suffix++
                continue
            }
            try {
                partialFile.copyTo(destination, overwrite = true)
                partialFile.delete()
                return destination
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
        throw IOException("Could not create a unique filename")
    }

    private class ImageHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

    fun pauseDownload(url: String): ActiveDownload? {
        Log.i(TAG, "pauseDownload: called for url=$url")
        val info = activeDownloadInfo[url]
        if (info == null) {
            Log.w(TAG, "pauseDownload: no active download found for $url")
            return null
        }
        // Mark as intentionally cancelled
        intentionallyCancelled[url] = true
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
        intentionallyCancelled[url] = true
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
        // .part files are NOT deleted — preserved for potential resume from History
        Log.i(TAG, "stopDownload: complete for $url (.part files preserved)")
    }

    fun cancelDownload(url: String) {
        intentionallyCancelled[url] = true
        stopDownload(url)
    }

    fun getActiveDownload(url: String): ActiveDownload? = activeDownloadInfo[url]

    fun wasIntentionallyCancelled(url: String): Boolean {
        return intentionallyCancelled.containsKey(url)
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

    fun cleanupPartialFile(outputDir: String, resolvedFilename: String) {
        if (resolvedFilename.isBlank()) return
        try {
            val partFile = File(outputDir, "$resolvedFilename.part")
            val ytdlFile = File(outputDir, "$resolvedFilename.ytdl")
            if (partFile.exists()) { partFile.delete(); Log.i(TAG, "[CLEANUP] Deleted .part: ${partFile.absolutePath}") }
            if (ytdlFile.exists()) { ytdlFile.delete(); Log.i(TAG, "[CLEANUP] Deleted .ytdl: ${ytdlFile.absolutePath}") }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupPartialFile error: ${e.message}")
        }
    }

    fun detectResolvedFilename(outputDir: String): String? {
        return try {
            val dir = File(outputDir)
            val partFiles = dir.listFiles { f -> f.name.endsWith(".part") && f.length() > 0 }
            if (partFiles.isNullOrEmpty()) return null
            val latest = partFiles.maxByOrNull { it.lastModified() } ?: return null
            latest.name.removeSuffix(".part").also {
                Log.i(TAG, "[DETECT] Found recent .part file: ${latest.name} -> resolved=$it")
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectResolvedFilename error: ${e.message}")
            null
        }
    }

    fun checkPartFileExists(outputDir: String, resolvedFilename: String): Boolean {
        if (resolvedFilename.isBlank()) return false
        return try {
            val partFile = File(outputDir, "$resolvedFilename.part")
            val exists = partFile.exists() && partFile.length() > 0
            Log.i(TAG, "[CHECK-PART] ${partFile.absolutePath} exists=$exists size=${if (exists) partFile.length() else 0}")
            exists
        } catch (e: Exception) {
            Log.w(TAG, "checkPartFileExists error: ${e.message}")
            false
        }
    }

    private fun findDownloadedFile(outputDir: String, url: String): String? {
        return try {
            val found = MediaFileLocator.findDownloaded(
                File(outputDir),
                MediaUrlNormalizer.youtubeVideoId(url)
            )
            if (found == null) Log.w(TAG, "findDownloadedFile: no media files in $outputDir")
            else Log.i(TAG, "findDownloadedFile: ${found.name}")
            found?.absolutePath
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
