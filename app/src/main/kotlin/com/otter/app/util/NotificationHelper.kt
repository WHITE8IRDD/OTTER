package com.otter.app.util

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.otter.app.MainActivity
import com.otter.app.R
import com.otter.app.data.model.DownloadState
import com.otter.app.service.DownloadService

object NotificationHelper {

    const val CHANNEL_DOWNLOAD = "channel_dl_v5"
    const val CHANNEL_COMPLETE = "channel_cmpl_v5"
    const val NOTIFICATION_ID_DOWNLOAD = 1001
    const val NOTIFICATION_ID_COMPLETE = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Delete all old channel IDs that might have been silent
        for (oldId in listOf("channel_download", "channel_complete",
                "channel_download_v2", "channel_complete_v2",
                "channel_dl_v3", "channel_cmpl_v3",
                "channel_dl_v4", "channel_cmpl_v4")) {
            try { manager.deleteNotificationChannel(oldId) } catch (e: Exception) {
                Log.v(TAG, "Failed to delete old channel $oldId: ${e.message}")
            }
        }

        val soundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOAD,
            context.getString(R.string.notification_channel_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_download_desc)
            setShowBadge(false)
            setSound(null, null)
        }

        val completeChannel = NotificationChannel(
            CHANNEL_COMPLETE,
            context.getString(R.string.notification_channel_complete),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_complete_desc)
            setSound(soundUri, audioAttrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            enableLights(true)
        }

        manager.createNotificationChannel(downloadChannel)
        manager.createNotificationChannel(completeChannel)
    }

    private const val TAG = "NotificationHelper"

    fun playTestSound(context: Context) {
        playNotificationSound(context)
    }

    private fun playNotificationSound(context: Context) {
        Log.i(TAG, "playNotificationSound called")
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            Log.i(TAG, "Notification URI: $uri")
            // Try with alarm usage first (bypasses MIUI silent restrictions)
            val player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                setLooping(false)
                setOnPreparedListener {
                    Log.i(TAG, "MediaPlayer prepared, starting playback")
                    start()
                }
                setOnCompletionListener {
                    Log.i(TAG, "MediaPlayer playback completed")
                    it.release()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    mp.release()
                    fallbackVibrate(context)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer failed", e)
            fallbackVibrate(context)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fallbackVibrate(context: Context) {
        Log.i(TAG, "fallbackVibrate called")
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                Log.i(TAG, "Vibrating device")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 300, 100, 400), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 200, 100, 300, 100, 400), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed", e)
        }
    }

    fun openNotificationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification settings", e)
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        state: DownloadState.Downloading,
        largeIcon: Bitmap? = null,
        url: String = ""
    ): android.app.Notification {
        val pauseIntent = Intent(context, com.otter.app.receiver.DownloadNotificationReceiver::class.java).apply {
            action = com.otter.app.receiver.DownloadNotificationReceiver.ACTION_PAUSE
            putExtra(DownloadService.EXTRA_URL, url)
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            context, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, com.otter.app.receiver.DownloadNotificationReceiver::class.java).apply {
            action = "com.otter.app.CANCEL_DOWNLOAD"
            putExtra(DownloadService.EXTRA_URL, url)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = (state.progress * 100).toInt()
        val contentText = context.getString(R.string.notif_progress_content, percent, state.sizeText)
        val subText = context.getString(R.string.notif_progress_sub, state.speedText, state.etaText)

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSilent(true)
            .setLargeIcon(largeIcon)
            .setOnlyAlertOnce(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$contentText\n$subText")
            )
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.pause), pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.stop), cancelPendingIntent)
            .build()
    }

    fun buildPausedNotification(
        context: Context,
        title: String,
        state: DownloadState.Paused,
        largeIcon: Bitmap? = null
    ): android.app.Notification {
        val resumeIntent = Intent(context, com.otter.app.receiver.DownloadNotificationReceiver::class.java).apply {
            action = com.otter.app.receiver.DownloadNotificationReceiver.ACTION_RESUME
            putExtra(DownloadService.EXTRA_URL, state.url)
        }
        val resumePendingIntent = PendingIntent.getBroadcast(
            context, 3, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(context, com.otter.app.receiver.DownloadNotificationReceiver::class.java).apply {
            action = "com.otter.app.CANCEL_DOWNLOAD"
            putExtra(DownloadService.EXTRA_URL, state.url)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 4, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = (state.progress * 100).toInt()

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(context.getString(R.string.notif_paused_title, title))
            .setContentText(context.getString(R.string.notif_paused_content, percent))
            .setProgress(100, percent, false)
            .setOngoing(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.resume), resumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.stop), cancelPendingIntent)
            .build()
    }

    fun showCompleteNotification(
        context: Context,
        title: String,
        filePath: String,
        largeIcon: Bitmap? = null
    ) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openFileIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("action", "open_file")
            putExtra("file_path", filePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openFilePendingIntent = PendingIntent.getActivity(
            context, 1, openFileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shareIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("action", "share_file")
            putExtra("file_path", filePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sharePendingIntent = PendingIntent.getActivity(
            context, 2, shareIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val locateIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("action", "locate_file")
            putExtra("file_path", filePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val locatePendingIntent = PendingIntent.getActivity(
            context, 3, locateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.download_complete))
            .setContentText(title)
            .setContentIntent(openPendingIntent)
            .setLargeIcon(largeIcon)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .addAction(android.R.drawable.ic_menu_zoom, context.getString(R.string.notif_open), openFilePendingIntent)
            .addAction(android.R.drawable.ic_menu_share, context.getString(R.string.notif_share), sharePendingIntent)
            .addAction(android.R.drawable.ic_menu_directions, context.getString(R.string.notif_show_folder), locatePendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        playNotificationSound(context)
    }

    fun showErrorNotification(
        context: Context,
        title: String,
        error: String,
        filePath: String? = null
    ) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val retryIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("action", "retry_download")
            putExtra("file_path", filePath)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val retryPendingIntent = PendingIntent.getActivity(
            context, 4, retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText("$title: $error")
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .addAction(android.R.drawable.ic_menu_revert, context.getString(R.string.notif_retry), retryPendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_COMPLETE + 1, notification)
        playNotificationSound(context)
    }
}
