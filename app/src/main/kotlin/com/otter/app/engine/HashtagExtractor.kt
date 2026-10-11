package com.otter.app.engine

/**
 * Hashtags of a post/video description.
 *
 * Kept Android-free and dependency-free so it is unit tested on a plain JVM and can
 * be shared by the video path (yt-dlp description) and the Instagram path (caption).
 */
object HashtagExtractor {

    private val hashtag = Regex("""#\w+""")

    /** Every hashtag of [text], in order, without duplicates (case-sensitive). */
    fun extract(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return hashtag.findAll(text)
            .map { it.value }
            .distinct()
            .toList()
    }

    /** The description with its hashtags removed — used as a post title fallback. */
    fun strip(text: String): String =
        text.replace(hashtag, "").replace(Regex("""\s+"""), " ").trim()
}
