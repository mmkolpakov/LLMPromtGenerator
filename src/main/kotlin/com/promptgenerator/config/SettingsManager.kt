package com.promptgenerator.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class SettingsManager {
    private val logger = LoggerFactory.getLogger(SettingsManager::class.java)
    private val json = Json { prettyPrint = true }
    private val settingsLock = ReentrantReadWriteLock()
    private val configManager = ConfigManager.instance

    private val settingsDir = Paths.get(System.getProperty("user.home"), ".promptgenerator").toFile()
    private val settingsFile = File(settingsDir, "settings.json")

    // Initialize settings
    private var currentSettings = loadSettings()

    fun getSettings(): AppSettings = settingsLock.read { currentSettings }

    fun updateSettings(settings: AppSettings) {
        settingsLock.write {
            currentSettings = settings
            saveSettings(settings)
        }
    }

    fun updateProviderConfig(providerName: String, config: ProviderConfig): Boolean {
        return configManager.updateProviderConfig(providerName, config)
    }

    fun saveConfig(config: LLMConfig): Boolean {
        return configManager.saveConfig(config)
    }

    private fun loadSettings(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                Json.decodeFromString<AppSettings>(content)
            } else {
                // Create default settings
                AppSettings().also { saveSettings(it) }
            }
        } catch (e: Exception) {
            logger.error("Failed to load settings", e)
            AppSettings()
        }
    }

    private fun saveSettings(settings: AppSettings) {
        try {
            if (!settingsDir.exists()) {
                val created = settingsDir.mkdirs()
                if (!created) {
                    logger.error("Failed to create directory: ${settingsDir.absolutePath}")
                    return
                }
            }
            val content = json.encodeToString(settings)
            settingsFile.writeText(content)
        } catch (e: Exception) {
            logger.error("Failed to save settings", e)
        }
    }
}

class ConfigManager private constructor() {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)
    private val configLock = ReentrantReadWriteLock()
    private var cachedConfig: LLMConfig? = null

    companion object {
        val instance: ConfigManager by lazy { ConfigManager() }
    }

    fun updateProviderConfig(providerName: String, config: ProviderConfig): Boolean {
        return configLock.write {
            try {
                // Get current config (from cache or load new)
                val currentConfig = cachedConfig ?: ConfigLoader.loadLLMConfig()

                // Update the provider config
                val updatedProviders = currentConfig.providers.toMutableMap()
                updatedProviders[providerName] = config

                // Create new config with updated providers
                val newConfig = currentConfig.copy(providers = updatedProviders)

                // Save the updated config
                val result = YamlConfigWriter.writeConfig(newConfig, "config/llm-config.yaml")

                // Update cache if successful
                if (result) {
                    cachedConfig = newConfig
                    // Also reload in ConfigLoader
                    ConfigLoader.reloadConfig()
                    logger.info("Provider config updated successfully for: $providerName")
                } else {
                    logger.error("Failed to write updated config for provider: $providerName")
                }

                result
            } catch (e: Exception) {
                logger.error("Failed to update provider config for $providerName", e)
                false
            }
        }
    }

    fun saveConfig(config: LLMConfig): Boolean {
        return configLock.write {
            val result = YamlConfigWriter.writeConfig(config, "config/llm-config.yaml")
            if (result) {
                cachedConfig = config
                ConfigLoader.reloadConfig()
            }
            result
        }
    }
}

@Serializable
data class AppSettings(
    val isDarkTheme: Boolean = false,
    val resultsLimit: Int = 100,
    val saveResultsPath: String = "generated_prompts",
    val saveTemplatesPath: String = "templates",
    val maxCombinations: Int = 1000,
    val showPartialResults: Boolean = true,
    val exportFileFormat: String = "md"
)