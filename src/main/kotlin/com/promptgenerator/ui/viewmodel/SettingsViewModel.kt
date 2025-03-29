package com.promptgenerator.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promptgenerator.config.AppSettings
import com.promptgenerator.config.LLMConfig
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.config.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

class SettingsViewModel(
    private val settingsManager: SettingsManager,
    private val llmConfig: LLMConfig
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // UI state
    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        loadSettings()
    }

    /**
     * Loads application settings
     */
    private fun loadSettings() {
        // Load app settings
        val settings = settingsManager.getSettings()

        // Load provider configs
        val providers = llmConfig.providers.map { (name, config) ->
            ProviderSettingsUiState(
                name = name,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                defaultModel = config.defaultModel,
                isEditing = false
            )
        }

        uiState = uiState.copy(
            maxCombinations = settings.maxCombinations,
            showPartialResults = settings.showPartialResults,
            resultsLimit = settings.resultsLimit,
            saveResultsPath = settings.saveResultsPath,
            saveTemplatesPath = settings.saveTemplatesPath,
            providers = providers,
            defaultProvider = llmConfig.defaultProvider
        )
    }

    /**
     * Updates application settings
     */
    fun updateSettings(
        maxCombinations: Int,
        showPartialResults: Boolean,
        resultsLimit: Int,
        saveResultsPath: String,
        saveTemplatesPath: String,
        exportFileFormat: String // Новый параметр
    ) {
        viewModelScope.launch {
            try {
                // Update settings
                val settings = AppSettings(
                    isDarkTheme = settingsManager.getSettings().isDarkTheme,
                    maxCombinations = maxCombinations,
                    showPartialResults = showPartialResults,
                    resultsLimit = resultsLimit,
                    saveResultsPath = saveResultsPath,
                    saveTemplatesPath = saveTemplatesPath,
                    exportFileFormat = exportFileFormat
                )

                settingsManager.updateSettings(settings)

                // Update UI
                uiState = uiState.copy(
                    maxCombinations = maxCombinations,
                    showPartialResults = showPartialResults,
                    resultsLimit = resultsLimit,
                    saveResultsPath = saveResultsPath,
                    saveTemplatesPath = saveTemplatesPath,
                    exportFileFormat = exportFileFormat,
                    successMessage = "Settings updated successfully"
                )
            } catch (e: Exception) {
                logger.error("Error updating settings", e)
                uiState = uiState.copy(
                    errorMessage = "Error updating settings: ${e.message}"
                )
            }
        }
    }

    /**
     * Updates a provider configuration
     */
    fun updateProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String
    ) {
        viewModelScope.launch {
            try {
                // Create updated provider config
                val existingConfig = llmConfig.providers[name]
                if (existingConfig != null) {
                    val updatedConfig = existingConfig.copy(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        defaultModel = defaultModel
                    )

                    // Update config
                    settingsManager.updateProviderConfig(name, updatedConfig)

                    // Update UI
                    val updatedProviders = uiState.providers.map {
                        if (it.name == name) {
                            it.copy(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                defaultModel = defaultModel,
                                isEditing = false
                            )
                        } else {
                            it
                        }
                    }

                    uiState = uiState.copy(
                        providers = updatedProviders,
                        successMessage = "Provider updated successfully"
                    )
                }
            } catch (e: Exception) {
                logger.error("Error updating provider", e)
                uiState = uiState.copy(
                    errorMessage = "Error updating provider: ${e.message}"
                )
            }
        }
    }

    /**
     * Sets the default provider
     */
    fun setDefaultProvider(providerName: String) {
        viewModelScope.launch {
            try {
                // Update the default provider in the config
                val updatedConfig = llmConfig.copy(defaultProvider = providerName)

                // Save the updated config
                if (settingsManager.saveConfig(updatedConfig)) {
                    // Update UI
                    uiState = uiState.copy(
                        defaultProvider = providerName,
                        successMessage = "Default provider set to $providerName"
                    )
                } else {
                    throw Exception("Failed to save configuration")
                }
            } catch (e: Exception) {
                logger.error("Error setting default provider", e)
                uiState = uiState.copy(
                    errorMessage = "Error setting default provider: ${e.message}"
                )
            }
        }
    }

    /**
     * Clears error or success message
     */
    fun clearMessage() {
        uiState = uiState.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    /**
     * Releases resources
     */
    override fun close() {
        // No resources to release
    }
}

/**
 * UI state for settings screen
 */
data class SettingsUiState(
    val maxCombinations: Int = 1000,
    val showPartialResults: Boolean = true,
    val resultsLimit: Int = 100,
    val saveResultsPath: String = "generated_prompts",
    val saveTemplatesPath: String = "templates",
    val exportFileFormat: String = "md",
    val providers: List<ProviderSettingsUiState> = emptyList(),
    val defaultProvider: String = "gemini",

    val errorMessage: String? = null,
    val successMessage: String? = null
)

/**
 * UI state for provider settings
 */
data class ProviderSettingsUiState(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val isEditing: Boolean
)