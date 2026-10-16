package com.otter.app.engine

/**
 * What a multi-item download actually produced.
 *
 * [firstFailure] carries the reason of the first slide that failed, because "24 of 24 failed" is
 * not something a user can act on: an expired CDN signature, a login wall and a full storage card
 * all look identical from a counter.
 */
data class ImageDownloadSummary(
    val savedPaths: List<String>,
    val failedCount: Int,
    val firstFailure: String = ""
) {
    val savedCount: Int get() = savedPaths.size
}
