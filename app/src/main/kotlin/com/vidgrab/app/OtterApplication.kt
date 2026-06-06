package com.vidgrab.app

import android.app.Application
import android.util.Log
import com.vidgrab.app.engine.FormatParser
import com.vidgrab.app.engine.YtDlpUpdater
import com.vidgrab.app.util.NotificationHelper
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
            autoUpdateYtDlp()
        }
    }

    private suspend fun initAVEngine() = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().init(this@OtterApplication)
            FFmpeg.getInstance().init(this@OtterApplication)
            Aria2c.getInstance().init(this@OtterApplication)
            Log.i(TAG, "Engine initialized successfully")
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize engine", e)
        }
    }

    private suspend fun autoUpdateYtDlp() {
        val updater = YtDlpUpdater(this)
        if (updater.shouldAutoUpdate() || updater.getVersion() == "Unknown") {
            Log.i(TAG, "Auto-updating engine (nightly channel)...")
            updater.update { progress ->
                Log.i(TAG, "Engine update: $progress")
            }
        }
    }

    companion object {
        private const val TAG = "OtterApplication"
        lateinit var instance: OtterApplication
            private set
    }
}
