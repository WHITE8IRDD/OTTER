package com.otter.app.engine

import org.json.JSONObject

/** yt-dlp's resolved gallery entries, NOT its flat webpage links or thumbnails. */
object EngineGalleryParser {
    data class Gallery(val slides: List<InstagramMediaParser.Slide>, val expectedCount: Int)

    fun parse(json: JSONObject): Gallery {
        val entries = json.optJSONArray("entries")
        val nodes = if (entries == null) listOf(json) else
            (0 until minOf(entries.length(), 40)).mapNotNull { entries.optJSONObject(it) }
        val slides = nodes.mapNotNull { entry ->
            val url = entry.optString("url")
            val ext = entry.optString("ext").lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") && isHttp(url)) {
                InstagramMediaParser.Slide(url, false)
            } else {
                val formats = entry.optJSONArray("formats")
                val videos = if (formats == null) emptyList() else
                    (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                        .filter { fmt ->
                            fmt.optString("vcodec").let { it.isNotBlank() && it != "none" && it != "images" } &&
                                fmt.optString("protocol") in setOf("http", "https", "") &&
                                isHttp(fmt.optString("url"))
                        }
                val best = videos.maxWithOrNull(compareBy<JSONObject> { it.optInt("height") }.thenBy { it.optDouble("tbr", 0.0) })
                val videoUrl = best?.optString("url") ?: url.takeIf {
                    isHttp(it) && ext in setOf("mp4", "webm", "mov", "m4v") &&
                        entry.optString("_type") !in setOf("url", "url_transparent")
                }
                videoUrl?.let { InstagramMediaParser.Slide(it, true, entry.optString("thumbnail")) }
            }
        }.distinctBy { MediaClassifier.identityOf(it.url) }
        return Gallery(slides, entries?.length() ?: 1)
    }

    private fun isHttp(url: String) = MediaUrlNormalizer.host(url) != null
}
