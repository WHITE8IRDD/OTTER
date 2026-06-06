package com.vidgrab.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vidgrab.app.service.DownloadService

class DownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.vidgrab.app.CANCEL_DOWNLOAD" -> {
                val url = intent.getStringExtra(DownloadService.EXTRA_URL) ?: return
                val serviceIntent = Intent(context, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_CANCEL_DOWNLOAD
                    putExtra(DownloadService.EXTRA_URL, url)
                }
                context.startService(serviceIntent)
            }
        }
    }
}
