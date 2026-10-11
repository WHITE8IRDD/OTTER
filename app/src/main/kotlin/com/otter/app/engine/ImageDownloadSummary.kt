package com.otter.app.engine

data class ImageDownloadSummary(
    val savedPaths: List<String>,
    val failedCount: Int
) {
    val savedCount: Int get() = savedPaths.size
}
