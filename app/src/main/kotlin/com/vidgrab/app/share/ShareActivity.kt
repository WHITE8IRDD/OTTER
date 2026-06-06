package com.vidgrab.app.share

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vidgrab.app.MainActivity
import com.vidgrab.app.ui.theme.OtterTheme
import com.vidgrab.app.util.isValidUrl

class ShareActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ShareActivity"
        const val EXTRA_SHARED_URL = "shared_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)

        if (sharedUrl != null && sharedUrl.isValidUrl()) {
            Log.i(TAG, "Received URL from share: $sharedUrl")
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SHARED_URL, sharedUrl)
            }
            startActivity(mainIntent)
            finish()
        } else {
            Log.w(TAG, "No valid URL found in share intent")
            setContent {
                OtterTheme {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            finish()
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    extractUrlFromText(text)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.dataString
            }
            else -> null
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val urlRegex = Regex("""https?://\S+""")
        return urlRegex.find(text)?.value
    }
}
