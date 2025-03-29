package com.promptgenerator.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promptgenerator.config.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

class MainViewModel(
    private val settingsManager: SettingsManager
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var uiState by mutableStateOf(MainUiState())
        private set

    init {
        try {
            val settings = settingsManager.getSettings()
            uiState = uiState.copy(
                isDarkTheme = settings.isDarkTheme
            )
        } catch (e: Exception) {
            logger.error("Error loading theme setting", e)
        }
    }

    fun navigateTo(screen: Screen) {
        uiState = uiState.copy(currentScreen = screen)
    }

    fun setDarkTheme(isDark: Boolean) {
        uiState = uiState.copy(isDarkTheme = isDark)

        viewModelScope.launch {
            try {
                // Изменим порядок получения настроек и сохранения
                val settings = settingsManager.getSettings()
                val updatedSettings = settings.copy(isDarkTheme = isDark)
                settingsManager.updateSettings(updatedSettings)
            } catch (e: Exception) {
                logger.error("Error updating theme setting", e)
            }
        }
    }

    override fun close() {
        viewModelScope.cancel()
    }
}

data class MainUiState(
    val currentScreen: Screen = Screen.Generator,
    val isDarkTheme: Boolean = false
)

enum class Screen {
    Generator,
    History,
    Settings,
    About
}