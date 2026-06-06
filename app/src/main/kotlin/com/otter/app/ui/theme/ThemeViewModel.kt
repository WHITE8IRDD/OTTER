package com.otter.app.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otter.app.data.datastore.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = UserPreferences(application)

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _accentColor = MutableStateFlow(Color.Black)
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
            combine(
                preferences.accentColor,
                preferences.darkMode
            ) { savedColor, isDark ->
                if (savedColor == -1) {
                    if (isDark) Color.White else Color.Black
                } else {
                    Color(savedColor)
                }
            }.collect { color ->
                _accentColor.value = color
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
