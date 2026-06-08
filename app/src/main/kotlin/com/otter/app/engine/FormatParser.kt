package com.otter.app.engine

import android.content.Context
import android.util.Log
import com.otter.app.data.model.AudioTrack
import com.otter.app.data.model.SubtitleTrack
import com.otter.app.data.model.VideoFormat
import com.otter.app.data.model.VideoInfo
import com.otter.app.util.Languages
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class FormatParser(private val context: Context? = null) {

    companion object {
        private const val TAG = "FormatParser"
        private const val OUTPUT_BASENAME = "%(title).200B"
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"

        val engineReady = MutableStateFlow(false)

        private fun applyPlatformArgs(request: YoutubeDLRequest, url: String) {
            val lower = url.lowercase()

            // Common: add user-agent for all platforms except YouTube (handled natively)
            val needsUA = listOf(
                "twitter.com", "x.com", "t.co",
                "reddit.com", "redd.it", "v.redd.it",
                "twitch.tv",
                "vimeo.com",
                "soundcloud.com",
                "bilibili.com",
                "dailymotion.com", "dai.ly",
                "pinterest.com", "pin.it",
                "streamable.com",
                "bandcamp.com",
                "snapchat.com"
            )
            if (needsUA.any { lower.contains(it) }) {
                request.addOption("--user-agent", UA)
            }

            when {
                // Twitter/X
                lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("t.co") -> {
                    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                    request.addOption("--extractor-retries", "5")
                    Log.i(TAG, "[PLATFORM-FLAGS] Twitter/X detected, applying user-agent + en-US header + retry=5")
                }
                // Reddit
                lower.contains("reddit.com") || lower.contains("redd.it") || lower.contains("v.redd.it") -> {
                    request.addOption("--add-header", "Accept:application/json")
                    Log.i(TAG, "[PLATFORM-FLAGS] Reddit detected, applying user-agent + Accept:application/json")
                }
                // Twitch
                lower.contains("twitch.tv") -> {
                    request.addOption("--socket-timeout", "20")
                    Log.i(TAG, "[PLATFORM-FLAGS] Twitch detected, applying user-agent + socket-timeout=20")
                }
                // Vimeo
                lower.contains("vimeo.com") -> {
                    request.addOption("--add-header", "Referer:https://vimeo.com")
                    Log.i(TAG, "[PLATFORM-FLAGS] Vimeo detected, applying user-agent + referer")
                }
                // SoundCloud
                lower.contains("soundcloud.com") -> {
                    Log.i(TAG, "[PLATFORM-FLAGS] SoundCloud detected, applying user-agent")
                }
                // Bilibili
                lower.contains("bilibili.com") -> {
                    request.addOption("--add-header", "Referer:https://www.bilibili.com")
                    request.addOption("--add-header", "Accept-Language:zh-CN,zh;q=0.9,en;q=0.8")
                    request.addOption("--geo-bypass")
                    Log.i(TAG, "[PLATFORM-FLAGS] Bilibili detected, applying user-agent + referer + zh-CN + geo-bypass")
                }
                // Dailymotion
                lower.contains("dailymotion.com") || lower.contains("dai.ly") -> {
                    Log.i(TAG, "[PLATFORM-FLAGS] Dailymotion detected, applying user-agent")
                }
                // Pinterest
                lower.contains("pinterest.com") || lower.contains("pin.it") -> {
                    Log.i(TAG, "[PLATFORM-FLAGS] Pinterest detected, applying user-agent")
                }
                // Streamable
                lower.contains("streamable.com") -> {
                    Log.i(TAG, "[PLATFORM-FLAGS] Streamable detected, applying user-agent")
                }
                // Bandcamp
                lower.contains("bandcamp.com") -> {
                    Log.i(TAG, "[PLATFORM-FLAGS] Bandcamp detected, applying user-agent")
                }
                // Snapchat
                lower.contains("snapchat.com") -> {
                    request.addOption("--add-header", "Referer:https://www.snapchat.com")
                    Log.i(TAG, "[PLATFORM-FLAGS] Snapchat detected, applying user-agent + referer")
                }
            }
        }

        private suspend fun ensureEngineReady() {
            if (engineReady.value) return
            Log.i(TAG, "[PREFLIGHT] Engine not confirmed ready — running --version check")
            try {
                kotlinx.coroutines.withTimeout(3000L) {
                    val pingRequest = YoutubeDLRequest("").apply { addOption("--version") }
                    YoutubeDL.getInstance().execute(pingRequest)
                }
                engineReady.value = true
                Log.i(TAG, "[PREFLIGHT] --version check succeeded, engine now confirmed ready")
            } catch (e: Exception) {
                Log.w(TAG, "[PREFLIGHT] --version check failed (non-blocking): ${e.message}")
            }
        }

        fun resolveTikTokShortUrl(url: String): String {
            if (!url.contains("tiktok.com") && !url.contains("vm.tiktok.com") && !url.contains("vt.tiktok.com")) return url
            val shortPattern = Regex("""https?://(vm|vt)\.tiktok\.com/\S+""", RegexOption.IGNORE_CASE)
            if (!shortPattern.matches(url.trim())) return url
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "HEAD"
                conn.connect()
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrEmpty() && location.contains("tiktok.com")) location else url
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve TikTok short URL: ${e.message}")
                url
            }
        }

        suspend fun warmUpEngine(context: Context) {
            if (engineReady.value) return
            val startTime = System.currentTimeMillis()
            try {
                Log.i(TAG, "[WARMUP] Pre-warming engine start...")
                YoutubeDL.getInstance().init(context)

                // Step 1: Quick version check to load binary
                val versionRequest = YoutubeDLRequest("").apply { addOption("--version") }
                YoutubeDL.getInstance().execute(versionRequest)
                Log.i(TAG, "[WARMUP] yt-dlp binary loaded")

                // Step 2: Simulated YouTube fetch to pre-load extractors (generous timeout)
                try {
                    val warmRequest = YoutubeDLRequest("https://www.youtube.com/watch?v=dQw4w9WgXcQ").apply {
                        addOption("--skip-download")
                        addOption("--no-warnings")
                        addOption("--dump-json")
                        addOption("--no-playlist")
                        addOption("--socket-timeout", "30")
                        addOption("--extractor-retries", "3")
                    }
                    YoutubeDL.getInstance().getInfo(warmRequest)
                } catch (e: Exception) {
                    Log.w(TAG, "[WARMUP] YouTube warmup fetch failed (non-fatal)", e)
                }

                engineReady.value = true
                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "[WARMUP] Engine pre-warmed successfully in ${duration}ms")
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.w(TAG, "[WARMUP] Engine pre-warm failed after ${duration}ms (ignored)", e)
            }
        }
    }

    private val videoInfoCache = ConcurrentHashMap<String, CachedInfo>()

    private data class CachedInfo(
        val info: VideoInfo,
        val timestamp: Long
    )

    suspend fun fetchVideoInfo(
        url: String,
        onRetryMessage: ((String) -> Unit)? = null
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        Log.i(TAG, "fetchVideoInfo START url=$url")
        val resolvedUrl = resolveTikTokShortUrl(url)
        val isInstaCarousel = resolvedUrl.contains("instagram.com/p/", ignoreCase = true)

        if (!isInstaCarousel) {
            videoInfoCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < 300_000L) {
                    return@withContext Result.success(cached.info)
                }
            }
        }

        if (isInstaCarousel && context != null) {
            val scraper = InstagramImageScraper(context)
            val scraped = scraper.scrape(url)
            if (scraped.isNotEmpty()) {
                Log.i(TAG, "Scraper found ${scraped.size} images for Instagram carousel")
                val info = VideoInfo(
                    id = url.hashCode().toString(),
                    title = "Instagram Post",
                    uploader = "",
                    duration = 0L,
                    thumbnailUrl = scraped.first(),
                    originalUrl = url,
                    formats = emptyList(),
                    description = "",
                    extractor = "Instagram",
                    isPlaylist = false,
                    uploadDate = "",
                    viewCount = 0L,
                    likeCount = 0L,
                    tags = emptyList(),
                    hashtags = emptyList(),
                    carouselImages = scraped,
                    isCarousel = true
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                return@withContext Result.success(info)
            }
            Log.i(TAG, "Scraper returned empty, falling back to yt-dlp")
        }

        // Pre-flight: ensure yt-dlp binary is responsive before first attempt
        if (!engineReady.value) {
            Log.i(TAG, "[PREFLIGHT] Engine not ready yet, running warmup check")
            ensureEngineReady()
        }

        val delays = listOf(1500L, 5000L)
        val maxAttempts = 1 + delays.size
        var lastError: Exception? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                val msg = if (attempt == 2) "Retrying\u2026" else "Almost there\u2026"
                onRetryMessage?.invoke(msg)
                delay(delays[attempt - 2])
            }

            try {
                val targetUrl = if (url.contains("tiktok.com") || url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com")) resolvedUrl else url
                val request = YoutubeDLRequest(targetUrl).apply {
                    addOption("--dump-json")
                    addOption("-o", OUTPUT_BASENAME)
                    if (!url.contains("instagram.com")) {
                        addOption("--no-playlist")
                    }
                    addOption("--skip-download")
                    addOption("--no-warnings")
                    addOption("--no-check-certificate")
                    addOption("--no-write-info-json")
                    addOption("--no-write-description")
                    addOption("--no-write-thumbnail")
                    addOption("--extractor-retries", "3")
                    addOption("--flat-playlist")
                    addOption("--ignore-errors")

                    // Progressive socket timeout per attempt
                    val baseTimeout = when {
                        url.contains("instagram.com") -> 15
                        url.contains("facebook.com") || url.contains("fb.watch") -> 15
                        else -> 10
                    }
                    val socketTimeout = (baseTimeout + (attempt - 1) * 10).coerceAtMost(30)
                    addOption("--socket-timeout", socketTimeout.toString())
                    Log.i(TAG, "[FETCH-RETRY] attempt=$attempt socketTimeout=${socketTimeout}s")

                    // Platform-specific flags (user-agent, headers, geo-bypass, etc.)
                    applyPlatformArgs(this, url)

                    if (context != null) {
                        val cookieFile = File(context.filesDir, "cookies.txt")
                        if (cookieFile.exists() && cookieFile.length() > 0) {
                            addOption("--cookies", cookieFile.absolutePath)
                        }
                    }
                }

                val ytExecStart = System.currentTimeMillis()
                val ytResponse = YoutubeDL.getInstance().execute(request, null, null)
                val ytExecTime = System.currentTimeMillis() - ytExecStart
                Log.i(TAG, "[FETCH] yt-dlp execute returned in ${ytExecTime}ms attempt=$attempt")
                val videoInfo = YoutubeDL.objectMapper.readValue(
                    ytResponse.out,
                    com.yausername.youtubedl_android.mapper.VideoInfo::class.java
                )
                val parseStart = System.currentTimeMillis()
                val rawJson = JSONObject(ytResponse.out)
                val playlistCount = rawJson.optInt("playlist_count", 0)
                Log.i(TAG, "[FETCH] JSON parse took ${System.currentTimeMillis() - parseStart}ms")

                val formats = parseFormats(videoInfo)
                val subtitleTracks = parseSubtitleTracks(ytResponse.out)
                val audioTracks = parseAudioTracks(ytResponse.out)
                val rawTags = videoInfo.tags?.filterNotNull() ?: emptyList()
                val hashtags = extractHashtags(videoInfo.description ?: "")
                var carouselImages = extractCarouselImages(videoInfo, ytResponse.out, rawJson, url)

                val responseType = rawJson.optString("_type", "")
                val isInstaPost = url.contains("instagram.com", ignoreCase = true) &&
                        !url.contains("/reel/", ignoreCase = true)

                if (carouselImages.isEmpty() && responseType == "playlist" && isInstaPost && context != null) {
                    try {
                        val scraper = InstagramImageScraper(context)
                        val scraped = scraper.scrape(url)
                        if (scraped.isNotEmpty()) {
                            carouselImages = scraped
                            Log.i(TAG, "WebView scraper found ${scraped.size} images for Instagram carousel")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Instagram WebView scraper fallback failed", e)
                    }
                }

                val isCarousel = carouselImages.isNotEmpty() ||
                        (responseType == "playlist" && isInstaPost)
                val isPlaylist = responseType == "playlist" || isCarousel ||
                        (videoInfo.extractor?.lowercase()?.contains("playlist") == true) ||
                        (videoInfo.extractorKey?.lowercase()?.contains("playlist") == true) ||
                        playlistCount > 0

                val info = VideoInfo(
                    id = videoInfo.id ?: "",
                    title = videoInfo.title ?: "Unknown Title",
                    uploader = videoInfo.uploader ?: "",
                    duration = videoInfo.duration?.toLong() ?: 0L,
                    thumbnailUrl = videoInfo.thumbnail,
                    originalUrl = url,
                    formats = formats,
                    description = videoInfo.description ?: "",
                    extractor = videoInfo.extractor ?: "",
                    isPlaylist = isPlaylist,
                    uploadDate = videoInfo.uploadDate ?: "",
                    viewCount = videoInfo.viewCount?.toLongOrNull() ?: 0L,
                    likeCount = videoInfo.likeCount?.toLongOrNull() ?: 0L,
                    tags = rawTags,
                    hashtags = hashtags,
                    carouselImages = carouselImages,
                    isCarousel = isCarousel,
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                Log.i(TAG, "fetchVideoInfo COMPLETE title=${info.title} formats=${info.formats.size} extractor=${info.extractor}")
                return@withContext Result.success(info)
            } catch (e: Exception) {
                val isInstaNoVideo = url.contains("instagram.com/p/", ignoreCase = true) &&
                        e.message?.contains("No video formats found") == true
                if (isInstaNoVideo && context != null) {
                    try {
                        val scraper = InstagramImageScraper(context)
                        val scraped = scraper.scrape(url)
                        if (scraped.isNotEmpty()) {
                            Log.i(TAG, "Scraper found ${scraped.size} images for Instagram carousel (engine fallback)")
                            val info = VideoInfo(
                                id = url.hashCode().toString(),
                                title = "Instagram Post",
                                uploader = "",
                                duration = 0L,
                                thumbnailUrl = scraped.first(),
                                originalUrl = url,
                                formats = emptyList(),
                                description = "",
                                extractor = "Instagram",
                                isPlaylist = false,
                                uploadDate = "",
                                viewCount = 0L,
                                likeCount = 0L,
                                tags = emptyList(),
                                hashtags = emptyList(),
                                carouselImages = scraped,
                                isCarousel = true
                            )
                            videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                            return@withContext Result.success(info)
                        }
                    } catch (scrapeErr: Exception) {
                        Log.w(TAG, "Instagram scraper also failed", scrapeErr)
                    }
                }
                lastError = e
                Log.w(TAG, "Attempt $attempt/$maxAttempts failed for $url: ${e.message}")
                videoInfoCache.remove(url)
            }
        }

        Log.e(TAG, "All $maxAttempts attempts failed for $url", lastError)
        videoInfoCache.remove(url)
        Result.failure(lastError ?: Exception("Failed to fetch video info after $maxAttempts attempts"))
    }

    fun clearCache() {
        videoInfoCache.clear()
    }

    fun clearCacheForUrl(url: String) {
        videoInfoCache.remove(url)
    }



    private fun extractCarouselImages(
        videoInfo: com.yausername.youtubedl_android.mapper.VideoInfo,
        rawJson: String,
        parsedJson: JSONObject? = null,
        url: String = ""
    ): List<String> {
        val extractor = videoInfo.extractor?.lowercase() ?: ""
        if (!extractor.contains("instagram")) return emptyList()

        val json = parsedJson ?: try { JSONObject(rawJson) } catch (e: Exception) { null } ?: return emptyList()

        val responseType = json.optString("_type", "")
        val entries = json.optJSONArray("entries")

        if (responseType != "playlist") {
            val isVideo = json.optBoolean("is_video", false) || json.has("formats")
            if (isVideo) return emptyList()
            val singleUrl = videoInfo.url?.takeIf { it.isNotEmpty() }
            val singleThumb = videoInfo.thumbnail?.takeIf { it.isNotEmpty() }
            return listOfNotNull(singleUrl, singleThumb).take(1)
        }

        if (entries == null || entries.length() == 0) {
            return emptyList()
        }

        val urls = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i)
            if (entry != null) {
                val entryIsVideo = entry.optBoolean("is_video", false) || entry.has("formats")
                if (entryIsVideo) continue
                entry.optString("url").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
                    ?: entry.optString("thumbnail").takeIf { it.isNotEmpty() }?.let { urls.add(it) }
            }
        }
        return urls.distinct().take(10)
    }

    private fun extractHashtags(text: String): List<String> {
        val regex = Regex("#\\w+")
        return regex.findAll(text).map { it.value }.toList().distinct()
    }

    private fun parseSubtitleTracks(rawJson: String): List<SubtitleTrack> {
        return try {
            val json = JSONObject(rawJson)
            val tracks = mutableListOf<SubtitleTrack>()

            fun parseLanguage(code: String, formats: JSONArray, isAuto: Boolean) {
                if (formats.length() == 0) return
                val first = formats.getJSONObject(0)
                val ext = first.optString("ext", "vtt")
                val url = if (first.has("url")) first.optString("url", "") else ""
                tracks.add(SubtitleTrack(
                    languageCode = code,
                    languageName = Languages.displayName(code).ifEmpty { code },
                    ext = ext,
                    isAutoGenerated = isAuto,
                    url = url.ifEmpty { null }
                ))
            }

            val subtitles = json.optJSONObject("subtitles")
            if (subtitles != null) {
                val keys = subtitles.keys()
                var count = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = subtitles.optJSONArray(key)
                    if (arr != null) { parseLanguage(key, arr, false); count++ }
                }
                Log.i(TAG, "Manual subtitles parsed: $count")
            } else {
                Log.i(TAG, "No subtitles field in JSON")
            }

            val autoCaptions = json.optJSONObject("automatic_captions")
            if (autoCaptions != null) {
                val keys = autoCaptions.keys()
                var count = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (tracks.none { it.languageCode == key }) {
                        val arr = autoCaptions.optJSONArray(key)
                        if (arr != null) { parseLanguage(key, arr, true); count++ }
                    }
                }
                Log.i(TAG, "Auto captions parsed: $count")
            } else {
                Log.i(TAG, "No automatic_captions field in JSON")
            }

            Log.i(TAG, "Total subtitle tracks: ${tracks.size} (manual=${tracks.count { !it.isAutoGenerated }}, auto=${tracks.count { it.isAutoGenerated }})")
            tracks.distinctBy { it.languageCode }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse subtitle tracks", e)
            emptyList()
        }
    }

    private fun parseAudioTracks(rawJson: String): List<AudioTrack> {
        return try {
            val json = JSONObject(rawJson)
            val formats = json.optJSONArray("formats") ?: return emptyList()
            val audioMap = mutableMapOf<String, Pair<JSONObject, Int>>()

            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                val vcodec = fmt.optString("vcodec", "")
                val acodec = fmt.optString("acodec", "")
                val language = fmt.optString("language", "")

                val isAudioOnly = (vcodec == "none" || vcodec.isEmpty()) && acodec.isNotEmpty() && acodec != "none"
                if (!isAudioOnly) continue

                val langCode = if (language.isNotEmpty()) language else "und"
                val abr = fmt.optInt("abr", 0)
                val existing = audioMap[langCode]
                if (existing == null || abr > existing.second) {
                    audioMap[langCode] = fmt to abr
                }
            }

            val totalFormats = formats.length()
            val hasLanguageFormats = audioMap.keys.any { it != "und" }

            val tracks = audioMap.map { (langCode, pair) ->
                val fmt = pair.first
                val note = fmt.optString("format_note", "")
                val isOriginal = langCode == "und" || note.contains("original", ignoreCase = true)
                val isDubbed = note.contains("dub", ignoreCase = true)

                AudioTrack(
                    formatId = fmt.optString("format_id", ""),
                    languageCode = langCode,
                    languageName = Languages.displayName(langCode).ifEmpty { langCode },
                    isOriginal = isOriginal,
                    isDubbed = isDubbed,
                    codec = fmt.optString("acodec", "unknown"),
                    bitrate = fmt.optInt("abr", 0).takeIf { it > 0 },
                    filesize = fmt.optLong("filesize", 0L).takeIf { it > 0L }
                )
            }.filter { it.languageCode != "und" || !hasLanguageFormats }
             .sortedByDescending { if (it.isOriginal) 0 else 1 }

            Log.i(TAG, "Audio tracks parsed: ${tracks.size} languages")
            tracks.forEach { Log.i(TAG, "  - ${it.languageName} (${it.languageCode}) ${it.codec} original=${it.isOriginal} dubbed=${it.isDubbed}") }
            tracks
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse audio tracks", e)
            emptyList()
        }
    }

    private fun parseFormats(response: com.yausername.youtubedl_android.mapper.VideoInfo): List<VideoFormat> {
        val rawFormats = response.formats ?: return listOf(getBestFormatFallback())

        val parsed = rawFormats.mapNotNull { fmt ->
            try {
                val isAudioOnly = (fmt.vcodec == "none" || fmt.vcodec == null) &&
                        fmt.acodec != null && fmt.acodec != "none"
                val hasVideo = fmt.vcodec != null && fmt.vcodec != "none"
                val hasAudio = fmt.acodec != null && fmt.acodec != "none"

                if (!hasVideo && !hasAudio) return@mapNotNull null

                val formatNote = fmt.formatNote ?: ""
                val displayName = buildString {
                    append(fmt.format ?: fmt.formatId ?: "Unknown")
                    if (formatNote.isNotEmpty() && !(fmt.format?.contains(formatNote) == true)) {
                        append(" \u2022 $formatNote")
                    }
                }

                VideoFormat(
                    formatId = fmt.formatId ?: return@mapNotNull null,
                    displayName = displayName,
                    ext = fmt.ext ?: "mp4",
                    width = if (fmt.width != 0) fmt.width else null,
                    height = if (fmt.height != 0) fmt.height else null,
                    fps = if (fmt.fps != 0) fmt.fps.toDouble() else null,
                    tbr = if (fmt.tbr != 0) fmt.tbr.toDouble() else null,
                    isAudioOnly = isAudioOnly,
                    filesize = if (fmt.fileSize != 0L) fmt.fileSize else if (fmt.fileSizeApproximate != 0L) fmt.fileSizeApproximate else null,
                    formatNote = formatNote,
                    vcodec = fmt.vcodec ?: "",
                    acodec = fmt.acodec ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }

        if (parsed.isEmpty()) return listOf(getBestFormatFallback())

        val videoFormats = parsed.filter { !it.isAudioOnly }
            .sortedByDescending { it.height ?: 0 }
        val audioFormats = parsed.filter { it.isAudioOnly }
            .sortedByDescending { it.tbr ?: 0.0 }

        val bestVideo = videoFormats.firstOrNull()
        val bestAudio = audioFormats.firstOrNull()
        val combinedFormats = mutableListOf<VideoFormat>()

        if (bestVideo != null) {
            combinedFormats.add(bestVideo.copy(formatId = "bestvideo", displayName = "Best Video", isAudioOnly = false))
        }
        if (bestAudio != null) {
            combinedFormats.add(bestAudio.copy(formatId = "bestaudio", displayName = "Best Audio", isAudioOnly = true))
        }
        if (bestVideo != null && bestAudio != null) {
            combinedFormats.add(VideoFormat(
                formatId = "bestvideo+bestaudio",
                displayName = "Best Video + Audio (recommended)",
                ext = bestVideo.ext,
                width = bestVideo.width,
                height = bestVideo.height,
                fps = bestVideo.fps,
                tbr = null,
                isAudioOnly = false,
                filesize = null,
                formatNote = "Muxed",
                vcodec = bestVideo.vcodec,
                acodec = bestAudio.acodec
            ))
        }

        combinedFormats.addAll(videoFormats)
        combinedFormats.addAll(audioFormats)

        return combinedFormats
    }

    fun getBestFormatFallback(): VideoFormat {
        return VideoFormat(
            formatId = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
            displayName = "Best Available (recommended)",
            ext = "mp4",
            width = null,
            height = null,
            fps = null,
            tbr = null,
            isAudioOnly = false,
            filesize = null,
            formatNote = "",
            vcodec = "",
            acodec = ""
        )
    }
}
