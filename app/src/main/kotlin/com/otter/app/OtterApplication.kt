package com.otter.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.otter.app.engine.EngineLock
import com.otter.app.engine.FormatParser
import com.otter.app.engine.YtDlpUpdater
import com.otter.app.util.NotificationHelper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtterApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.createChannels(this)
        applicationScope.launch {
            val initJob = async { initAVEngine() }
            val warmJob = async { FormatParser.warmUpEngine(this@OtterApplication) }
            initJob.await()
            warmJob.await()
            keepEngineCurrent()
        }
    }

    private suspend fun initAVEngine() = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(this@OtterApplication)
            FFmpeg.getInstance().init(this@OtterApplication)
            Log.i(TAG, "Engine initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize engine", e)
        }
    }

    /**
     * Keeps the bundled yt-dlp from drifting out of date.
     *
     * The APK ships an engine build frozen at compile time — this one shipped a 2025.11.12 while
     * the project was at 2026.08.19 — and every extractor fix in between is lost: YouTube challenges,
     * Instagram payload shapes and TikTok endpoints all change on the site's side, not the app's. A
     * daily check against the stable channel is what other downloader apps do for exactly this reason.
     *
     * Three things hold it back. It waits for the engine to be idle, because the update is a
     * multi-megabyte download and a restart of the very binary the user just asked to fetch a page
     * with. It refuses a metered connection, because that download costs money. And it never touches
     * the nightly channel: the button in Settings stays the way to ask for the bleeding edge.
     */
    private suspend fun keepEngineCurrent() {
        if (EngineLock.wasUsed) {
            Log.i(TAG, "Engine update skipped: a download is already running in this session")
            return
        }
        // The grace period is what makes the check above mean something. Startup is otherwise
        // always idle, and the updater works by deleting the engine directory and refilling it, so
        // a fetch that began inside that window would find no binary. A user who has not asked for
        // anything in these seconds is not going to be disturbed by the swap; one who has is.
        delay(UPDATE_GRACE_MS)
        if (EngineLock.wasUsed) {
            Log.i(TAG, "Engine update deferred: the engine was put to work during the grace period")
            return
        }
        val updater = YtDlpUpdater(this)
        if (!updater.shouldAutoUpdate()) return
        if (!isUnmeteredConnection()) {
            Log.i(TAG, "Engine update skipped: connection is metered")
            return
        }
        // The first run after an install reports the bundled build as "older" than the newest
        // release, so this can cost one download of a few megabytes before it goes quiet for a day.
        when (val result = updater.update(channel = YoutubeDL.UpdateChannel.STABLE)) {
            is YtDlpUpdater.UpdateResult.Updated ->
                Log.i(TAG, "Engine updated automatically to ${'$'}{result.version}")
            is YtDlpUpdater.UpdateResult.Failed ->
                Log.w(TAG, "Automatic engine update failed: ${'$'}{result.error}")
            else -> Unit
        }
    }

    private fun isUnmeteredConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "OtterApplication"
        private const val UPDATE_GRACE_MS = 10_000L
        lateinit var instance: OtterApplication
            private set
    }
}
