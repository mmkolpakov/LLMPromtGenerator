package com.promptgenerator.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promptgenerator.config.SettingsManager
import com.promptgenerator.config.AppSettings
import java.io.Closeable

class MainViewModel(
    private val settingsManager: SettingsManager
) : Closeable {
    // UI state
    var uiState by mutableStateOf(MainUiState())
        private set

    init {
        // Load settings
        val settings = settingsManager.getSettings()
        uiState = uiState.copy(
            isDarkTheme = settings.isDarkTheme
        )
    }

    /**
     * Navigates to the specified screen.
     */
    fun navigateTo(screen: Screen) {
        uiState = uiState.copy(currentScreen = screen)
    }

    /**
     * Updates the app theme.
     */
    fun setDarkTheme(isDark: Boolean) {
        uiState = uiState.copy(isDarkTheme = isDark)

        // Update settings
        val settings = settingsManager.getSettings()
        settingsManager.updateSettings(settings.copy(isDarkTheme = isDark))
    }

    /**
     * Releases resources
     */
    override fun close() {
        // No resources to release
    }
}

/**
 * UI state for the main screen.
 */
data class MainUiState(
    val currentScreen: Screen = Screen.Generator,
    val isDarkTheme: Boolean = false
)

/**
 * Screens in the application.
 */
enum class Screen {
    Generator,
    History,
    Settings,
    About
}