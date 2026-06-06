package com.vidgrab.app.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.data.datastore.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = UserPreferences(application)

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _accentColor = MutableStateFlow(Periwinkle)
    val accentColor: StateFlow<Color> = _accentColor.asStateFlow()

    private val _tintLogo = MutableStateFlow(true)
    val tintLogo: StateFlow<Boolean> = _tintLogo.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.darkMode.collect { dark ->
                _isDarkMode.value = dark
            }
        }
        viewModelScope.launch {
            preferences.accentColor.collect { argb ->
                _accentColor.value = if (argb == -1) Periwinkle else Color(argb)
            }
        }
        viewModelScope.launch {
            preferences.tintLogo.collect { enabled ->
                _tintLogo.value = enabled
            }
        }
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val newValue = !_isDarkMode.value
            _isDarkMode.value = newValue
            preferences.setDarkMode(newValue)
        }
    }

    fun setAccentColor(color: Color) {
        viewModelScope.launch {
            _accentColor.value = color
            preferences.setAccentColor(color.toArgb())
        }
    }

    fun setAccentColorFromPreset(preset: AccentPreset) {
        setAccentColor(preset.light)
    }

    fun toggleTintLogo() {
        viewModelScope.launch {
            val newValue = !_tintLogo.value
            _tintLogo.value = newValue
            preferences.setTintLogo(newValue)
        }
    }
}
