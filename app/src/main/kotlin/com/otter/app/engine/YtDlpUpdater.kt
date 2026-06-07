package com.otter.app.engine

import android.content.Context
import android.util.Log
import com.otter.app.R
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YtDlpUpdater(private val context: Context) {

    companion object {
        private const val TAG = "YtDlpUpdater"
        private const val UPDATE_INTERVAL_MS = 86_400_000L // 24 hours
        private const val PREF_LAST_UPDATE = "ytdlp_last_update_time"
    }

    sealed class UpdateResult {
        object AlreadyUpToDate : UpdateResult()
        data class Updated(val version: String) : UpdateResult()
        data class Failed(val error: String) : UpdateResult()
    }

    suspend fun update(
        onProgress: (String) -> Unit = {}
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            onProgress(context.getString(R.string.update_checking))
            val result = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel.NIGHTLY
            )
            when (result) {
                YoutubeDL.UpdateStatus.DONE -> {
                    val version = getVersion()
                    saveUpdateTime()
                    onProgress(context.getString(R.string.update_success, version))
                    Log.i(TAG, "yt-dlp updated to $version (nightly)")
                    UpdateResult.Updated(version)
                }
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    saveUpdateTime()
                    onProgress(context.getString(R.string.update_already))
                    Log.i(TAG, "yt-dlp already up to date")
                    UpdateResult.AlreadyUpToDate
                }
                else -> {
                    UpdateResult.Failed(context.getString(R.string.update_failed_status, result))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            UpdateResult.Failed(e.message ?: context.getString(R.string.update_unknown_error))
        }
    }

    fun shouldAutoUpdate(): Boolean {
        val lastUpdate = context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
            .getLong(PREF_LAST_UPDATE, 0L)
        return System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS
    }

    private fun saveUpdateTime() {
        context.getSharedPreferences("ytdlp_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun getVersion(): String {
        return try {
            YoutubeDL.getInstance().version(context) ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
