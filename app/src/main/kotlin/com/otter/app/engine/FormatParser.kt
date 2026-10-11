package com.otter.app.engine

import android.content.Context
import com.otter.app.R
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class FormatParser(private val context: Context? = null) {

    companion object {
        private const val TAG = "FormatParser"
        private const val OUTPUT_BASENAME = "%(title).200B"
        /** Instagram never renders more than 20 slides in one post. */
        private const val MAX_CAROUSEL_SLIDES = 20
        /** Hard ceiling on reading one Instagram post before yt-dlp gets its turn. */
        private const val INSTAGRAM_SCRAPE_BUDGET_MS = 60_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val engineReady = MutableStateFlow(false)

        private fun applyPlatformArgs(request: YoutubeDLRequest, url: String) {
            fun hostMatches(vararg domains: String) = domains.any { MediaUrlNormalizer.hostMatches(url, it) }

            val needsUA = hostMatches(
                "twitter.com", "x.com", "t.co",
                "reddit.com", "redd.it", "twitch.tv", "vimeo.com",
                "soundcloud.com", "bilibili.com", "dailymotion.com", "dai.ly",
                "pinterest.com", "pin.it", "streamable.com", "bandcamp.com", "snapchat.com"
            )
            if (needsUA) request.addOption("--user-agent", UA)

            when {
                hostMatches("twitter.com", "x.com", "t.co") -> {
                    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                    request.addOption("--extractor-retries", "5")
                    Log.i(TAG, "[PLATFORM-FLAGS] Twitter/X detected, applying user-agent + en-US header + retry=5")
                }
                hostMatches("reddit.com", "redd.it") -> {
                    request.addOption("--user-agent", DESKTOP_UA)
                    Log.i(TAG, "[PLATFORM-FLAGS] Reddit detected, applying desktop user-agent")
                }
                hostMatches("twitch.tv") -> {
                    request.addOption("--socket-timeout", "20")
                    Log.i(TAG, "[PLATFORM-FLAGS] Twitch detected, applying user-agent + socket-timeout=20")
                }
                hostMatches("vimeo.com") -> {
                    request.addOption("--add-header", "Referer:https://vimeo.com")
                    Log.i(TAG, "[PLATFORM-FLAGS] Vimeo detected, applying user-agent + referer")
                }
                hostMatches("soundcloud.com") -> Log.i(TAG, "[PLATFORM-FLAGS] SoundCloud detected, applying user-agent")
                hostMatches("bilibili.com") -> {
                    request.addOption("--add-header", "Referer:https://www.bilibili.com")
                    request.addOption("--add-header", "Accept-Language:zh-CN,zh;q=0.9,en;q=0.8")
                    request.addOption("--geo-bypass")
                    Log.i(TAG, "[PLATFORM-FLAGS] Bilibili detected, applying user-agent + referer + zh-CN + geo-bypass")
                }
                hostMatches("dailymotion.com", "dai.ly") -> Log.i(TAG, "[PLATFORM-FLAGS] Dailymotion detected, applying user-agent")
                hostMatches("pinterest.com", "pin.it") -> Log.i(TAG, "[PLATFORM-FLAGS] Pinterest detected, applying user-agent")
                hostMatches("streamable.com") -> Log.i(TAG, "[PLATFORM-FLAGS] Streamable detected, applying user-agent")
                hostMatches("bandcamp.com") -> Log.i(TAG, "[PLATFORM-FLAGS] Bandcamp detected, applying user-agent")
                hostMatches("snapchat.com") -> {
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
            if (!MediaUrlNormalizer.hostMatches(url, "tiktok.com")) return url
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
                if (!location.isNullOrEmpty() && MediaUrlNormalizer.hostMatches(location, "tiktok.com")) location else url
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
        onRetryMessage: ((String) -> Unit)? = null,
        onRetryStatus: ((Int, Int) -> Unit)? = null
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        EngineLock.markUsed()
        Log.i(TAG, "fetchVideoInfo START url=$url")
        val resolvedUrl = MediaUrlNormalizer.normalizeYouTubeShortsUrl(resolveTikTokShortUrl(url))
        val isInstaCarousel = isInstagramPostUrl(resolvedUrl)
        val youtubeVideoId = MediaUrlNormalizer.youtubeVideoId(resolvedUrl)

        if (MediaUrlNormalizer.isDirectImageUrl(resolvedUrl)) {
            val info = createImageGalleryInfo(
                sourceUrl = url,
                title = imageTitleFromUrl(resolvedUrl),
                extractor = "Website images",
                images = listOf(resolvedUrl)
            )
            videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
            return@withContext Result.success(info)
        }

        if (!isInstaCarousel) {
            videoInfoCache[url]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestamp < 300_000L) {
                    return@withContext Result.success(cached.info)
                }
            }
        }

        // Nothing Instagram related is allowed to throw out of here: an escaping
        // exception kills the coroutine that owns the fetch, which is what leaves
        // the screen sitting on "parsing" with no result and no error at all.
        var instagramDiagnostic = "not tried"
        var instagramScrape: InstagramImageScraper.ScrapeResult? = null
        if (isInstaCarousel && context != null) {
            instagramScrape = runCatching {
                withTimeoutOrNull(INSTAGRAM_SCRAPE_BUDGET_MS) {
                    InstagramImageScraper(context).scrapeDetailed(url)
                }
            }.onFailure {
                Log.w(TAG, "Instagram scrape failed", it)
            }.getOrNull()
            val scraped = instagramScrape
            if (scraped != null) {
                instagramDiagnostic = scraped.sources.joinToString(" ").ifBlank { "no endpoints answered" }
            } else {
                instagramDiagnostic = "scraper gave up (timeout or error)"
            }
            if (scraped != null && scraped.mediaCount > 0) {
                Log.i(TAG, "Scraper found ${scraped.imageUrls.size} photo(s) and " +
                    "${scraped.videoUrls.size} video(s) for Instagram carousel")
                val info = createImageGalleryInfo(
                    sourceUrl = url,
                    title = instagramTitle(scraped.caption, scraped.shortcode),
                    extractor = "Instagram",
                    images = scraped.imageUrls,
                    videos = scraped.videoUrls,
                    videoPosters = scraped.videoPosters,
                    expectedCount = scraped.expectedCount,
                    description = scraped.caption
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                return@withContext Result.success(info)
            }
            Log.i(TAG, "Scraper returned empty ($instagramDiagnostic), falling back to yt-dlp")
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
            onRetryStatus?.invoke(attempt, maxAttempts)

            try {
                val targetUrl = resolvedUrl
                val request = YoutubeDLRequest(targetUrl).apply {
                    addOption("--dump-json")
                    addOption("-o", OUTPUT_BASENAME)
                    if (!isInstagramUrl(url)) {
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
                        isInstagramUrl(url) -> 15
                        MediaUrlNormalizer.hostMatches(url, "facebook.com") ||
                            MediaUrlNormalizer.hostMatches(url, "fb.watch") -> 15
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
                if (youtubeVideoId.isNotEmpty() && context != null) {
                    YoutubeInfoCache.keep(context, youtubeVideoId, ytResponse.out)
                }
                val videoInfo = YoutubeDL.objectMapper.readValue(
                    ytResponse.out,
                    com.yausername.youtubedl_android.mapper.VideoInfo::class.java
                )
                val parseStart = System.currentTimeMillis()
                val rawJson = JSONObject(ytResponse.out)
                val playlistCount = rawJson.optInt("playlist_count", 0)
                Log.i(TAG, "[FETCH] JSON parse took ${System.currentTimeMillis() - parseStart}ms")

                val rawFormatCount = rawJson.optJSONArray("formats")?.length() ?: 0
                if (rawFormatCount == 0 && !isKnownExtractorHost(url)) {
                    val pageImages = discoverGenericPageImageInfo(url)
                    if (pageImages != null) {
                        videoInfoCache[url] = CachedInfo(pageImages, System.currentTimeMillis())
                        return@withContext Result.success(pageImages)
                    }
                }

                val formats = parseFormats(videoInfo)
                val subtitleTracks = parseSubtitleTracks(ytResponse.out)
                val audioTracks = parseAudioTracks(ytResponse.out)
                val rawTags = videoInfo.tags?.filterNotNull() ?: emptyList()
                val hashtags = HashtagExtractor.extract(videoInfo.description ?: "")
                var carouselMedia = extractCarouselMedia(videoInfo, ytResponse.out, rawJson, url)
                var carouselImages = carouselMedia.photos
                var carouselVideos = carouselMedia.videos
                var carouselExpected = carouselMedia.expectedCount

                val responseType = rawJson.optString("_type", "")
                val isInstaPost = isInstagramUrl(url) &&
                        !runCatching { URL(url).path.lowercase().contains("/reel/") }.getOrDefault(false)

                if (carouselImages.isEmpty() && carouselVideos.isEmpty() &&
                    responseType == "playlist" && isInstaPost && context != null
                ) {
                    try {
                        val scraped = instagramScrape
                            ?: runCatching {
                                withTimeoutOrNull(INSTAGRAM_SCRAPE_BUDGET_MS) {
                                    InstagramImageScraper(context).scrapeDetailed(url)
                                }
                            }.getOrNull().also { instagramScrape = it }
                        if (instagramDiagnostic == "not tried") {
                            instagramDiagnostic = if (scraped == null) {
                                "scraper gave up (timeout or error)"
                            } else {
                                scraped.sources.joinToString(" ").ifBlank { "no endpoints answered" }
                            }
                        }
                        if (scraped != null && scraped.mediaCount > 0) {
                            carouselImages = scraped.imageUrls
                            carouselVideos = scraped.videoUrls
                            carouselExpected = scraped.expectedCount
                            carouselMedia = carouselMedia.copy(
                                photos = carouselImages,
                                videos = carouselVideos,
                                posters = scraped.videoPosters,
                                expectedCount = scraped.expectedCount
                            )
                            Log.i(TAG, "Scraper found ${scraped.imageUrls.size} photo(s) and " +
                                "${scraped.videoUrls.size} video(s) for Instagram carousel")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Instagram scraper fallback failed", e)
                    }
                }

                // An Instagram playlist we could not break into slides but that yt-dlp
                // did give formats for is better served by the format sheet than by an
                // empty picker nobody can download from.
                val hasSlides = carouselImages.isNotEmpty() || carouselVideos.isNotEmpty()
                val isCarousel = hasSlides ||
                        (responseType == "playlist" && isInstaPost && formats.isEmpty())

                if (isCarousel && !hasSlides) {
                    // Nothing to choose from: the picker would open empty and the
                    // download button would never appear. Say so, and carry the
                    // per-endpoint counts with it so a report can be acted on.
                    val ytSummary = "yt-dlp: $responseType/" +
                        "${rawJson.optJSONArray("entries")?.length() ?: 0} entries/" +
                        "${formats.size} formats"
                    Log.w(TAG, "Instagram post exposed no readable slides: $url | " +
                        "$instagramDiagnostic | $ytSummary")
                    val detail = "$instagramDiagnostic · $ytSummary"
                    val message = context?.getString(R.string.error_instagram_no_slides, detail)
                        ?: "Instagram returned no readable slides for this post ($detail)"
                    return@withContext Result.failure(IllegalStateException(message))
                }
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
                    carouselVideos = carouselVideos,
                    carouselVideoPosters = carouselMedia.posters,
                    carouselExpectedCount = if (carouselExpected > 0) carouselExpected
                        else carouselImages.size + carouselVideos.size,
                    isCarousel = isCarousel,
                    subtitleTracks = subtitleTracks,
                    audioTracks = audioTracks
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                Log.i(TAG, "fetchVideoInfo COMPLETE title=${info.title} formats=${info.formats.size} extractor=${info.extractor}")
                return@withContext Result.success(info)
            } catch (e: Exception) {
                val isInstaNoVideo = isInstagramPostUrl(url) &&
                        e.message?.contains("No video formats found") == true
                if (isInstaNoVideo && context != null) {
                    try {
                        val scraped = instagramScrape
                            ?: runCatching {
                                withTimeoutOrNull(INSTAGRAM_SCRAPE_BUDGET_MS) {
                                    InstagramImageScraper(context).scrapeDetailed(url)
                                }
                            }.getOrNull().also { instagramScrape = it }
                        if (scraped != null && scraped.mediaCount > 0) {
                            Log.i(TAG, "Scraper found ${scraped.imageUrls.size} photo(s) and " +
                                "${scraped.videoUrls.size} video(s) for Instagram carousel (engine fallback)")
                            val info = createImageGalleryInfo(
                                sourceUrl = url,
                                title = instagramTitle(scraped.caption, scraped.shortcode),
                                extractor = "Instagram",
                                images = scraped.imageUrls,
                                videos = scraped.videoUrls,
                                videoPosters = scraped.videoPosters,
                                expectedCount = scraped.expectedCount,
                                description = scraped.caption
                            )
                            videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                            return@withContext Result.success(info)
                        }
                    } catch (scrapeErr: Exception) {
                        Log.w(TAG, "Instagram scraper also failed", scrapeErr)
                    }
                }
                if (attempt == 1 && !isKnownExtractorHost(url)) {
                    try {
                        val pageImages = discoverGenericPageImageInfo(url)
                        if (pageImages != null) {
                            videoInfoCache[url] = CachedInfo(pageImages, System.currentTimeMillis())
                            return@withContext Result.success(pageImages)
                        }
                    } catch (scrapeErr: Exception) {
                        Log.w(TAG, "Generic page image fallback failed", scrapeErr)
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

    private fun isInstagramUrl(url: String): Boolean =
        MediaUrlNormalizer.hostMatches(url, "instagram.com")

    private fun isInstagramPostUrl(url: String): Boolean {
        if (!isInstagramUrl(url)) return false
        val path = runCatching { URL(url).path.lowercase() }.getOrNull() ?: return false
        return path.contains("/p/") && !path.contains("/reel/")
    }

    private suspend fun discoverGenericPageImageInfo(url: String): VideoInfo? {
        val appContext = context ?: return null
        if (isKnownExtractorHost(url)) return null
        val discovery = GenericPageMediaScraper(appContext).discoverImages(url) ?: return null
        if (discovery.imageUrls.isEmpty()) return null
        return createImageGalleryInfo(
            sourceUrl = url,
            title = discovery.title.ifBlank { "Website images" },
            extractor = "Website images",
            images = discovery.imageUrls
        )
    }

    private fun createImageGalleryInfo(
        sourceUrl: String,
        title: String,
        extractor: String,
        images: List<String>,
        videos: List<String> = emptyList(),
        videoPosters: List<String> = emptyList(),
        expectedCount: Int = 0,
        description: String = ""
    ): VideoInfo {
        val imageUrls = images.filter { it.isNotBlank() }.distinct().take(40)
        val videoUrls = videos.filter { it.isNotBlank() }.distinct().take(40)
        val posters = videoUrls.mapIndexed { index, url ->
            videoPosters.getOrNull(videos.indexOf(url)).orEmpty().takeIf { it.isNotBlank() }
                ?: videoPosters.getOrNull(index).orEmpty()
        }
        val readCount = imageUrls.size + videoUrls.size
        return VideoInfo(
            id = sourceUrl.hashCode().toString(),
            title = title.ifBlank { imageTitleFromUrl(sourceUrl) },
            uploader = "",
            duration = 0L,
            thumbnailUrl = imageUrls.firstOrNull() ?: posters.firstOrNull { it.isNotBlank() },
            originalUrl = sourceUrl,
            formats = emptyList(),
            description = description,
            extractor = extractor,
            isPlaylist = false,
            hashtags = HashtagExtractor.extract(description),
            carouselImages = imageUrls,
            carouselVideos = videoUrls,
            carouselVideoPosters = posters,
            carouselExpectedCount = if (expectedCount > 0) expectedCount else readCount,
            isCarousel = readCount > 0
        )
    }

    /** Instagram posts have no real title: use the first caption line when there is one. */
    private fun instagramTitle(caption: String, shortcode: String): String {
        val firstLine = caption.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val cleaned = firstLine.replace(Regex("#\\w+"), "").trim()
        return when {
            cleaned.isNotBlank() -> cleaned.take(80)
            shortcode.isNotBlank() -> "Instagram post $shortcode"
            else -> "Instagram Post"
        }
    }

    private fun imageTitleFromUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            parsed.path.substringAfterLast('/').substringBeforeLast('.')
                .replace(Regex("[-_]+"), " ")
                .trim()
                .take(80)
                .ifBlank { parsed.host.removePrefix("www.") }
        }.getOrDefault("Image")
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return false
        val domains = setOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "instagram.com", "instagr.am",
            "tiktok.com", "facebook.com", "fb.com", "fb.watch",
            "twitter.com", "x.com", "t.co", "reddit.com", "redd.it",
            "twitch.tv", "vimeo.com", "bilibili.com", "dailymotion.com", "dai.ly",
            "pinterest.com", "pin.it", "streamable.com", "bandcamp.com",
            "soundcloud.com", "snapchat.com"
        )
        return domains.any { host == it || host.endsWith(".$it") }
    }

    fun clearCache() {
        videoInfoCache.clear()
    }

    fun clearCacheForUrl(url: String) {
        videoInfoCache.remove(url)
    }



    /** The slides of a carousel: photos for the image path, videos for the video path. */
    private data class CarouselMedia(
        val photos: List<String>,
        val videos: List<String> = emptyList(),
        val posters: List<String> = emptyList(),
        val expectedCount: Int = 0
    )

    private fun extractCarouselMedia(
        videoInfo: com.yausername.youtubedl_android.mapper.VideoInfo,
        rawJson: String,
        parsedJson: JSONObject? = null,
        url: String = ""
    ): CarouselMedia {
        val extractor = videoInfo.extractor?.lowercase() ?: ""
        if (!extractor.contains("instagram")) return CarouselMedia(emptyList())

        val json = parsedJson ?: try { JSONObject(rawJson) } catch (e: Exception) { null }
            ?: return CarouselMedia(emptyList())

        val responseType = json.optString("_type", "")
        val entries = json.optJSONArray("entries")

        if (responseType != "playlist") {
            val isVideo = json.optBoolean("is_video", false) || json.has("formats")
            if (isVideo) return CarouselMedia(emptyList())
            val singleUrl = videoInfo.url?.takeIf { it.isNotEmpty() }
            val singleThumb = videoInfo.thumbnail?.takeIf { it.isNotEmpty() }
            return CarouselMedia(listOfNotNull(singleUrl, singleThumb).take(1))
        }

        if (entries == null || entries.length() == 0) {
            return CarouselMedia(emptyList())
        }

        val photos = mutableListOf<String>()
        val videos = mutableListOf<String>()
        for (i in 0 until minOf(entries.length(), MAX_CAROUSEL_SLIDES)) {
            val entry = entries.optJSONObject(i) ?: continue
            val entryIsVideo = entry.optBoolean("is_video", false) || entry.has("formats")
            if (entryIsVideo) {
                // Video slides go through the video path: any URL yt-dlp can extract works.
                val candidate = entry.optString("url").takeIf { it.isNotEmpty() }
                    ?: entry.optString("webpage_url").takeIf { it.isNotEmpty() }
                if (candidate != null && candidate != url) videos.add(candidate)
                continue
            }
            entry.optString("url").takeIf { it.isNotEmpty() }?.let { photos.add(it) }
                ?: entry.optString("thumbnail").takeIf { it.isNotEmpty() }?.let { photos.add(it) }
        }
        return CarouselMedia(
            photos = photos.distinct().take(MAX_CAROUSEL_SLIDES),
            videos = videos.distinct().take(MAX_CAROUSEL_SLIDES),
            expectedCount = entries.length()
        )
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
