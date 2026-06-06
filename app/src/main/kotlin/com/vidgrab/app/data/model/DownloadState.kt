package com.vidgrab.app.data.model

sealed class DownloadState {
    object Idle : DownloadState()
    data class FetchingInfo(val message: String = "Fetching video info\u2026") : DownloadState()
    data class InfoReady(val info: VideoInfo) : DownloadState()
    data class PlaylistDetected(val info: VideoInfo, val count: Int) : DownloadState()
    data class Downloading(val progress: Float, val speedText: String, val etaText: String) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}
