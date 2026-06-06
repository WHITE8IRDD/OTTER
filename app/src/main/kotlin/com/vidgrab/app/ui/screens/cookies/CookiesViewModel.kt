package com.vidgrab.app.ui.screens.cookies

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.data.cookies.CookieManager
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
            _status.value = "Cannot save empty cookies"
            isError = true
            return
        }
        viewModelScope.launch {
            try {
                cookieManager.save(text)
                _hasCookies.value = true
                _status.value = "Cookies saved successfully!"
                isError = false
            } catch (e: Exception) {
                _status.value = "Failed to save: ${e.message}"
                isError = true
            }
        }
    }

    fun clear() {
        cookieManager.delete()
        _cookieText.value = ""
        _hasCookies.value = false
        _status.value = "Cookies cleared"
        isError = false
    }

    fun importFromFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val text = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (text.isBlank()) {
                    _status.value = "File is empty"
                    isError = true
                    return@launch
                }
                _cookieText.value = text
                _status.value = "Cookies loaded from file. Tap Save to apply."
                isError = false
            } catch (e: Exception) {
                _status.value = "Failed to read file: ${e.message}"
                isError = true
            }
        }
    }
}
