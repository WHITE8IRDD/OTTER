package com.otter.app.ui.screens.home

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.engine.FormatSpec
import com.otter.app.engine.FetchUserMessage
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadPhase
import com.otter.app.data.model.DownloadState
import com.otter.app.data.model.VideoFormat
import com.otter.app.data.model.VideoInfo
import com.otter.app.data.model.AudioTrack
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.engine.ImageDownloadSummary
import com.otter.app.engine.MediaSlide
import com.otter.app.service.ActiveDownloadStateHolder
import com.otter.app.service.DownloadService
import com.otter.app.service.DownloadStateHolder
import com.otter.app.R
import com.otter.app.util.FileHelper
import com.otter.app.util.isValidUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(application)
    private val preferences = UserPreferences(application)

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _showFormatSheet = MutableStateFlow(false)
    val showFormatSheet: StateFlow<Boolean> = _showFormatSheet.asStateFlow()

    private val _currentVideoInfo = MutableStateFlow<VideoInfo?>(null)
    val currentVideoInfo: StateFlow<VideoInfo?> = _currentVideoInfo.asStateFlow()

    private val _alsoSaveThumbnail = MutableStateFlow(false)
    val alsoSaveThumbnail: StateFlow<Boolean> = _alsoSaveThumbnail.asStateFlow()

    private val _recentUrls = MutableStateFlow<List<String>>(emptyList())
    val recentUrls: StateFlow<List<String>> = _recentUrls.asStateFlow()

    private val _autoDownload = MutableStateFlow(false)
    val autoDownload: StateFlow<Boolean> = _autoDownload.asStateFlow()

    private val _fetchingMessage = MutableStateFlow("")
    val fetchingMessage: StateFlow<String> = _fetchingMessage.asStateFlow()

    private val _retryStatus = MutableStateFlow(RetryStatus())
    val retryStatus: StateFlow<RetryStatus> = _retryStatus.asStateFlow()

    /**
     * Which items of a gallery post are chosen, as indexes into `VideoInfo.carouselMedia`.
     *
     * One set over one ordered list, rather than one set for photos and one for videos: a post that
     * alternates pictures and clips is one list of items, and a user must be able to take the third
     * picture and the one video and nothing else.
     */
    private val _carouselSelection = MutableStateFlow<Set<Int>>(emptySet())
    val carouselSelection: StateFlow<Set<Int>> = _carouselSelection.asStateFlow()

    private val _showThumbnailOnly = MutableStateFlow(false)
    val showThumbnailOnly: StateFlow<Boolean> = _showThumbnailOnly.asStateFlow()

    private val _selectedSubtitleLangs = MutableStateFlow<Set<String>>(emptySet())
    val selectedSubtitleLangs: StateFlow<Set<String>> = _selectedSubtitleLangs.asStateFlow()

    private val _selectedAutoCaptionLangs = MutableStateFlow<Set<String>>(emptySet())
    val selectedAutoCaptionLangs: StateFlow<Set<String>> = _selectedAutoCaptionLangs.asStateFlow()

    private val _embedSubtitles = MutableStateFlow(false)
    val embedSubtitles: StateFlow<Boolean> = _embedSubtitles.asStateFlow()

    private val _subtitleFormat = MutableStateFlow("srt")
    val subtitleFormat: StateFlow<String> = _subtitleFormat.asStateFlow()

    private val _selectedAudioTrackFormatId = MutableStateFlow<String?>(null)
    val selectedAudioTrackFormatId: StateFlow<String?> = _selectedAudioTrackFormatId.asStateFlow()

    private val _selectedAudioTrack = MutableStateFlow<AudioTrack?>(null)
    val selectedAudioTrack: StateFlow<AudioTrack?> = _selectedAudioTrack.asStateFlow()

    private var fetchJob: Job? = null
    private var debounceJob: Job? = null
    private var lastProgressUpdate = 0L

    // Observe shared active download state (mirrors from History-triggered downloads)
    val activeDownloadState: StateFlow<DownloadState> = ActiveDownloadStateHolder.activeDownloadState
    val activeDownloadTitle: StateFlow<String?> = ActiveDownloadStateHolder.activeDownloadTitle
    val activeDownloadUrl: StateFlow<String?> = ActiveDownloadStateHolder.activeDownloadUrl

    // Combined: show active download if present, otherwise local fetch/download state
    val displayState: StateFlow<DownloadState> = combine(
        activeDownloadState, _downloadState
    ) { active, local ->
        when {
            active !is DownloadState.Idle -> active
            else -> local
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadState.Idle)

    val downloadPath: StateFlow<String> = repository.downloadPath
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        _fetchingMessage.value = getApplication<Application>().getString(R.string.fetching_video_info)
        viewModelScope.launch {
            preferences.autoDownload.collect { auto ->
                _autoDownload.value = auto
            }
        }
        viewModelScope.launch { loadRecentUrls() }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoDownload(enabled)
        }
    }

    private suspend fun loadRecentUrls() {
        val urls = preferences.getRecentUrls()
        _recentUrls.value = urls
    }

    private fun addRecentUrl(url: String) {
        val current = _recentUrls.value.toMutableList()
        current.remove(url)
        current.add(0, url)
        if (current.size > 5) current.removeAt(current.lastIndex)
        _recentUrls.value = current
        viewModelScope.launch {
            preferences.setRecentUrls(current)
        }
    }

    fun onUrlChanged(url: String) {
        _urlInput.value = url
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300)
            if (_urlInput.value.trim().isNotEmpty() && _urlInput.value.trim().isValidUrl()) {
                fetchVideoInfo()
            }
        }
    }

    fun clearUrlInput() {
        _urlInput.value = ""
        _downloadState.value = DownloadState.Idle
        _currentVideoInfo.value = null
    }

    fun selectRecentUrl(url: String) {
        _urlInput.value = url
        fetchVideoInfo()
    }

    fun onPasteClicked(text: String?) {
        if (text != null) {
            debounceJob?.cancel()
            _urlInput.value = text
            fetchVideoInfo()
        }
    }

    fun setSharedUrl(url: String, onConsumed: () -> Unit = {}) {
        _urlInput.value = url
        fetchVideoInfo(onConsumed = onConsumed)
    }

    fun toggleAlsoSaveThumbnail() {
        _alsoSaveThumbnail.value = !_alsoSaveThumbnail.value
    }

    fun toggleSubtitleLang(code: String) {
        val current = _selectedSubtitleLangs.value.toMutableSet()
        if (current.contains(code)) current.remove(code) else current.add(code)
        _selectedSubtitleLangs.value = current
    }

    fun toggleAutoCaptionLang(code: String) {
        val current = _selectedAutoCaptionLangs.value.toMutableSet()
        if (current.contains(code)) current.remove(code) else current.add(code)
        _selectedAutoCaptionLangs.value = current
    }

    fun setEmbedSubtitles(enabled: Boolean) {
        _embedSubtitles.value = enabled
    }

    fun setSubtitleFormat(fmt: String) {
        _subtitleFormat.value = fmt
    }

    fun selectAudioTrack(track: AudioTrack?) {
        _selectedAudioTrackFormatId.value = track?.formatId
        _selectedAudioTrack.value = track
    }

    fun suggestDefaultAudioTrack(audioTracks: List<AudioTrack>) {
        if (audioTracks.size <= 1 || _selectedAudioTrackFormatId.value != null) return
        val deviceLang = java.util.Locale.getDefault().language
        val track = audioTracks.find { it.languageCode == deviceLang }
            ?: audioTracks.find { it.isOriginal }
            ?: audioTracks.firstOrNull()
        if (track != null) {
            _selectedAudioTrackFormatId.value = track.formatId
            _selectedAudioTrack.value = track
        }
    }

    fun toggleCarouselIndex(index: Int) {
        val current = _carouselSelection.value.toMutableSet()
        if (!current.add(index)) current.remove(index)
        _carouselSelection.value = current
    }

    /** Selects only one kind, which is what "just the videos of this post" means. */
    fun selectKindOfCarousel(isVideo: Boolean) {
        val items = _currentVideoInfo.value?.carouselMedia ?: return
        _carouselSelection.value = items.indices.filter { items[it].isVideo == isVideo }.toSet()
    }


    fun selectAllCarousel() {
        val total = _currentVideoInfo.value?.carouselMedia?.size ?: 0
        _carouselSelection.value = (0 until total).toSet()
    }

    fun deselectAllCarousel() {
        _carouselSelection.value = emptySet()
    }

    /**
     * Downloads the selected slides of one post. Photos and video slides travel the
     * same path — a plain GET against the CDN, which already accepted the signed URL —
     * so a mixed post is saved in one pass instead of picture slides first and videos
     * through a separate engine run that never managed to fetch them.
     */
    fun downloadCarouselSelected() {
        val info = _currentVideoInfo.value ?: return
        // Sorted by index so the files are numbered in the order the post listed them.
        val slides = _carouselSelection.value.sorted()
            .mapNotNull { index -> info.carouselMedia.getOrNull(index) }
            .map { item -> MediaSlide(item.url, isVideo = item.isVideo) }
        if (slides.isEmpty()) return

        _showFormatSheet.value = false

        viewModelScope.launch {
            val outputDir = repository.downloadPath.first()
            _downloadState.value = DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING)

            val summary = try {
                repository.downloadMedia(
                    slides = slides,
                    baseName = info.title,
                    outputDir = outputDir,
                    refererUrl = info.originalUrl,
                    onProgress = { state -> _downloadState.value = state }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("HomeViewModel", "Carousel download failed", e)
                ImageDownloadSummary(emptyList(), slides.size, e.message.orEmpty())
            }

            if (summary.savedCount > 0) {
                try {
                    saveCarouselRecord(info, summary.savedPaths)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Could not store the history entry", e)
                }
            }

            val failedCount = slides.size - summary.savedCount
            val toastMsg = when {
                // The first failure reason is shown rather than a bare count: "nothing saved" is
                // not actionable, "the link answered 403 (expired)" is.
                summary.savedCount == 0 -> getApplication<Application>().getString(
                    if (summary.firstFailure.isBlank()) R.string.media_saved_none
                    else R.string.media_saved_none_reason, summary.firstFailure
                )
                failedCount > 0 -> getApplication<Application>()
                    .getString(R.string.media_saved_partial, summary.savedCount, failedCount)
                summary.savedCount == 1 -> getApplication<Application>()
                    .getString(R.string.image_saved)
                else -> getApplication<Application>()
                    .getString(R.string.media_saved, summary.savedCount)
            }
            _downloadState.value = DownloadState.Idle
            Toast.makeText(getApplication(), toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    /** One History row for the saved slides of a post, description and hashtags included. */
    private suspend fun saveCarouselRecord(info: VideoInfo, savedPaths: List<String>) {
        val firstPath = savedPaths.firstOrNull() ?: return
        val totalSize = withContext(Dispatchers.IO) {
            savedPaths.sumOf { path -> FileHelper.getFileSizeBytes(path) }
        }
        val extension = firstPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val item = DownloadItem(
            url = info.originalUrl,
            title = info.title,
            uploader = info.uploader,
            thumbnailUrl = info.thumbnailUrl
                ?: info.carouselImages.firstOrNull()
                ?: info.carouselVideoPosters.firstOrNull { it.isNotBlank() },
            filePath = firstPath,
            fileSize = totalSize,
            duration = info.duration,
            formatId = "",
            ext = extension,
            isAudioOnly = false,
            description = info.description.orEmpty(),
            hashtags = info.hashtags
        )
        repository.saveDownloadItem(item)
    }

    fun fetchVideoInfo(onConsumed: () -> Unit = {}) {
        val url = _urlInput.value.trim()
        Log.d("HomeViewModel", "[RETRY-TAPPED] user requested retry/fetch for ${url.take(60)}")
        if (!url.isValidUrl()) {
            _downloadState.value = DownloadState.Error(getApplication<Application>().getString(R.string.enter_valid_url))
            return
        }

        fetchJob?.cancel()
        val initialMsg = if (url.contains("instagram.com")) getApplication<Application>().getString(R.string.fetching_post) else getApplication<Application>().getString(R.string.fetching_video_info)
        _fetchingMessage.value = initialMsg
        _downloadState.value = DownloadState.FetchingInfo(initialMsg)
        _currentVideoInfo.value = null
        _showFormatSheet.value = false
        _retryStatus.value = RetryStatus(attempt = 1, maxAttempts = 3, isRetrying = false)

        fetchJob = viewModelScope.launch {
            val result = repository.fetchVideoInfo(
                url,
                onRetryMessage = { msg ->
                    _fetchingMessage.value = msg
                    _downloadState.value = DownloadState.FetchingInfo(msg)
                },
                onRetryStatus = { attempt, maxAttempts ->
                    _retryStatus.value = RetryStatus(attempt = attempt, maxAttempts = maxAttempts, isRetrying = attempt > 1)
                    if (attempt > 1) {
                        val retryMsg = getApplication<Application>().getString(R.string.fetching_attempt, attempt, maxAttempts)
                        _fetchingMessage.value = retryMsg
                        _downloadState.value = DownloadState.FetchingInfo(retryMsg)
                    }
                }
            )
            result.fold(
                onSuccess = { info ->
                    _currentVideoInfo.value = info
                    addRecentUrl(url)
                    suggestDefaultAudioTrack(info.audioTracks)
                    if (info.isCarousel) {
                        selectAllCarousel()
                        _showThumbnailOnly.value = false
                        _downloadState.value = DownloadState.InfoReady(info)
                        // "Download everything in this post" is what the auto-download switch means,
                        // and a shared gallery link already says so by being shared: take the user
                        // straight to the download instead of opening a picker behind a progress bar.
                        if (_autoDownload.value) {
                            _showFormatSheet.value = false
                            downloadCarouselSelected()
                        } else {
                            _showFormatSheet.value = true
                        }
                    } else if (info.isPlaylist) {
                        _downloadState.value = DownloadState.PlaylistDetected(info, info.playlistCount)
                        _showFormatSheet.value = false
                    } else {
                        _downloadState.value = DownloadState.InfoReady(info)
                        _showFormatSheet.value = true
                    }
                    if (!info.isCarousel && !info.isPlaylist && _autoDownload.value) {
                        downloadBestFormat(info, url)
                    }
                    onConsumed()
                },
                onFailure = { error ->
                    _showFormatSheet.value = false
                    val msg = error.message ?: getApplication<Application>().getString(R.string.failed_fetch_video)
                    // A message this app composed after diagnosing the failure is already the
                    // explanation; classifying it would replace a specific reason with a hint.
                    val friendly = if (error is FetchUserMessage && !msg.isBlank()) msg
                    else friendlyErrorMessage(msg, url)
                    Log.w("HomeViewModel", "[ERROR-STATE] error shown to user: $friendly")
                    _downloadState.value = DownloadState.Error(friendly)
                    onConsumed()
                }
            )
        }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
        // The coroutine is only half of it: the engine child process keeps running to completion
        // unless it is told to stop.
        _urlInput.value.trim().takeIf { it.isNotEmpty() }?.let { repository.cancelFetch(it) }
        _downloadState.value = DownloadState.Idle
        _showFormatSheet.value = false
    }

    fun quickDownload() {
        val url = _urlInput.value.trim()
        if (!url.isValidUrl()) {
            _downloadState.value = DownloadState.Error(getApplication<Application>().getString(R.string.enter_valid_url))
            return
        }

        fetchJob?.cancel()
        val initialMsg = if (url.contains("instagram.com")) getApplication<Application>().getString(R.string.fetching_post) else getApplication<Application>().getString(R.string.fetching_video_info)
        _fetchingMessage.value = initialMsg
        _downloadState.value = DownloadState.FetchingInfo(initialMsg)

        fetchJob = viewModelScope.launch {
            _retryStatus.value = RetryStatus(attempt = 1, maxAttempts = 3, isRetrying = false)
            val result = repository.fetchVideoInfo(
                url,
                onRetryMessage = { msg ->
                    _fetchingMessage.value = msg
                    _downloadState.value = DownloadState.FetchingInfo(msg)
                },
                onRetryStatus = { attempt, maxAttempts ->
                    _retryStatus.value = RetryStatus(attempt = attempt, maxAttempts = maxAttempts, isRetrying = attempt > 1)
                    if (attempt > 1) {
                        val retryMsg = getApplication<Application>().getString(R.string.fetching_attempt, attempt, maxAttempts)
                        _fetchingMessage.value = retryMsg
                        _downloadState.value = DownloadState.FetchingInfo(retryMsg)
                    }
                }
            )
            result.fold(
                onSuccess = { info ->
                    _currentVideoInfo.value = info
                    addRecentUrl(url)
                    suggestDefaultAudioTrack(info.audioTracks)
                    if (info.isCarousel) {
                        selectAllCarousel()
                        _showFormatSheet.value = true
                    } else {
                        downloadBestFormat(info, url)
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: getApplication<Application>().getString(R.string.failed_fetch_video)
                    _downloadState.value = DownloadState.Error(
                        if (error is FetchUserMessage && !msg.isBlank()) msg
                        else friendlyErrorMessage(msg, url)
                    )
                }
            )
        }
    }

    private fun downloadBestFormat(info: VideoInfo, url: String) {
        val bestFormat = info.formats.firstOrNull { it.formatId == "bestvideo+bestaudio" }
            ?: info.formats.firstOrNull { !it.isAudioOnly }
        if (bestFormat != null) {
            viewModelScope.launch {
                val outputDir = repository.downloadPath.first()
                val safeFormatId = FormatSpec.forVideo(
                    formatId = bestFormat.formatId,
                    isAudioOnly = bestFormat.isAudioOnly,
                    isMuxed = bestFormat.isMuxed,
                    audioTrackId = _selectedAudioTrack.value?.formatId
                )
                startDownloadService(url, safeFormatId, outputDir, info, bestFormat.formatId, bestFormat.ext, bestFormat.isAudioOnly)
            }
        } else {
            _downloadState.value = DownloadState.Error(getApplication<Application>().getString(R.string.no_suitable_format))
        }
    }

    fun downloadThumbnailOnly() {
        _showFormatSheet.value = false
        val url = _urlInput.value.trim()
        val info = _currentVideoInfo.value ?: return

        viewModelScope.launch {
            val outputDir = repository.downloadPath.first()
            _downloadState.value = DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING)
            repository.downloadThumbnailOnly(url, outputDir) { state ->
                _downloadState.value = state
                if (state is DownloadState.Success) {
                    viewModelScope.launch {
                        FileHelper.scanFile(getApplication(), state.filePath)
                    }
                }
            }
        }
    }

    fun onFormatSelected(format: VideoFormat) {
        _showFormatSheet.value = false
        val url = _urlInput.value.trim()
        val info = _currentVideoInfo.value ?: return

        viewModelScope.launch {
            val outputDir = repository.downloadPath.first()
            val safeFormatId = FormatSpec.forVideo(
                formatId = format.formatId,
                isAudioOnly = format.isAudioOnly,
                isMuxed = format.isMuxed,
                audioTrackId = _selectedAudioTrack.value?.formatId
            )
            startDownloadService(url, safeFormatId, outputDir, info, format.formatId, format.ext, format.isAudioOnly)
        }
    }

    private var downloadObserverJob: Job? = null

    private fun startDownloadService(
        url: String,
        formatId: String,
        outputDir: String,
        info: VideoInfo,
        fmtId: String,
        ext: String,
        isAudioOnly: Boolean
    ) {
        _downloadState.value = DownloadState.Downloading(0f, phase = DownloadPhase.PREPARING)
        Log.d("HomeViewModel", "[RETRY-DISPATCH] new download attempt starting for ${url.take(60)} formatId=$formatId")

        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FORMAT_ID, formatId)
            putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir)
            putExtra(DownloadService.EXTRA_TITLE, info.title)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, info.thumbnailUrl)
            putExtra(DownloadService.EXTRA_SAVE_THUMBNAIL, _alsoSaveThumbnail.value)
            putExtra(DownloadService.EXTRA_SUBTITLE_LANGS, _selectedSubtitleLangs.value.joinToString(","))
            putExtra(DownloadService.EXTRA_AUTO_CAPTION_LANGS, _selectedAutoCaptionLangs.value.joinToString(","))
            putExtra(DownloadService.EXTRA_EMBED_SUBTITLES, _embedSubtitles.value)
            putExtra(DownloadService.EXTRA_SUBTITLE_FORMAT, _subtitleFormat.value)
            // Metadata stored with the finished History entry.
            putExtra(DownloadService.EXTRA_UPLOADER, info.uploader)
            putExtra(DownloadService.EXTRA_DESCRIPTION, info.description.orEmpty())
            putExtra(DownloadService.EXTRA_HASHTAGS, info.hashtags.joinToString(","))
            putExtra(DownloadService.EXTRA_DURATION, info.duration)
            putExtra(DownloadService.EXTRA_IS_AUDIO_ONLY, isAudioOnly)
            putExtra(
                DownloadService.EXTRA_HAS_SUBTITLES,
                _selectedSubtitleLangs.value.isNotEmpty() || _selectedAutoCaptionLangs.value.isNotEmpty()
            )
        }
        getApplication<Application>().startForegroundService(intent)

        downloadObserverJob?.cancel()
        downloadObserverJob = viewModelScope.launch {
            DownloadStateHolder.state.collect { state ->
                if (state is DownloadState.Downloading) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate < 200L) return@collect
                    lastProgressUpdate = now
                }

                if (state is DownloadState.Error) {
                    val friendly = friendlyErrorMessage(state.message, url)
                    Log.w("HomeViewModel", "[ERROR-STATE] download error shown to user: $friendly (raw: ${state.message})")
                    _downloadState.value = DownloadState.Error(friendly)
                } else {
                    _downloadState.value = state
                }
                if (state is DownloadState.Success) {
                    FileHelper.scanFile(getApplication(), state.filePath)
                    if (_alsoSaveThumbnail.value) {
                        Toast.makeText(
                            getApplication(),
                            getApplication<Application>().getString(R.string.video_thumbnail_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // The History row itself is written by DownloadService, which has
                    // the description/hashtags that were sent with the download.
                }
            }
        }
    }

    fun onDismissFormatSheet() {
        _showFormatSheet.value = false
        if (_downloadState.value is DownloadState.InfoReady ||
            _downloadState.value is DownloadState.PlaylistDetected) {
            _downloadState.value = DownloadState.Idle
        }
    }

    fun onPauseDownload() {
        val url = _urlInput.value.trim()
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_PAUSE_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        getApplication<Application>().startService(intent)
    }

    fun onResumeDownload() {
        val url = _urlInput.value.trim()
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_RESUME_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        getApplication<Application>().startService(intent)
        // Re-establish observer so progress bar updates after resume
        downloadObserverJob?.cancel()
        downloadObserverJob = viewModelScope.launch {
            DownloadStateHolder.state.collect { state ->
                if (state is DownloadState.Downloading) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate < 200L) return@collect
                    lastProgressUpdate = now
                }
                _downloadState.value = state
            }
        }
    }

    fun onStopDownload() {
        val url = _urlInput.value.trim()
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        getApplication<Application>().startService(intent)
        downloadObserverJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun onCancelDownload() {
        onStopDownload()
    }

    fun resetState() {
        fetchJob?.cancel()
        _downloadState.value = DownloadState.Idle
        _currentVideoInfo.value = null
        _showFormatSheet.value = false
    }

    private fun friendlyErrorMessage(rawError: String, url: String): String {
        val platform = FetchErrorClassifier.detectPlatform(url)
        val app = getApplication<Application>()

        return when (FetchErrorClassifier.classify(rawError, platform)) {
            FetchErrorClassifier.Kind.NO_DOWNLOADABLE_MEDIA ->
                app.getString(R.string.error_no_downloadable_media)

            FetchErrorClassifier.Kind.EXTRACTION_FAILED ->
                app.getString(R.string.error_extraction_failed)

            FetchErrorClassifier.Kind.INSUFFICIENT_STORAGE ->
                app.getString(R.string.error_storage_full)

            FetchErrorClassifier.Kind.NETWORK_TIMEOUT ->
                app.getString(R.string.error_network_timeout)

            FetchErrorClassifier.Kind.NO_INTERNET ->
                app.getString(R.string.error_no_internet)

            FetchErrorClassifier.Kind.VIDEO_UNAVAILABLE ->
                app.getString(R.string.error_video_unavailable)

            FetchErrorClassifier.Kind.PRIVATE_VIDEO ->
                app.getString(R.string.error_private_video)

            FetchErrorClassifier.Kind.VIDEO_REMOVED ->
                app.getString(R.string.error_video_removed)

            FetchErrorClassifier.Kind.COPYRIGHT ->
                app.getString(R.string.error_copyright)

            FetchErrorClassifier.Kind.AUTH_REQUIRED ->
                app.getString(R.string.error_auth_required, getCookieHint(platform))

            FetchErrorClassifier.Kind.REGION_BLOCKED ->
                app.getString(R.string.error_region_blocked)

            FetchErrorClassifier.Kind.LIVE_NOT_STARTED ->
                app.getString(R.string.error_live_not_started)

            FetchErrorClassifier.Kind.LIVE_ENDED ->
                app.getString(R.string.error_live_ended)

            FetchErrorClassifier.Kind.CODEC_ISSUE ->
                app.getString(R.string.error_codec_issue)

            FetchErrorClassifier.Kind.FORMAT_UNAVAILABLE ->
                app.getString(R.string.error_format_unavailable)

            FetchErrorClassifier.Kind.INSTAGRAM_LOGIN ->
                app.getString(R.string.error_instagram_login)

            FetchErrorClassifier.Kind.TWITTER_RATE_LIMIT ->
                app.getString(R.string.error_twitter_rate_limit)

            FetchErrorClassifier.Kind.TIKTOK_EXTRACTOR ->
                app.getString(R.string.error_tiktok_extractor)

            FetchErrorClassifier.Kind.FACEBOOK_LOGIN ->
                app.getString(R.string.error_facebook_login)

            FetchErrorClassifier.Kind.BOT_CHALLENGE ->
                app.getString(R.string.error_bot_challenge)

            FetchErrorClassifier.Kind.SOURCE_RESTRICTED ->
                app.getString(R.string.error_source_restricted)

            FetchErrorClassifier.Kind.GENERIC ->
                app.getString(R.string.error_generic_with_hint)
        }
    }

    private fun detectPlatform(url: String): Int = FetchErrorClassifier.detectPlatform(url)

    private fun getCookieHint(platform: Int): String {
        return when (platform) {
            FetchErrorClassifier.PLATFORM_INSTAGRAM -> getApplication<Application>().getString(R.string.cookie_hint_instagram)
            FetchErrorClassifier.PLATFORM_FACEBOOK -> getApplication<Application>().getString(R.string.cookie_hint_facebook)
            FetchErrorClassifier.PLATFORM_TWITTER -> getApplication<Application>().getString(R.string.cookie_hint_twitter)
            FetchErrorClassifier.PLATFORM_YOUTUBE -> getApplication<Application>().getString(R.string.cookie_hint_youtube_age)
            else -> getApplication<Application>().getString(R.string.cookie_hint_generic)
        }
    }
}

data class RetryStatus(
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val isRetrying: Boolean = false
)
