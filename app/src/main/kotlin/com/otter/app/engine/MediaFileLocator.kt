package com.otter.app.engine

import java.io.File

/**
 * Finds the media file a finished download actually produced.
 *
 * A YouTube download can write straight into the download folder rather than into a
 * staging directory of its own, so "the newest media file in there" is not necessarily
 * the file this download wrote. yt-dlp names the file with the video id in square
 * brackets, which is enough to tell them apart.
 */
object MediaFileLocator {

    private val mediaExtensions = setOf(
        "mp4", "m4v", "mov", "webm", "mkv", "m4a", "mp3", "opus", "ogg", "flac", "wav", "aac"
    )

    /**
     * @param dir the directory the download wrote into.
     * @param videoId the video id from the URL, blank when unknown.
     */
    fun findDownloaded(dir: File, videoId: String): File? {
        val files = runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
            .orEmpty()
        if (files.isEmpty()) return null

        val id = videoId.trim()
        val tagged = if (id.isEmpty()) emptyList() else files.filter { it.name.contains("[$id]") }
        val pool = tagged.ifEmpty { files }
        return pool.maxByOrNull { it.lastModified() }
    }
}
