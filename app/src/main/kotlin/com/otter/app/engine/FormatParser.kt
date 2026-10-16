package com.otter.app.engine

import android.content.Context
import com.otter.app.R
import android.util.Log
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.model.AudioTrack
import com.otter.app.data.model.CarouselItem
import com.otter.app.data.model.SubtitleTrack
import com.otter.app.data.model.VideoFormat
import com.otter.app.data.model.VideoInfo
import com.otter.app.util.Languages
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
        /**
         * Hard ceiling on reading one Instagram post before yt-dlp gets its turn. It was 60 s,
         * which meant a post nobody could read kept the screen on "parsing" for a minute before
         * anything was reported; the endpoints themselves finish in seconds, and only the browser
         * render is slow, so 30 s is the point where waiting longer stops paying for itself.
         */
        private const val INSTAGRAM_SCRAPE_BUDGET_MS = 30_000L
        /** Photo-mode links have no video formats, so read their public hydration payload directly. */
        private const val TIKTOK_PHOTO_SCRAPE_BUDGET_MS = 8_000L
        private const val UA = MediaUrlNormalizer.TIKTOK_USER_AGENT
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val engineReady = MutableStateFlow(false)

        /** How long a finished extraction is reused. */
        private const val INFO_CACHE_TTL_MS = 300_000L

        /** A URL without its query string, for logs: signed CDN links are capability tokens. */
        private fun redact(url: String): String = url.substringBefore('?')

        private fun applyPlatformArgs(request: YoutubeDLRequest, url: String) {
            fun hostMatches(vararg domains: String) = domains.any { MediaUrlNormalizer.hostMatches(url, it) }

            val needsUA = hostMatches(
                "tiktok.com", "tiktokv.com",
                "twitter.com", "x.com", "t.co",
                "reddit.com", "redd.it", "twitch.tv", "vimeo.com",
                "soundcloud.com", "bilibili.com", "dailymotion.com", "dai.ly",
                "pinterest.com", "pin.it", "streamable.com", "bandcamp.com", "snapchat.com"
            )
            if (needsUA) request.addOption("--user-agent", UA)

            when {
                hostMatches("tiktok.com", "tiktokv.com") -> {
                    request.addOption("--add-header", "Referer:https://www.tiktok.com/")
                    request.addOption("--extractor-retries", "5")
                    Log.i(TAG, "[PLATFORM-FLAGS] TikTok detected, using public web extraction with browser headers + retry=5")
                }
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

        /**
         * Makes sure the engine is laid out on disk before the first run of a session.
         *
         * This used to start the interpreter to ask it for its version, which cost one to three
         * seconds of Python startup before the user saw anything — and told the app nothing, since
         * the fetch itself reports a broken binary perfectly well. `init` is synchronised, copies
         * the files once and returns at once afterwards, so it is the whole check.
         */
        suspend fun ensureEngineReady(context: Context?) {
            if (engineReady.value || context == null) return
            withContext(Dispatchers.IO) {
                runCatching { YoutubeDL.getInstance().init(context) }
                    .onSuccess {
                        engineReady.value = true
                        Log.i(TAG, "[PREFLIGHT] engine initialised without a version subprocess")
                    }
                    .onFailure { Log.w(TAG, "[PREFLIGHT] engine init failed (non-blocking): ${it.message}") }
            }
        }

        fun resolveTikTokShortUrl(url: String): String {
            val original = url.trim()
            val parsed = runCatching { URL(original) }.getOrNull() ?: return url
            if (!MediaUrlNormalizer.hostMatches(original, "tiktok.com") &&
                !MediaUrlNormalizer.hostMatches(original, "tiktokv.com")
            ) return url
            val host = parsed.host.lowercase()
            val isShortHost = host == "vm.tiktok.com" || host == "vt.tiktok.com"
            val isShortPath = parsed.path.startsWith("/t/", ignoreCase = true)
            if (!isShortHost && !isShortPath) return url

            var current = original
            repeat(5) {
                val connection = runCatching { URL(current).openConnection() as HttpURLConnection }
                    .getOrNull() ?: return current
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 3_500
                    connection.readTimeout = 3_500
                    connection.requestMethod = "GET"
                    connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )
                    connection.setRequestProperty("Range", "bytes=0-0")
                    val status = connection.responseCode
                    val location = connection.getHeaderField("Location")
                    if (status !in 300..399 || location.isNullOrBlank()) return current
                    val next = runCatching { URL(URL(current), location).toExternalForm() }.getOrNull()
                        ?: return current
                    val isTiktokRedirect = MediaUrlNormalizer.hostMatches(next, "tiktok.com") ||
                        MediaUrlNormalizer.hostMatches(next, "tiktokv.com")
                    if (!isTiktokRedirect) return current
                    current = next
                    val path = runCatching { URL(current).path }.getOrDefault("")
                    if (path.contains("/video/") || path.contains("/photo/")) return current
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve TikTok short URL: ${e.message}")
                    return current
                } finally {
                    connection.disconnect()
                }
            }
            return current
        }

        suspend fun warmUpEngine(context: Context) {
            if (engineReady.value) return
            val startTime = System.currentTimeMillis()
            try {
                Log.i(TAG, "[WARMUP] Pre-warming engine start...")
                YoutubeDL.getInstance().init(context)

                // Quick version check to load binary into memory
                val versionRequest = YoutubeDLRequest("").apply { addOption("--version") }
                YoutubeDL.getInstance().execute(versionRequest)
                Log.i(TAG, "[WARMUP] yt-dlp binary loaded")

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

    /**
     * Reads in flight for the same URL, so one paste cannot start two extractions.
     *
     * A paste can reach this method from three places at once — the 300 ms typing debounce, the
     * clipboard listener and the share intent — and each of them used to run its own engine pass.
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<VideoInfo>>>()

    suspend fun fetchVideoInfo(
        url: String,
        onRetryMessage: ((String) -> Unit)? = null,
        onRetryStatus: ((Int, Int) -> Unit)? = null
    ): Result<VideoInfo> = coroutineScope {
        val key = url.trim()
        inFlight[key]?.let { running ->
            Log.i(TAG, "[FETCH] joining the extraction already running for ${redact(key)}")
            return@coroutineScope running.await()
        }
        val startedAt = System.currentTimeMillis()
        val job = async { fetchVideoInfoInner(key, onRetryMessage, onRetryStatus, startedAt) }
        inFlight[key] = job
        try {
            job.await()
        } finally {
            inFlight.remove(key, job)
        }
    }

    // ---- cancellable extraction -------------------------------------------------------------

    private val fetchProcesses = ConcurrentHashMap<String, MutableSet<String>>()

    private fun registerFetchProcess(url: String, processId: String) {
        fetchProcesses.computeIfAbsent(url) {
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
        }.add(processId)
    }

    private fun unregisterFetchProcess(url: String, processId: String) {
        val ids = fetchProcesses[url] ?: return
        ids.remove(processId)
        if (ids.isEmpty()) fetchProcesses.remove(url, ids)
    }

    /**
     * Ends the engine run for [url], if one is in flight.
     *
     * Cancelling the coroutine alone did not stop `yt-dlp`: the child kept working for up to a
     * minute on a video nobody wanted, holding the network and the CPU that the next attempt needs.
     */
    fun cancelFetch(url: String) {
        val ids = fetchProcesses.remove(url) ?: return
        ids.toList().forEach { id ->
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
                .onFailure { Log.w(TAG, "cancelFetch: destroyProcessById(${'$'}id) failed: ${'$'}{it.message}") }
        }
    }

    private suspend fun fetchVideoInfoInner(
        url: String,
        onRetryMessage: ((String) -> Unit)?,
        onRetryStatus: ((Int, Int) -> Unit)?,
        startedAt: Long
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        EngineLock.markUsed()
        Log.i(TAG, "fetchVideoInfo START url=${redact(url)}")
        val resolvedUrl = MediaUrlNormalizer.normalizeYouTubeShortsUrl(resolveTikTokShortUrl(url))
        // Reels and /tv/ links belong here too: they are how Instagram shares a one-video post,
        // and excluding them is what sent those links to an extractor that needs a login.
        val isInstaPost = InstagramMediaParser.isPostUrl(resolvedUrl)

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

        // Instagram used to be excluded from this cache wholesale, so every retry, every
        // back-navigation and every second tap re-read the whole post (up to five endpoints and a
        // browser render). A read that came back complete is safe to reuse for the same short window
        // as any other result; a partial one is not, because it may be missing slides.
        videoInfoCache[url]?.let { cached ->
            val fresh = System.currentTimeMillis() - cached.timestamp < INFO_CACHE_TTL_MS
            val reusable = !isInstaPost || cached.info.isCarouselComplete ||
                cached.info.formats.isNotEmpty()
            if (fresh && reusable) {
                Log.i(TAG, "[FETCH] reusing the extraction from ${System.currentTimeMillis() - cached.timestamp}ms ago")
                return@withContext Result.success(cached.info)
            }
        }

        // TikTok photo-mode links expose an imagePost payload, not video formats.
        // Handle those through the selectable gallery path before yt-dlp reports an
        // empty-format error. The page fetch is bounded and sends no cookies.
        if (TikTokPhotoParser.isPhotoModeUrl(resolvedUrl) && context != null) {
            val photoPost = runCatching {
                withTimeoutOrNull(TIKTOK_PHOTO_SCRAPE_BUDGET_MS) {
                    TikTokPhotoScraper().scrape(resolvedUrl)
                }
            }.onFailure { Log.w(TAG, "TikTok photo-mode page scrape failed", it) }.getOrNull()
            if (photoPost != null && photoPost.imageUrls.isNotEmpty()) {
                val info = createImageGalleryInfo(
                    sourceUrl = url,
                    title = photoPost.title.ifBlank { "TikTok photo post" },
                    extractor = "TikTok",
                    images = photoPost.imageUrls,
                    expectedCount = photoPost.expectedCount,
                    description = photoPost.description
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                Log.i(TAG, "TikTok photo-mode page exposed ${photoPost.imageUrls.size}/${photoPost.expectedCount} stills")
                return@withContext Result.success(info)
            }
        }

        // Nothing Instagram related is allowed to throw out of here: an escaping
        // exception kills the coroutine that owns the fetch, which is what leaves
        // the screen sitting on "parsing" with no result and no error at all.
        var instagramDiagnostic = "not tried"
        var instagramScrape: InstagramImageScraper.ScrapeResult? = null
        if (isInstaPost && context != null) {
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
                Log.i(TAG, "[TIMING] Instagram read ${scraped.imageUrls.size} photo(s) and " +
                    "${scraped.videoUrls.size} video(s) in ${System.currentTimeMillis() - startedAt}ms")
                val info = createImageGalleryInfo(
                    sourceUrl = url,
                    title = instagramTitle(scraped.caption, scraped.shortcode),
                    extractor = "Instagram",
                    images = scraped.imageUrls,
                    videos = scraped.videoUrls,
                    videoPosters = scraped.videoPosters,
                    slides = scraped.orderedSlides(),
                    expectedCount = scraped.expectedCount,
                    description = scraped.caption
                )
                videoInfoCache[url] = CachedInfo(info, System.currentTimeMillis())
                return@withContext Result.success(info)
            }
            Log.i(TAG, "Scraper returned empty ($instagramDiagnostic), falling back to yt-dlp")
        }

        // Pre-flight: only a file-layout check now; a broken binary shows up on the first run.
        if (!engineReady.value) {
            Log.i(TAG, "[PREFLIGHT] Engine not ready yet, running warmup check")
            ensureEngineReady(context)
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
                    addOption("--no-warnings")
                    addOption("--no-check-certificate")
                    // The app owns the retry ladder, so the engine is not allowed to run a private
                    // one underneath it: -R 1 is what stops a two-second connect failure from
                    // turning into a two-minute wait before the user sees anything.
                    EngineArgs.FETCH_QUIET_ARGS.forEach { addOption(it) }
                    addOption("-R", EngineArgs.FETCH_NETWORK_RETRIES.toString())
                    // Sizes in the sheet come from the format list; without this a DASH/HLS
                    // format reports no size at all.
                    addOption("--compat-options", "manifest-filesize-approx")
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
                    val socketTimeout = EngineArgs.socketTimeoutSeconds(baseTimeout, attempt, isLive = false)
                    addOption("--socket-timeout", socketTimeout.toString())
                    Log.i(TAG, "[FETCH-RETRY] attempt=$attempt socketTimeout=${socketTimeout}s")

                    // Platform-specific flags (user-agent, headers, geo-bypass, etc.)
                    applyPlatformArgs(this, url)

                    if (context != null && useSavedCookies(url)) {
                        val cookieFile = File(context.filesDir, "cookies.txt")
                        if (cookieFile.exists() && cookieFile.length() > 0) {
                            addOption("--cookies", cookieFile.absolutePath)
                        }
                    }
                }

                val ytExecStart = System.currentTimeMillis()
                // The engine call is given a process id of our own so that pressing Cancel stops
                // the yt-dlp child instead of leaving it running while the app pretends to be idle.
                val fetchProcessId = EngineArgs.processId(url)
                registerFetchProcess(url, fetchProcessId)
                val ytResponse = try {
                    YoutubeDL.getInstance().execute(request, fetchProcessId)
                } finally {
                    unregisterFetchProcess(url, fetchProcessId)
                }
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

                Log.i(TAG, "[TIMING] engine run ${ytExecTime}ms + json ${System.currentTimeMillis() - parseStart}ms for ${redact(url)} attempt=$attempt")
                val rawFormatCount = rawJson.optJSONArray("formats")?.length() ?: 0
                if (rawFormatCount == 0 && !isKnownExtractorHost(url)) {
                    val pageMedia = discoverGenericPageMedia(url)
                    if (pageMedia != null) {
                        return@withContext pageMedia.also { outcome ->
                            outcome.getOrNull()?.let { videoInfoCache[url] = CachedInfo(it, System.currentTimeMillis()) }
                        }
                    }
                    // Nothing declared a video and nothing was found: the engine's own "no formats"
                    // answer is the honest failure, so it is reported below rather than masked.
                }

                val formats = parseFormats(videoInfo)
                // Both of these used to build their own JSONObject from the same multi-megabyte
                // string the line above had already parsed, on the fetch's critical path.
                val subtitleTracks = parseSubtitleTracks(rawJson)
                val audioTracks = parseAudioTracks(rawJson)
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
                if (context != null) {
                    val ytId = MediaUrlNormalizer.youtubeVideoId(url)
                    if (ytId.isNotEmpty()) {
                        YoutubeInfoCache.keep(context, ytId, ytResponse.out)
                    }
                }
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
                                "${scraped.videoUrls.size} video(s) for Instagram post (engine fallback)")
                            val info = createImageGalleryInfo(
                                sourceUrl = url,
                                title = instagramTitle(scraped.caption, scraped.shortcode),
                                extractor = "Instagram",
                                images = scraped.imageUrls,
                                videos = scraped.videoUrls,
                                videoPosters = scraped.videoPosters,
                                slides = scraped.orderedSlides(),
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
                        val pageMedia = discoverGenericPageMedia(url)
                        if (pageMedia != null) {
                            return@withContext pageMedia.also { outcome ->
                                outcome.getOrNull()?.let {
                                    videoInfoCache[url] = CachedInfo(it, System.currentTimeMillis())
                                }
                            }
                        }
                    } catch (scrapeErr: Exception) {
                        Log.w(TAG, "Generic page media fallback failed", scrapeErr)
                    }
                }
                lastError = e
                Log.w(TAG, "Attempt $attempt/$maxAttempts failed for ${redact(url)}: ${e.message}")
                videoInfoCache.remove(url)
                if (DownloadRetryPolicy.isPermanent(e.message)) break
            }
        }

        Log.e(TAG, "All $maxAttempts attempts failed for $url", lastError)
        videoInfoCache.remove(url)
        Result.failure(lastError ?: Exception("Failed to fetch video info after $maxAttempts attempts"))
    }

    /**
     * Whether the user's saved cookie jar may be handed to the engine for this URL.
     *
     * The jar is only ever sent when the user turned cookies on in Settings — it used to be sent to
     * every request regardless, which contradicts the documented policy and can make the difference
     * between a public page that reads and a session-bound one that answers with a login wall.
     */
    private suspend fun useSavedCookies(url: String): Boolean {
        val context = context ?: return false
        if (!MediaUrlNormalizer.canUseSavedCookies(url)) return false
        return runCatching { UserPreferences(context).getCookiesEnabledOnce() }.getOrDefault(false)
    }

    private fun isInstagramUrl(url: String): Boolean =
        MediaUrlNormalizer.hostMatches(url, "instagram.com") ||
            MediaUrlNormalizer.hostMatches(url, "instagr.am")

    /** A post page as opposed to a profile or a tag page: any of p / reel / tv, share form included. */
    private fun isInstagramPostUrl(url: String): Boolean = InstagramMediaParser.isPostUrl(url)

    /**
     * The fallback for a page no extractor owns.
     *
     * Two rules make this behave like "find the media of this page" rather than "scrape the page":
     * the video the page declares is returned as the primary item and is listed first, and a page
     * that declares a video the app could not open is reported as that failure instead of being
     * answered with its own thumbnails.
     */
    private suspend fun discoverGenericPageMedia(url: String): Result<VideoInfo>? {
        val appContext = context ?: return null
        if (isKnownExtractorHost(url)) return null
        val discovery = runCatching { GenericPageMediaScraper(appContext).discoverImages(url) }
            .onFailure { Log.w(TAG, "Could not inspect page for media: ${it.message}") }
            .getOrNull() ?: return null

        val videos = discovery.videoUrls.filter { MediaClassifier.isDirectlyDownloadable(it) }
        if (discovery.imageUrls.isEmpty() && videos.isEmpty()) {
            // A page that advertises a video (og:video, a <video> element, a manifest link) whose
            // media the app cannot open is a video problem, not an image gallery. Listing its
            // pictures as the result is what made this look like a success that downloaded nothing.
            if (discovery.hasVideoSignals) {
                return Result.failure(
                    IllegalStateException(
                        context?.getString(R.string.error_page_video_unavailable)
                            ?: "This page declares a video that OTTER could not open. " +
                            "It may need a login, may be DRM protected, or may only play in a browser."
                    )
                )
            }
            return null
        }

        val slides = buildList {
            discovery.videoUrls.forEachIndexed { index, url ->
                add(
                    InstagramMediaParser.Result.Slide(
                        url = url,
                        isVideo = true,
                        posterUrl = discovery.videoPosters.getOrNull(index).orEmpty()
                    )
                )
            }
            discovery.imageUrls.forEach { add(InstagramMediaParser.Result.Slide(it, isVideo = false)) }
        }
        return Result.success(
            createImageGalleryInfo(
                sourceUrl = url,
                title = discovery.title.ifBlank { "Website media" },
                extractor = "Website media",
                images = discovery.imageUrls,
                videos = discovery.videoUrls,
                videoPosters = discovery.videoPosters,
                slides = slides
            )
        )
    }

    /** Kept for the callers that only ever wanted the image list. */
    private fun createImageGalleryInfo(
        sourceUrl: String,
        title: String,
        extractor: String,
        images: List<String>,
        videos: List<String> = emptyList(),
        videoPosters: List<String> = emptyList(),
        slides: List<InstagramMediaParser.Result.Slide> = emptyList(),
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
            carouselItems = slides
                .filter { slide -> imageUrls.contains(slide.url) || videoUrls.contains(slide.url) }
                .map { slide ->
                    CarouselItem(
                        url = slide.url,
                        isVideo = slide.isVideo,
                        posterUrl = slide.posterUrl
                    )
                },
            carouselPrimaryIsVideo = slides.firstOrNull()?.isVideo
                ?: (videoUrls.isNotEmpty() && imageUrls.isEmpty()),
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
            "tiktok.com", "tiktokv.com", "facebook.com", "fb.com", "fb.watch",
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

    private fun parseSubtitleTracks(json: JSONObject): List<SubtitleTrack> {
        return try {
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

    private fun parseAudioTracks(json: JSONObject): List<AudioTrack> {
        return try {
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
