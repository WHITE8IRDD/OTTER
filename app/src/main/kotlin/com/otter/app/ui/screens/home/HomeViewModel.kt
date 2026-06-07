package com.otter.app.ui.screens.home

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.model.DownloadItem
import com.otter.app.data.model.DownloadState
import com.otter.app.data.model.VideoFormat
import com.otter.app.data.model.VideoInfo
import com.otter.app.data.model.AudioTrack
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.service.DownloadService
import com.otter.app.service.DownloadStateHolder
import com.otter.app.R
import com.otter.app.util.FileHelper
import com.otter.app.util.isValidUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _selectedCarouselIndices = MutableStateFlow<Set<Int>>(emptySet())
    val selectedCarouselIndices: StateFlow<Set<Int>> = _selectedCarouselIndices.asStateFlow()

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
        val current = _selectedCarouselIndices.value.toMutableSet()
        if (current.contains(index)) current.remove(index) else current.add(index)
        _selectedCarouselIndices.value = current
    }

    fun selectAllCarousel() {
        val count = _currentVideoInfo.value?.carouselImages?.size ?: 0
        _selectedCarouselIndices.value = (0 until count).toSet()
    }

    fun deselectAllCarousel() {
        _selectedCarouselIndices.value = emptySet()
    }

    fun downloadCarouselSelected() {
        val info = _currentVideoInfo.value ?: return
        val indices = _selectedCarouselIndices.value
        if (indices.isEmpty()) return

        val selectedUrls = indices.sorted().map { info.carouselImages[it] }
        _showFormatSheet.value = false

        viewModelScope.launch {
            val outputDir = repository.downloadPath.first()
            _downloadState.value = DownloadState.Downloading(0f, "", "")
            repository.downloadImages(selectedUrls, info.title, outputDir) { state ->
                _downloadState.value = state
                if (state is DownloadState.Success) {
                    val toastMsg = if (selectedUrls.size == 1) {
                        getApplication<Application>().getString(R.string.image_saved)
                    } else {
                        getApplication<Application>().getString(R.string.images_saved, selectedUrls.size)
                    }
                    Toast.makeText(getApplication(), toastMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun fetchVideoInfo(onConsumed: () -> Unit = {}) {
        val url = _urlInput.value.trim()
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

        fetchJob = viewModelScope.launch {
            val result = repository.fetchVideoInfo(url) { msg ->
                _fetchingMessage.value = msg
                _downloadState.value = DownloadState.FetchingInfo(msg)
            }
            result.fold(
                onSuccess = { info ->
                    _currentVideoInfo.value = info
                    addRecentUrl(url)
                    suggestDefaultAudioTrack(info.audioTracks)
                    if (info.isCarousel) {
                        _selectedCarouselIndices.value = (0 until info.carouselImages.size).toSet()
                        _showThumbnailOnly.value = false
                        _downloadState.value = DownloadState.InfoReady(info)
                        _showFormatSheet.value = true
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
                    val friendly = buildFriendlyError(msg, url)
                    _downloadState.value = DownloadState.Error(friendly)
                    onConsumed()
                }
            )
        }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
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
            val result = repository.fetchVideoInfo(url) { msg ->
                _fetchingMessage.value = msg
                _downloadState.value = DownloadState.FetchingInfo(msg)
            }
            result.fold(
                onSuccess = { info ->
                    _currentVideoInfo.value = info
                    addRecentUrl(url)
                    suggestDefaultAudioTrack(info.audioTracks)
                    if (info.isCarousel) {
                        _selectedCarouselIndices.value = (0 until info.carouselImages.size).toSet()
                        _showFormatSheet.value = true
                    } else {
                        downloadBestFormat(info, url)
                    }
                },
                onFailure = { error ->
                    val msg = error.message ?: getApplication<Application>().getString(R.string.failed_fetch_video)
                    _downloadState.value = DownloadState.Error(buildFriendlyError(msg, url))
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
                val audioTrack = _selectedAudioTrack.value
                val safeFormatId = if (bestFormat.isAudioOnly) {
                    audioTrack?.formatId ?: "bestaudio[ext=m4a]/bestaudio"
                } else {
                    val audioId = audioTrack?.formatId ?: "bestaudio"
                    "${bestFormat.formatId}+$audioId/best"
                }
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
            _downloadState.value = DownloadState.Downloading(0f, "", "")
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
            val audioTrack = _selectedAudioTrack.value
            val safeFormatId = if (format.isAudioOnly) {
                audioTrack?.formatId ?: "bestaudio[ext=m4a]/bestaudio"
            } else {
                val audioId = audioTrack?.formatId ?: "bestaudio"
                "${format.formatId}+$audioId/best"
            }
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
        _downloadState.value = DownloadState.Downloading(0f, "", "")

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
        }
        getApplication<Application>().startForegroundService(intent)

        downloadObserverJob?.cancel()
        downloadObserverJob = viewModelScope.launch {
            DownloadStateHolder.state.collect { state ->
                val now = System.currentTimeMillis()
                if (state is DownloadState.Downloading && now - lastProgressUpdate < 200L) return@collect
                if (state is DownloadState.Downloading) lastProgressUpdate = now

                _downloadState.value = state
                if (state is DownloadState.Success) {
                    FileHelper.scanFile(getApplication(), state.filePath)
                    if (_alsoSaveThumbnail.value) {
                        Toast.makeText(
                            getApplication(),
                            getApplication<Application>().getString(R.string.video_thumbnail_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    val fileSize = withContext(Dispatchers.IO) {
                        FileHelper.getFileSizeBytes(state.filePath)
                    }
                    val item = DownloadItem(
                        url = url,
                        title = info.title,
                        uploader = info.uploader,
                        thumbnailUrl = info.thumbnailUrl,
                        filePath = state.filePath,
                        fileSize = fileSize,
                        duration = info.duration,
                        formatId = fmtId,
                        ext = ext,
                        isAudioOnly = isAudioOnly,
                        hasSubtitles = _selectedSubtitleLangs.value.isNotEmpty() || _selectedAutoCaptionLangs.value.isNotEmpty()
                    )
                    repository.saveDownloadItem(item)
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

    fun onCancelDownload() {
        val url = _urlInput.value.trim()
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        getApplication<Application>().startService(intent)
        downloadObserverJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun resetState() {
        fetchJob?.cancel()
        _downloadState.value = DownloadState.Idle
        _currentVideoInfo.value = null
        _showFormatSheet.value = false
    }

    private fun buildFriendlyError(msg: String, url: String): String {
        return when {
            msg.contains("Instagram API is not granting access") ||
            msg.contains("empty media response") ||
            msg.contains("instagram") && (msg.contains("login") || msg.contains("cookies")) ->
                getApplication<Application>().getString(R.string.error_instagram_cookies)
            msg.contains("sign in") && msg.contains("YouTube") ->
                getApplication<Application>().getString(R.string.error_youtube_login)
            (url.contains("facebook.com") || url.contains("fb.watch") || url.contains("fb.com")) &&
                    (msg.contains("generic") || msg.contains("fallback") || msg.contains("extractor")) ->
                getApplication<Application>().getString(R.string.error_facebook)
            (url.contains("tiktok.com") || url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com")) &&
                    (msg.contains("impersonat") || msg.contains("not available") || msg.contains("status code 0")) ->
                getApplication<Application>().getString(R.string.error_tiktok)
            else -> msg
        }
    }
}
