package com.otter.app.share

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.data.datastore.UserPreferences
import com.otter.app.data.model.CarouselItem
import com.otter.app.data.model.VideoInfo
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.engine.FetchUserMessage
import com.otter.app.engine.FormatSpec
import com.otter.app.engine.QuickDownloadRouting
import com.otter.app.service.DownloadService
import com.otter.app.util.FriendlyErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the small Quick Download window is showing right now. */
sealed interface QuickDownloadUiState {
    /** The post is being read; the window shows a spinner over the app underneath. */
    data object Loading : QuickDownloadUiState

    /** The post's files are listed and the user is choosing. */
    data class Ready(
        val title: String,
        val platformLabel: String,
        val items: List<CarouselItem>,
        val selection: Set<Int>,
        val isPartial: Boolean,
        val expectedCount: Int,
        /** True when the item is one clip only the engine can extract (no per-file list). */
        val isEngineVideo: Boolean
    ) : QuickDownloadUiState

    /** The post could not be read; the window offers retry instead of leaving the app. */
    data class Error(val message: String) : QuickDownloadUiState

    /** The chosen files are being handed to the download service. */
    data object Dispatching : QuickDownloadUiState

    /** Downloads started; the window may close and the user stays where they were. */
    data object Done : QuickDownloadUiState
}

/**
 * Drives the Quick Download window: reads a shared link, lists its media, and sends
 * the picked files to the foreground download service.
 *
 * The whole point of this window is that it happens *over* the app that shared the
 * link — Instagram, TikTok, YouTube — so nothing here may launch another screen. The
 * downloads themselves run in `DownloadService`, which survives this activity closing
 * one second later.
 */
class QuickDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(application)
    private val preferences = UserPreferences(application)

    private val _state = MutableStateFlow<QuickDownloadUiState>(QuickDownloadUiState.Loading)
    val state: StateFlow<QuickDownloadUiState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var sourceUrl: String = ""
    private var items: List<CarouselItem> = emptyList()
    private var info: VideoInfo? = null

    fun start(url: String) {
        sourceUrl = url.trim()
        fetch()
    }

    fun retry() {
        if (sourceUrl.isNotBlank()) fetch()
    }

    fun cancelFetch() {
        fetchJob?.cancel()
        sourceUrl.takeIf { it.isNotBlank() }?.let { repository.cancelFetch(it) }
    }

    private fun fetch() {
        fetchJob?.cancel()
        _state.value = QuickDownloadUiState.Loading
        fetchJob = viewModelScope.launch {
            val result = repository.fetchVideoInfo(sourceUrl)
            result.fold(
                onSuccess = { fetched -> buildReady(fetched) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Quick Download could not read ${sourceUrl.take(80)}: ${error.message}")
                    _state.value = QuickDownloadUiState.Error(
                        FriendlyErrors.message(
                            context = getApplication<Application>(),
                            rawError = error.message.orEmpty(),
                            url = sourceUrl,
                            composed = (error as? FetchUserMessage)?.message
                        )
                    )
                }
            )
        }
    }

    private fun buildReady(fetched: VideoInfo) {
        info = fetched
        val galleryItems = fetched.carouselMedia
        items = when {
            galleryItems.isNotEmpty() -> galleryItems
            // One clip only the engine can open: the window lists it as one item so the
            // user still sees what will be saved and presses Download themselves.
            else -> listOf(
                CarouselItem(
                    url = fetched.originalUrl,
                    isVideo = true,
                    posterUrl = fetched.thumbnailUrl.orEmpty(),
                    detail = fetched.platformName
                )
            )
        }
        val isEngineVideo = galleryItems.isEmpty()
        _state.value = QuickDownloadUiState.Ready(
            title = fetched.title,
            platformLabel = platformLabel(fetched),
            items = items,
            selection = items.indices.toSet(),
            isPartial = fetched.isCarouselPartial,
            expectedCount = fetched.carouselExpectedCount,
            isEngineVideo = isEngineVideo
        )
    }

    /** What the window's header chip reads: the extractor's name, or the URL's own shape. */
    private fun platformLabel(fetched: VideoInfo): String {
        val fromExtractor = fetched.platformName
        if (fromExtractor.isNotBlank() && fromExtractor != "Generic") return fromExtractor
        return when (QuickDownloadRouting.route(sourceUrl)) {
            QuickDownloadRouting.Route.INSTAGRAM_GALLERY -> "Instagram"
            QuickDownloadRouting.Route.TIKTOK_PHOTO, QuickDownloadRouting.Route.TIKTOK_VIDEO -> "TikTok"
            QuickDownloadRouting.Route.DIRECT_IMAGE -> "Image"
            QuickDownloadRouting.Route.ENGINE_VIDEO -> fromExtractor.ifBlank { "Video" }
        }
    }

    fun toggle(index: Int) = withReady { ready ->
        val selection = ready.selection.toMutableSet()
        if (!selection.add(index)) selection.remove(index)
        _state.value = ready.copy(selection = selection)
    }

    fun selectAll() = withReady { ready ->
        _state.value = ready.copy(selection = ready.items.indices.toSet())
    }

    fun clearSelection() = withReady { ready ->
        _state.value = ready.copy(selection = emptySet())
    }

    /** "Pictures only" / "videos only" shortcuts for a mixed post. */
    fun selectKind(isVideo: Boolean) = withReady { ready ->
        _state.value = ready.copy(
            selection = ready.items.indices.filter { ready.items[it].isVideo == isVideo }.toSet()
        )
    }

    fun dismiss() {
        cancelFetch()
    }

    /**
     * Sends the picked files to the foreground service and reports Done, so the window
     * can close while the download itself keeps running over Instagram.
     */
    fun download() {
        val ready = _state.value as? QuickDownloadUiState.Ready ?: return
        if (ready.selection.isEmpty()) return
        val fetched = info ?: return
        _state.value = QuickDownloadUiState.Dispatching

        viewModelScope.launch {
            try {
                if (ready.isEngineVideo) {
                    startEngineDownload(fetched)
                } else {
                    startBatchDownload(fetched, ready)
                }
                _state.value = QuickDownloadUiState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Quick Download could not start", e)
                _state.value = QuickDownloadUiState.Error(e.message.orEmpty())
            }
        }
    }

    /** The engine path: one clip, best video+audio, exactly the quick-download promise. */
    private suspend fun startEngineDownload(fetched: VideoInfo) {
        val outputDir = repository.downloadPath.first()
        val dlSubtitles = preferences.getDownloadSubtitlesOnce()
        val subLang = if (dlSubtitles) preferences.getSubtitleLangOnce() else ""
        val bestFormat = fetched.formats.firstOrNull { it.formatId == "bestvideo+bestaudio" }
            ?: fetched.formats.firstOrNull { !it.isAudioOnly }
        val formatSpec = if (bestFormat != null) {
            FormatSpec.forVideo(
                formatId = bestFormat.formatId,
                isAudioOnly = bestFormat.isAudioOnly,
                isMuxed = bestFormat.isMuxed
            )
        } else {
            "bestvideo+bestaudio/best"
        }
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, fetched.originalUrl)
            putExtra(DownloadService.EXTRA_FORMAT_ID, formatSpec)
            putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir)
            putExtra(DownloadService.EXTRA_TITLE, fetched.title)
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, fetched.thumbnailUrl)
            putExtra(DownloadService.EXTRA_SAVE_THUMBNAIL, false)
            putExtra(DownloadService.EXTRA_SUBTITLE_LANGS, subLang)
            putExtra(DownloadService.EXTRA_AUTO_CAPTION_LANGS, "")
            putExtra(DownloadService.EXTRA_EMBED_SUBTITLES, dlSubtitles)
            putExtra(DownloadService.EXTRA_SUBTITLE_FORMAT, "srt")
            putExtra(DownloadService.EXTRA_UPLOADER, fetched.uploader)
            putExtra(DownloadService.EXTRA_DESCRIPTION, fetched.description.orEmpty())
            putExtra(DownloadService.EXTRA_HASHTAGS, fetched.hashtags.joinToString(","))
            putExtra(DownloadService.EXTRA_DURATION, fetched.duration)
            putExtra(DownloadService.EXTRA_IS_AUDIO_ONLY, false)
            putExtra(DownloadService.EXTRA_HAS_SUBTITLES, dlSubtitles)
        }
        getApplication<Application>().startForegroundService(intent)
        Log.i(TAG, "Quick Download started an engine download for ${fetched.originalUrl.take(80)}")
    }

    /** The gallery path: the picked slides travel straight from their signed CDN URLs. */
    private suspend fun startBatchDownload(fetched: VideoInfo, ready: QuickDownloadUiState.Ready) {
        val spec = QuickDownloadRouting.MediaBatchSpec.fromSelection(
            items = ready.items,
            selected = ready.selection,
            sourceUrl = fetched.originalUrl,
            title = fetched.title,
            uploader = fetched.uploader,
            description = fetched.description.orEmpty(),
            hashtags = fetched.hashtags,
            thumbnailUrl = fetched.thumbnailUrl.orEmpty()
        ) ?: return
        val outputDir = repository.downloadPath.first()
        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_MEDIA_BATCH
            putExtra(DownloadService.EXTRA_URL, spec.sourceUrl)
            putExtra(DownloadService.EXTRA_TITLE, spec.title)
            putExtra(DownloadService.EXTRA_OUTPUT_DIR, outputDir)
            putStringArrayListExtra(DownloadService.EXTRA_MEDIA_URLS, ArrayList(spec.mediaUrls))
            putIntegerArrayListExtra(DownloadService.EXTRA_MEDIA_KINDS, ArrayList(spec.mediaKinds))
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, spec.thumbnailUrl)
            putExtra(DownloadService.EXTRA_UPLOADER, spec.uploader)
            putExtra(DownloadService.EXTRA_DESCRIPTION, spec.description)
            putExtra(DownloadService.EXTRA_HASHTAGS, spec.hashtags.joinToString(","))
            putExtra(DownloadService.EXTRA_DURATION, fetched.duration)
            putExtra(DownloadService.EXTRA_IS_AUDIO_ONLY, false)
            putExtra(DownloadService.EXTRA_HAS_SUBTITLES, false)
        }
        getApplication<Application>().startForegroundService(intent)
        Log.i(TAG, "Quick Download started a ${spec.size}-file batch for ${spec.sourceUrl.take(80)}")
    }

    private inline fun withReady(block: (QuickDownloadUiState.Ready) -> Unit) {
        (_state.value as? QuickDownloadUiState.Ready)?.let(block)
    }

    companion object {
        private const val TAG = "QuickDownloadViewModel"
    }
}
