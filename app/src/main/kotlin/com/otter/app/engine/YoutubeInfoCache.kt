package com.otter.app.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The extraction yt-dlp produces when a video's formats are listed.
 *
 * Reading a YouTube page costs a Python start, a page fetch and a throttling
 * challenge, and the app used to pay for that twice: once to show the format list
 * and again for the download itself. On a short video that second extraction is most
 * of the wait. Handing the saved extraction back with `--load-info-json` skips it.
 *
 * YouTube's format URLs expire, so a saved extraction is only offered while it is
 * still [FRESHNESS_MS] old, and a download that fails with it simply re-extracts on
 * the next attempt.
 */
object YoutubeInfoCache {

    private const val TAG = "YoutubeInfoCache"

    /** YouTube's format URLs last hours; half an hour is safely inside that. */
    const val FRESHNESS_MS = 30L * 60L * 1000L

    private const val DIR = "youtube-info"
    private const val KEEP = 8

    fun file(context: Context, videoId: String): File =
        File(File(context.cacheDir, DIR).apply { mkdirs() }, "$videoId.info.json")

    /** True when a saved extraction is new enough for its format URLs to still work. */
    fun isFresh(file: File, now: Long = System.currentTimeMillis()): Boolean =
        file.isFile && now - file.lastModified() in 0..FRESHNESS_MS

    /** Saves an extraction and drops the oldest ones beyond [KEEP]. */
    fun keep(context: Context, videoId: String, json: String) {
        if (videoId.isBlank() || json.isBlank()) return
        runCatching {
            val target = file(context, videoId)
            target.writeText(json)
            File(context.cacheDir, DIR).listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "Could not keep the extraction: ${it.message}") }
    }
}
