package com.otter.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otter.app.data.cookies.CookieManager
import com.otter.app.share.ShareActivity
import com.otter.app.ui.navigation.NavGraph
import com.otter.app.ui.theme.ThemeViewModel
import com.otter.app.ui.theme.OtterTheme
import com.otter.app.util.FileHelper
import com.otter.app.util.LocaleManager

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleManager.getPersistedLanguage(newBase)
        super.attachBaseContext(LocaleManager.applyLanguage(newBase, lang))
    }

    companion object {
        const val EXTRA_NAVIGATE_TO = "navigate_to"
        const val NAVIGATE_COOKIE_LOGIN = "cookie_login"
        const val ACTION_SET_COOKIES = "com.otter.app.SET_COOKIES"
        const val EXTRA_COOKIES_CONTENT = "cookies_content"
        const val EXTRA_IS_BASE64 = "is_base64"
        private const val TAG = "MainActivity"
    }

    private val _sharedUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val currentLocale = resources.configuration.locales[0]
        val isRtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        Log.i(TAG, "Current locale: ${currentLocale.language} | LayoutDirection RTL: $isRtl")

        _sharedUrl.value = intent?.getStringExtra(ShareActivity.EXTRA_SHARED_URL)
        val navigateTo = intent?.getStringExtra(EXTRA_NAVIGATE_TO)

        handleNotificationAction(intent)
        handleCookieIntent(intent)

        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val isDarkMode by themeViewModel.isDarkMode.collectAsState()
            val accentColor by themeViewModel.accentColor.collectAsState()
            val sharedUrl = _sharedUrl.value

            OtterTheme(
                darkTheme = isDarkMode,
                accentColor = accentColor
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NavGraph(
                        sharedUrl = sharedUrl,
                        navigateToCookieLogin = navigateTo == NAVIGATE_COOKIE_LOGIN,
                        onSharedUrlConsumed = { _sharedUrl.value = null },
                        themeViewModel = themeViewModel
                    )
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val lang = LocaleManager.getPersistedLanguage(this)
        newConfig.setLayoutDirection(java.util.Locale.forLanguageTag(lang))
        super.onConfigurationChanged(newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newUrl = intent.getStringExtra(ShareActivity.EXTRA_SHARED_URL)
        if (!newUrl.isNullOrEmpty()) {
            _sharedUrl.value = newUrl
        }
        handleNotificationAction(intent)
        handleCookieIntent(intent)
    }

    private fun handleNotificationAction(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        val filePath = intent.getStringExtra("file_path") ?: return

        when (action) {
            "open_file" -> FileHelper.openFile(this, filePath)
            "share_file" -> FileHelper.shareFile(this, filePath)
            "locate_file" -> FileHelper.locateFile(this, filePath)
        }
    }

    private fun handleCookieIntent(intent: Intent?) {
        if (intent?.action != ACTION_SET_COOKIES) return
        val content = intent.getStringExtra(EXTRA_COOKIES_CONTENT) ?: return
        val isBase64 = intent.getBooleanExtra(EXTRA_IS_BASE64, false)
        val finalContent = if (isBase64) {
            String(Base64.decode(content, Base64.DEFAULT))
        } else {
            content
        }
        CookieManager(this).save(finalContent)
        Log.i(TAG, "Cookies saved via intent")
    }
}
