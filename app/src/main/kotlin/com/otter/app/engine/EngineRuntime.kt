package com.otter.app.engine

import android.content.Context
import com.otter.app.BuildConfig
import com.otter.app.R
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Initialise once; an engine update must never delete files under running Python processes. */
object EngineRuntime {
    private val lock = ReentrantReadWriteLock(true)
    @Volatile private var ready = false

    fun initialize(context: Context) = lock.write {
        if (ready) return@write
        val app = context.applicationContext
        val engine = YoutubeDL.getInstance()
        val prefs = app.getSharedPreferences("outter-engine", Context.MODE_PRIVATE)
        val baseline = BuildConfig.BUNDLED_YTDLP_VERSION
        // Migrate old installs too: wrapper init alone only copies the bundled engine on a
        // fresh install. Preserve newer user-installed stable/nightly versions.
        val installed = engine.version(app).orEmpty()
        val date = installed.removePrefix("v").take(10)
        if (prefs.getString("baseline", "") != baseline && (date.isBlank() || date < baseline)) {
            val dir = File(File(app.noBackupFilesDir, YoutubeDL.baseName), YoutubeDL.ytdlpDirName)
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create engine directory" }
            val temporary = File(dir, "yt-dlp.new")
            try {
                app.resources.openRawResource(R.raw.outter_ytdlp).use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                check(temporary.renameTo(File(dir, YoutubeDL.ytdlpBin))) { "Cannot install bundled engine" }
            } finally { temporary.delete() }
        }
        engine.init(app)
        FFmpeg.getInstance().init(app)
        prefs.edit().putString("baseline", baseline).apply()
        ready = true
    }

    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse = lock.read {
        check(ready) { "Download engine is not initialized; please retry" }
        YoutubeDL.getInstance().execute(request, processId, callback)
    }

    fun update(context: Context, channel: YoutubeDL.UpdateChannel): YoutubeDL.UpdateStatus? = lock.write {
        initialize(context)
        YoutubeDL.getInstance().updateYoutubeDL(context, channel)
    }
}
