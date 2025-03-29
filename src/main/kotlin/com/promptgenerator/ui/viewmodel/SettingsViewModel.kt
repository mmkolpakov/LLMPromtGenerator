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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

class SettingsViewModel(
    private val settingsManager: SettingsManager,
    private val llmConfig: LLMConfig
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var uiState by mutableStateOf(SettingsUiState())
        private set

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val settings = settingsManager.getSettings()

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
                    exportFileFormat = settings.exportFileFormat,
                    providers = providers,
                    defaultProvider = llmConfig.defaultProvider
                )
            } catch (e: Exception) {
                logger.error("Error loading settings", e)
                uiState = uiState.copy(
                    errorMessage = "Error loading settings: ${e.message}"
                )
            }
        }
    }

    fun updateSettings(
        maxCombinations: Int,
        showPartialResults: Boolean,
        resultsLimit: Int,
        saveResultsPath: String,
        saveTemplatesPath: String,
        exportFileFormat: String
    ) {
        viewModelScope.launch {
            try {
                val settings = AppSettings(
                    isDarkTheme = settingsManager.getSettings().isDarkTheme,
                    maxCombinations = maxCombinations,
                    showPartialResults = showPartialResults,
                    resultsLimit = resultsLimit,
                    saveResultsPath = saveResultsPath,
                    saveTemplatesPath = saveTemplatesPath,
                    exportFileFormat = exportFileFormat
                )

                settingsManager.updateSettings(settings).onSuccess {
                    uiState = uiState.copy(
                        maxCombinations = maxCombinations,
                        showPartialResults = showPartialResults,
                        resultsLimit = resultsLimit,
                        saveResultsPath = saveResultsPath,
                        saveTemplatesPath = saveTemplatesPath,
                        exportFileFormat = exportFileFormat,
                        successMessage = "Settings updated successfully"
                    )
                }.onFailure { e ->
                    uiState = uiState.copy(
                        errorMessage = "Error updating settings: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                logger.error("Error updating settings", e)
                uiState = uiState.copy(
                    errorMessage = "Error updating settings: ${e.message}"
                )
            }
        }
    }

    fun updateProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String
    ) {
        viewModelScope.launch {
            try {
                val existingConfig = llmConfig.providers[name]
                if (existingConfig != null) {
                    val updatedConfig = existingConfig.copy(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        defaultModel = defaultModel
                    )

                    settingsManager.updateProviderConfig(name, updatedConfig).onSuccess { success ->
                        if (success) {
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
                        } else {
                            uiState = uiState.copy(
                                errorMessage = "Failed to update provider configuration"
                            )
                        }
                    }.onFailure { e ->
                        uiState = uiState.copy(
                            errorMessage = "Error updating provider: ${e.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error updating provider", e)
                uiState = uiState.copy(
                    errorMessage = "Error updating provider: ${e.message}"
                )
            }
        }
    }

    fun setDefaultProvider(providerName: String) {
        viewModelScope.launch {
            try {
                val updatedConfig = llmConfig.copy(defaultProvider = providerName)

                settingsManager.saveConfig(updatedConfig).onSuccess { success ->
                    if (success) {
                        uiState = uiState.copy(
                            defaultProvider = providerName,
                            successMessage = "Default provider set to $providerName"
                        )
                    } else {
                        uiState = uiState.copy(
                            errorMessage = "Failed to save configuration"
                        )
                    }
                }.onFailure { e ->
                    uiState = uiState.copy(
                        errorMessage = "Error setting default provider: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                logger.error("Error setting default provider", e)
                uiState = uiState.copy(
                    errorMessage = "Error setting default provider: ${e.message}"
                )
            }
        }
    }

    fun clearMessage() {
        uiState = uiState.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    override fun close() {
        viewModelScope.cancel()
    }
}

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

data class ProviderSettingsUiState(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val isEditing: Boolean
)