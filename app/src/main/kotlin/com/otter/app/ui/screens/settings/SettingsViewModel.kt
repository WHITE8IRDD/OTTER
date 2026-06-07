package com.otter.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.R
import com.otter.app.data.repository.DownloadRepository
import com.otter.app.engine.YtDlpUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloadRepository(application)
    private val updater = YtDlpUpdater(application)
    private val prefs = repository.preferences

    val downloadPath: StateFlow<String> = prefs.downloadPath
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val autoDownload: StateFlow<Boolean> = prefs.autoDownload
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val extractAudio: StateFlow<Boolean> = prefs.extractAudio
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val audioFormat: StateFlow<String> = prefs.audioFormat
        .stateIn(viewModelScope, SharingStarted.Eagerly, "mp3")

    val downloadSubtitles: StateFlow<Boolean> = prefs.downloadSubtitles
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val subtitleLang: StateFlow<String> = prefs.subtitleLang
        .stateIn(viewModelScope, SharingStarted.Eagerly, "en.*,.*-orig")

    val embedThumbnail: StateFlow<Boolean> = prefs.embedThumbnail
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val embedMetadata: StateFlow<Boolean> = prefs.embedMetadata
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val rateLimit: StateFlow<Boolean> = prefs.rateLimit
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val maxRate: StateFlow<String> = prefs.maxRate
        .stateIn(viewModelScope, SharingStarted.Eagerly, "1000")

    val playlist: StateFlow<Boolean> = prefs.playlist
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val outputTemplate: StateFlow<String> = prefs.outputTemplate
        .stateIn(viewModelScope, SharingStarted.Eagerly, "%(title)s.%(ext)s")

    val userAgent: StateFlow<String> = prefs.userAgent
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val useAria2c: StateFlow<Boolean> = prefs.useAria2c
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val concurrentFragments: StateFlow<Int> = prefs.concurrentFragments
        .stateIn(viewModelScope, SharingStarted.Eagerly, 8)

    val retries: StateFlow<Int> = prefs.retries
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    val cookiesEnabled: StateFlow<Boolean> = prefs.cookiesEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val cookieContent: StateFlow<String> = prefs.cookieContent
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    val ytDlpVersion: String
        get() = updater.getVersion()

    val appVersion: String
        get() = try {
            getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "1.1.0"
        } catch (e: Exception) { "1.1.0" }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoDownload(enabled) }
    }

    fun setExtractAudio(enabled: Boolean) {
        viewModelScope.launch { prefs.setExtractAudio(enabled) }
    }

    fun setAudioFormat(format: String) {
        viewModelScope.launch { prefs.setAudioFormat(format) }
    }

    fun setDownloadSubtitles(enabled: Boolean) {
        viewModelScope.launch { prefs.setDownloadSubtitles(enabled) }
    }

    fun setSubtitleLang(lang: String) {
        viewModelScope.launch { prefs.setSubtitleLang(lang) }
    }

    fun setEmbedThumbnail(enabled: Boolean) {
        viewModelScope.launch { prefs.setEmbedThumbnail(enabled) }
    }

    fun setEmbedMetadata(enabled: Boolean) {
        viewModelScope.launch { prefs.setEmbedMetadata(enabled) }
    }

    fun setRateLimit(enabled: Boolean) {
        viewModelScope.launch { prefs.setRateLimit(enabled) }
    }

    fun setMaxRate(rate: String) {
        viewModelScope.launch { prefs.setMaxRate(rate) }
    }

    fun setPlaylist(enabled: Boolean) {
        viewModelScope.launch { prefs.setPlaylist(enabled) }
    }

    fun setOutputTemplate(template: String) {
        viewModelScope.launch { prefs.setOutputTemplate(template) }
    }

    fun setUserAgent(ua: String) {
        viewModelScope.launch { prefs.setUserAgent(ua) }
    }

    fun setUseAria2c(enabled: Boolean) {
        viewModelScope.launch { prefs.setUseAria2c(enabled) }
    }

    fun setConcurrentFragments(n: Int) {
        viewModelScope.launch { prefs.setConcurrentFragments(n) }
    }

    fun setRetries(n: Int) {
        viewModelScope.launch { prefs.setRetries(n) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDarkMode(enabled) }
    }

    fun setCookiesEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCookiesEnabled(enabled) }
    }

    fun clearCookies() {
        viewModelScope.launch {
            prefs.setCookieContent("")
            prefs.setCookiesEnabled(false)
        }
    }

    fun updateYtDlp() {
        if (_isUpdating.value) return
        viewModelScope.launch {
            _isUpdating.value = true
            _updateStatus.value = getApplication<Application>().getString(R.string.checking_updates)
            val result = updater.update { progress ->
                _updateStatus.value = progress
            }
            _updateStatus.value = when (result) {
                is YtDlpUpdater.UpdateResult.Updated -> getApplication<Application>().getString(R.string.updated_to, result.version)
                is YtDlpUpdater.UpdateResult.AlreadyUpToDate -> getApplication<Application>().getString(R.string.already_up_to_date)
                is YtDlpUpdater.UpdateResult.Failed -> getApplication<Application>().getString(R.string.update_failed, result.error)
            }
            _isUpdating.value = false
        }
    }

    fun setDownloadPath(path: String) {
        viewModelScope.launch { prefs.setDownloadPath(path) }
    }

    fun resetDownloadPath() {
        viewModelScope.launch {
            prefs.setDownloadPath(prefs.getDefaultDownloadPath())
        }
    }

    fun clearUpdateStatus() {
        _updateStatus.value = null
    }
}
