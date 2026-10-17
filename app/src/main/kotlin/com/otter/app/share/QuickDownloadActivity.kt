package com.otter.app.share

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.otter.app.R
import com.otter.app.ui.theme.OtterTheme
import com.otter.app.util.extractUrlFromText
import com.otter.app.util.isValidUrl

/**
 * The Quick Download share target: a small window that opens *over* the sharing app.
 *
 * Instagram's share sheet (and TikTok's, YouTube's, …) hands this activity a link.
 * The activity answers with the compact selection window in [QuickDownloadWindow] —
 * the post's files, pickable, one Download button — and closes back into the app the
 * user was browsing. It never launches this app's main screen: that redirect was the
 * regression that made "Quick Download" drag the user out of Instagram.
 *
 * The downloads themselves are started on `DownloadService`, a foreground service, so
 * they survive this activity closing a moment later.
 */
class QuickDownloadActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QuickDownloadActivity"
    }

    private lateinit var viewModel: QuickDownloadViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)
        if (sharedUrl == null || !sharedUrl.isValidUrl()) {
            Log.w(TAG, "Quick Download received no usable URL")
            Toast.makeText(this, R.string.no_valid_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.i(TAG, "Quick Download window for: $sharedUrl")

        viewModel = ViewModelProvider(this)[QuickDownloadViewModel::class.java]
        // After a configuration change the ViewModel already holds the read (or its
        // result), so only a fresh launch starts one.
        if (savedInstanceState == null) viewModel.start(sharedUrl)

        setContent {
            OtterTheme {
                val state by viewModel.state.collectAsState()

                // Started: close back into the app underneath — Instagram keeps its place.
                LaunchedEffect(state) {
                    if (state is QuickDownloadUiState.Done) {
                        Toast.makeText(
                            this@QuickDownloadActivity,
                            getString(R.string.quick_download_started),
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }

                QuickDownloadWindow(
                    state = state,
                    onToggle = viewModel::toggle,
                    onSelectAll = viewModel::selectAll,
                    onClear = viewModel::clearSelection,
                    onSelectKind = viewModel::selectKind,
                    onDownload = viewModel::download,
                    onRetry = viewModel::retry,
                    onDismiss = {
                        viewModel.dismiss()
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second share while the window is open replaces the first.
        val sharedUrl = extractUrlFromIntent(intent)
        if (sharedUrl != null && sharedUrl.isValidUrl()) {
            Log.i(TAG, "Quick Download window re-targeted to: $sharedUrl")
            if (::viewModel.isInitialized) viewModel.start(sharedUrl)
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { it.extractUrlFromText() }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    }
}
