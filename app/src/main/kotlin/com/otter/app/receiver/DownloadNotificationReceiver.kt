package com.otter.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.otter.app.service.DownloadService

class DownloadNotificationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DLNotifyReceiver"
        const val ACTION_PAUSE = "com.otter.app.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.otter.app.RESUME_DOWNLOAD"
        const val ACTION_CANCEL = "com.otter.app.CANCEL_DOWNLOAD"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val url = intent.getStringExtra(DownloadService.EXTRA_URL)
            if (url.isNullOrBlank()) {
                Log.e(TAG, "onReceive: missing or blank URL extra")
                return
            }
            Log.i(TAG, "onReceive action=${intent.action} url=$url")

            val serviceAction = when (intent.action) {
                ACTION_PAUSE -> DownloadService.ACTION_PAUSE_DOWNLOAD
                ACTION_RESUME -> DownloadService.ACTION_RESUME_DOWNLOAD
                ACTION_CANCEL -> DownloadService.ACTION_CANCEL_DOWNLOAD
                else -> {
                    Log.w(TAG, "Unknown action: ${intent.action}")
                    return
                }
            }

            val serviceIntent = Intent(context, DownloadService::class.java).apply {
                action = serviceAction
                putExtra(DownloadService.EXTRA_URL, url)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "onReceive: service started for action=${intent.action}")
        } catch (e: Throwable) {
            Log.e(TAG, "onReceive: failed to handle notification action", e)
        }
    }
}
