package com.otter.app.data.model

sealed class DownloadState {
    object Idle : DownloadState()
    data class FetchingInfo(val message: String = "Fetching video info\u2026") : DownloadState()
    data class InfoReady(val info: VideoInfo) : DownloadState()
    data class PlaylistDetected(val info: VideoInfo, val count: Int) : DownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long = -1L,
        val phase: DownloadPhase = DownloadPhase.DOWNLOADING
    ) : DownloadState() {
        val percentText: String
            get() = "${(progress * 100).coerceIn(0f, 100f).toInt()}%"

        val sizeText: String
            get() = if (totalBytes > 0) "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}" else ""

        val speedText: String
            get() = if (speedBytesPerSec > 0) "${formatBytes(speedBytesPerSec)}/s" else ""

        val etaText: String
            get() = formatEta(etaSeconds)
    }
    data class Paused(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val url: String,
        val formatId: String,
        val outputDir: String,
        val title: String
    ) : DownloadState()
    data class Success(val filePath: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

enum class DownloadPhase {
    PREPARING,
    DOWNLOADING,
    MERGING,
    POST_PROCESSING,
    FINISHING
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
