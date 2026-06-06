package com.otter.app.data.datastore

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "otter_prefs")

class UserPreferences(private val context: Context) {

    companion object {
        val KEY_DOWNLOAD_PATH = stringPreferencesKey("download_path")
        val KEY_DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val KEY_AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        val KEY_USE_ARIA2C = booleanPreferencesKey("use_aria2c")
        val KEY_MAX_CONCURRENT = stringPreferencesKey("max_concurrent")
        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_EXTRACT_AUDIO = booleanPreferencesKey("extract_audio")
        val KEY_AUDIO_FORMAT = stringPreferencesKey("audio_format")
        val KEY_DOWNLOAD_SUBTITLES = booleanPreferencesKey("download_subtitles")
        val KEY_SUBTITLE_LANG = stringPreferencesKey("subtitle_lang")
        val KEY_EMBED_THUMBNAIL = booleanPreferencesKey("embed_thumbnail")
        val KEY_EMBED_METADATA = booleanPreferencesKey("embed_metadata")
        val KEY_RATE_LIMIT = booleanPreferencesKey("rate_limit")
        val KEY_MAX_RATE = stringPreferencesKey("max_rate")
        val KEY_PLAYLIST = booleanPreferencesKey("playlist")
        val KEY_OUTPUT_TEMPLATE = stringPreferencesKey("output_template")
        val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        val KEY_PROXY = booleanPreferencesKey("proxy")
        val KEY_PROXY_URL = stringPreferencesKey("proxy_url")
        val KEY_RETRIES = intPreferencesKey("retries")
        val KEY_CONCURRENT_FRAGMENTS = intPreferencesKey("concurrent_fragments")
        val KEY_COOKIES_ENABLED = booleanPreferencesKey("cookies_enabled")
        val KEY_COOKIE_CONTENT = stringPreferencesKey("cookie_content")
        val KEY_ACCENT_COLOR = intPreferencesKey("accent_color")
        val KEY_TINT_LOGO = booleanPreferencesKey("tint_logo")
    }

    val downloadPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_PATH] ?: getDefaultDownloadPath()
    }

    val defaultQuality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_QUALITY] ?: "best"
    }

    val autoDownload: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_DOWNLOAD] ?: false
    }

    val useAria2c: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_USE_ARIA2C] ?: false
    }

    val darkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DARK_MODE] ?: false
    }

    val extractAudio: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EXTRACT_AUDIO] ?: false
    }

    val audioFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUDIO_FORMAT] ?: "mp3"
    }

    val downloadSubtitles: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DOWNLOAD_SUBTITLES] ?: false
    }

    val subtitleLang: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SUBTITLE_LANG] ?: "en.*,.*-orig"
    }

    val embedThumbnail: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EMBED_THUMBNAIL] ?: false
    }

    val embedMetadata: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_EMBED_METADATA] ?: true
    }

    val rateLimit: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_RATE_LIMIT] ?: false
    }

    val maxRate: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAX_RATE] ?: "1000"
    }

    val playlist: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PLAYLIST] ?: false
    }

    val outputTemplate: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OUTPUT_TEMPLATE] ?: "%(title)s.%(ext)s"
    }

    val userAgent: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_AGENT] ?: ""
    }

    val proxy: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROXY] ?: false
    }

    val proxyUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PROXY_URL] ?: ""
    }

    val retries: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_RETRIES] ?: 10
    }

    val concurrentFragments: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONCURRENT_FRAGMENTS] ?: 8
    }

    val cookiesEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_COOKIES_ENABLED] ?: false
    }

    val cookieContent: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_COOKIE_CONTENT] ?: ""
    }

    val accentColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: -1
    }

    val tintLogo: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TINT_LOGO] ?: true
    }

    suspend fun setDownloadPath(path: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DOWNLOAD_PATH] = path }
    }

    suspend fun setDefaultQuality(quality: String) {
        context.dataStore.edit { prefs -> prefs[KEY_DEFAULT_QUALITY] = quality }
    }

    suspend fun setAutoDownload(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_DOWNLOAD] = enabled }
    }

    suspend fun setUseAria2c(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_USE_ARIA2C] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DARK_MODE] = enabled }
    }

    suspend fun setExtractAudio(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_EXTRACT_AUDIO] = enabled }
    }

    suspend fun setAudioFormat(format: String) {
        context.dataStore.edit { prefs -> prefs[KEY_AUDIO_FORMAT] = format }
    }

    suspend fun setDownloadSubtitles(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DOWNLOAD_SUBTITLES] = enabled }
    }

    suspend fun setSubtitleLang(lang: String) {
        context.dataStore.edit { prefs -> prefs[KEY_SUBTITLE_LANG] = lang }
    }

    suspend fun setEmbedThumbnail(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_EMBED_THUMBNAIL] = enabled }
    }

    suspend fun setEmbedMetadata(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_EMBED_METADATA] = enabled }
    }

    suspend fun setRateLimit(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_RATE_LIMIT] = enabled }
    }

    suspend fun setMaxRate(rate: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MAX_RATE] = rate }
    }

    suspend fun setPlaylist(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_PLAYLIST] = enabled }
    }

    suspend fun setOutputTemplate(template: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OUTPUT_TEMPLATE] = template }
    }

    suspend fun setUserAgent(ua: String) {
        context.dataStore.edit { prefs -> prefs[KEY_USER_AGENT] = ua }
    }

    suspend fun setProxy(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_PROXY] = enabled }
    }

    suspend fun setProxyUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_PROXY_URL] = url }
    }

    suspend fun setRetries(retries: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_RETRIES] = retries }
    }

    suspend fun setConcurrentFragments(n: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_CONCURRENT_FRAGMENTS] = n }
    }

    suspend fun setCookiesEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_COOKIES_ENABLED] = enabled }
    }

    suspend fun setRecentUrls(urls: List<String>) {
        context.dataStore.edit { prefs ->
            for (i in 1..5) {
                val key = stringPreferencesKey("recent_url_$i")
                if (i <= urls.size) prefs[key] = urls[i - 1] else prefs.remove(key)
            }
        }
    }

    suspend fun getRecentUrls(): List<String> {
        return context.dataStore.data.first().let { prefs ->
            (1..5).mapNotNull { i ->
                prefs[stringPreferencesKey("recent_url_$i")]
            }
        }
    }

    suspend fun setCookieContent(content: String) {
        context.dataStore.edit { prefs -> prefs[KEY_COOKIE_CONTENT] = content }
    }

    suspend fun setAccentColor(colorArgb: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_ACCENT_COLOR] = colorArgb }
    }

    suspend fun setTintLogo(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_TINT_LOGO] = enabled }
    }

    suspend fun getAccentColorOnce(): Int = accentColor.first()

    fun getDefaultDownloadPath(): String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Otter/"
    }

    suspend fun getDownloadPathOnce(): String = downloadPath.first()
    suspend fun getExtractAudioOnce(): Boolean = extractAudio.first()
    suspend fun getAudioFormatOnce(): String = audioFormat.first()
    suspend fun getDownloadSubtitlesOnce(): Boolean = downloadSubtitles.first()
    suspend fun getSubtitleLangOnce(): String = subtitleLang.first()
    suspend fun getEmbedThumbnailOnce(): Boolean = embedThumbnail.first()
    suspend fun getEmbedMetadataOnce(): Boolean = embedMetadata.first()
    suspend fun getRateLimitOnce(): Boolean = rateLimit.first()
    suspend fun getMaxRateOnce(): String = maxRate.first()
    suspend fun getPlaylistOnce(): Boolean = playlist.first()
    suspend fun getOutputTemplateOnce(): String = outputTemplate.first()
    suspend fun getUserAgentOnce(): String = userAgent.first()
    suspend fun getProxyOnce(): Boolean = proxy.first()
    suspend fun getProxyUrlOnce(): String = proxyUrl.first()
    suspend fun getRetriesOnce(): Int = retries.first()
    suspend fun getConcurrentFragmentsOnce(): Int = concurrentFragments.first()
    suspend fun getUseAria2cOnce(): Boolean = useAria2c.first()
    suspend fun getCookiesEnabledOnce(): Boolean = cookiesEnabled.first()
    suspend fun getCookieContentOnce(): String = cookieContent.first()
}
