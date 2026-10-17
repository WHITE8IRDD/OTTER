package com.otter.app.engine

/** Posters use the same public referer as downloads; no cookie jar is attached to previews. */
object MediaPreviewHeaders {
    fun forUrl(url: String): Map<String, String> = if (
        listOf("cdninstagram.com", "fbcdn.net").any { MediaUrlNormalizer.hostMatches(url, it) }
    ) mapOf("Referer" to "https://www.instagram.com/") else emptyMap()
}
