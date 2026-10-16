package com.otter.app.share

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.otter.app.R
import com.otter.app.engine.InstagramUrl
import com.otter.app.engine.MediaUrlNormalizer
import com.otter.app.engine.TikTokPhotoParser
import com.otter.app.service.DownloadService
import com.otter.app.ui.theme.OtterTheme
import com.otter.app.util.extractUrlFromText
import com.otter.app.util.isValidUrl

/**
 * The share-sheet target that downloads without opening the app.
 *
 * Two jobs, and the split between them is the whole design:
 *
 *  * a link whose media needs no choosing (a video) is handed to [DownloadService] immediately, from
 *    `onCreate` itself — this used to wait for the engine to initialise and for a preferences read
 *    first, and an activity that has already faded out (`noHistory`) is not a place from which
 *    Android guarantees a foreground service may be started;
 *  * a link that names a gallery post (Instagram, a TikTok photo post, a page of pictures) opens
 *    [QuickSelectionOverlay] *in this activity*, so the user picks slides above the app they shared
 *    from instead of being moved into OTTER's main window and left to find their way back.
 */
class QuickDownloadActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickDownloadActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)
        if (sharedUrl == null || !sharedUrl.isValidUrl()) {
            Toast.makeText(this, getString(R.string.no_valid_url), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.i(TAG, "QuickDownload received URL: ${sharedUrl.substringBefore('?')}")

        // A gallery post needs a selection window: the slides are chosen, not guessed. This is the
        // app's one Instagram URL rule (posts, reels and the forwarded /share/ forms), plus TikTok
        // photo posts and plain image links. Everything else is a video the engine can take as is.
        if (TikTokPhotoParser.isPhotoModeUrl(sharedUrl) ||
            MediaUrlNormalizer.isDirectImageUrl(sharedUrl) ||
            InstagramUrl.isMediaUrl(sharedUrl)
        ) {
            Log.i(TAG, "Opening the selectable media flow over the sharing app")
            openSelectionOverlay(sharedUrl)
            return
        }

        startVideoDownload(sharedUrl)
    }

    /**
     * The selection window. No `MainActivity` intent, no task switch: the window is this activity,
     * drawn on the translucent theme the manifest already gives it, so closing it leaves the user
     * where they were.
     */
    private fun openSelectionOverlay(url: String) {
        setContent {
            OtterTheme {
                QuickSelectionOverlay(url = url, onClose = { finish() })
            }
        }
    }

    /** Hands a video link to the foreground service and gets out of the way. */
    private fun startVideoDownload(url: String) {
        val serviceIntent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_FORMAT_ID, DownloadService.QUICK_DOWNLOAD_FORMAT)
            putExtra(DownloadService.EXTRA_TITLE, getString(R.string.quick_download_title))
            putExtra(DownloadService.EXTRA_THUMBNAIL_URL, "")
            putExtra(DownloadService.EXTRA_SAVE_THUMBNAIL, false)
            // The output directory and the subtitle settings are read by the service, not here: the
            // one thing this activity must not do before starting a foreground service is await
            // something that touches disk or a datastore.
            putExtra(DownloadService.EXTRA_QUICK_DOWNLOAD, true)
        }
        runCatching { startForegroundService(serviceIntent) }
            .onFailure { error ->
                Log.e(TAG, "Could not start the download service", error)
                Toast.makeText(
                    this,
                    getString(R.string.quick_download_failed, error.message ?: getString(R.string.error_generic_with_hint)),
                    Toast.LENGTH_LONG
                ).show()
            }
        Toast.makeText(this, getString(R.string.quick_download_started), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.extractUrlFromText()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}
