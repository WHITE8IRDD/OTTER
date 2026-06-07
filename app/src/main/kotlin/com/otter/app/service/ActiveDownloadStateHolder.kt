package com.otter.app.service

import android.util.Log
import com.otter.app.data.model.DownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveDownloadStateHolder {
    private const val TAG = "ActiveDownloadHolder"

    private val _activeDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val activeDownloadState: StateFlow<DownloadState> = _activeDownloadState.asStateFlow()

    private val _activeDownloadTitle = MutableStateFlow<String?>(null)
    val activeDownloadTitle: StateFlow<String?> = _activeDownloadTitle.asStateFlow()

    private val _activeDownloadUrl = MutableStateFlow<String?>(null)
    val activeDownloadUrl: StateFlow<String?> = _activeDownloadUrl.asStateFlow()

    fun updateState(state: DownloadState, title: String? = null, url: String? = null) {
        _activeDownloadState.value = state
        if (title != null) _activeDownloadTitle.value = title
        if (url != null) _activeDownloadUrl.value = url
        Log.i(TAG, "[HOLDER] State updated: ${state.javaClass.simpleName} title=$title")
    }

    fun clear() {
        _activeDownloadState.value = DownloadState.Idle
        _activeDownloadTitle.value = null
        _activeDownloadUrl.value = null
        Log.i(TAG, "[HOLDER] State cleared")
    }
}
