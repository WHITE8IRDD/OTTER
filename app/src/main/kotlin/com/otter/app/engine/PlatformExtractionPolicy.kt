package com.otter.app.engine

/** Extraction identity must be identical while listing formats and downloading them. */
object PlatformExtractionPolicy {
    fun options(url: String): List<Pair<String, String>> = when {
        MediaUrlNormalizer.hostMatches(url, "tiktok.com") || MediaUrlNormalizer.hostMatches(url, "tiktokv.com") -> listOf(
            "--user-agent" to MediaUrlNormalizer.TIKTOK_USER_AGENT,
            "--add-header" to "Referer:https://www.tiktok.com/"
        )
        // Use the pinned engine's maintained client defaults and bundled QuickJS/EJS solver.
        // Freezing a client exclusion here makes future engine updates ineffective.
        else -> emptyList()
    }
}
