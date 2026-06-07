package com.otter.app.ui.screens.cookies

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.R
import com.otter.app.data.cookies.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CookiesViewModel(application: Application) : AndroidViewModel(application) {

    private val cookieManager = CookieManager(application)

    private val _cookieText = MutableStateFlow("")
    val cookieText: StateFlow<String> = _cookieText.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _hasCookies = MutableStateFlow(cookieManager.exists())
    val hasCookies: StateFlow<Boolean> = _hasCookies.asStateFlow()

    var isError = false
        private set

    init {
        cookieManager.load()?.let { content ->
            _cookieText.value = content
        }
    }

    fun onCookieTextChanged(text: String) {
        _cookieText.value = text
    }

    fun save() {
        val text = _cookieText.value.trim()
        if (text.isEmpty()) {
            _status.value = getApplication<Application>().getString(R.string.cookies_empty_error)
            isError = true
            return
        }
        viewModelScope.launch {
            try {
                cookieManager.save(text)
                _hasCookies.value = true
                _status.value = getApplication<Application>().getString(R.string.cookies_saved)
                isError = false
            } catch (e: Exception) {
                _status.value = getApplication<Application>().getString(R.string.cookies_save_failed, e.message)
                isError = true
            }
        }
    }

    fun clear() {
        cookieManager.delete()
        _cookieText.value = ""
        _hasCookies.value = false
        _status.value = getApplication<Application>().getString(R.string.cookies_cleared)
        isError = false
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    _status.value = getApplication<Application>().getString(R.string.file_empty)
                    isError = true
                    return@launch
                }
                _cookieText.value = text
                _status.value = getApplication<Application>().getString(R.string.cookies_loaded)
                isError = false
            } catch (e: Exception) {
                _status.value = getApplication<Application>().getString(R.string.file_read_failed, e.message)
                isError = true
            }
        }
    }
}
