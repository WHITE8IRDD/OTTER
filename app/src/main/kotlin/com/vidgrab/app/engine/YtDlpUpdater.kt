package com.vidgrab.app.engine

import android.content.Context
import android.util.Log
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
            onProgress("Checking for updates...")
            val result = YoutubeDL.getInstance().updateYoutubeDL(
                context,
                YoutubeDL.UpdateChannel.NIGHTLY
            )
            when (result) {
                YoutubeDL.UpdateStatus.DONE -> {
                    val version = getVersion()
                    saveUpdateTime()
                    onProgress("Updated to $version")
                    Log.i(TAG, "yt-dlp updated to $version (nightly)")
                    UpdateResult.Updated(version)
                }
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                    saveUpdateTime()
                    onProgress("Already up to date")
                    Log.i(TAG, "yt-dlp already up to date")
                    UpdateResult.AlreadyUpToDate
                }
                else -> {
                    UpdateResult.Failed("Update failed with status: $result")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            UpdateResult.Failed(e.message ?: "Unknown error")
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
