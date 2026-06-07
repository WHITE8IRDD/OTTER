package com.otter.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.util.Log
import java.util.Locale

class LocaleManager(base: Context) : ContextWrapper(base) {

    companion object {
        private const val PREFS_NAME = "otter_shared_prefs"
        private const val KEY_LANGUAGE = "app_language"
        private const val TAG = "LocaleManager"

        fun getPersistedLanguage(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "en") ?: "en"
        }

        fun setPersistedLanguage(context: Context, lang: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, lang).apply()
        }

        fun applyLanguage(context: Context, language: String): ContextWrapper {
            val locale = Locale.forLanguageTag(language)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            return ContextWrapper(context.createConfigurationContext(config))
        }

        fun persistAndRestart(activity: Activity, languageCode: String) {
            Log.i(TAG, "STEP 1: Language selected: $languageCode")

            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previous = prefs.getString(KEY_LANGUAGE, "none")
            Log.i(TAG, "STEP 2: Previous language: $previous")

            prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
            Log.i(TAG, "STEP 3: SharedPreferences committed: $languageCode")

            val verify = prefs.getString(KEY_LANGUAGE, "FAILED")
            Log.i(TAG, "STEP 4: Verify readback: $verify")

            Log.i(TAG, "STEP 5: Restarting activity with finish()+startActivity")
            val intent = activity.intent
            activity.finish()
            activity.overridePendingTransition(0, 0)
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
            Log.i(TAG, "STEP 6: Activity restart initiated")
        }
    }

    init {
        val lang = getPersistedLanguage(this)
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        resources.configuration.setLocale(locale)
        resources.configuration.setLayoutDirection(locale)
    }
}
