package com.promptgenerator.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SettingsManager {
    private val logger = LoggerFactory.getLogger(SettingsManager::class.java)
    private val json = Json { prettyPrint = true }
    private val settingsMutex = Mutex()
    private val configManager = ConfigManager.instance

    private val settingsDir = Paths.get(System.getProperty("user.home"), ".promptgenerator")
    private val settingsFile = settingsDir.resolve("settings.json").toFile()

    private val currentSettings = atomic<AppSettings?>(null)

    fun getSettings(): AppSettings {
        return currentSettings.value ?: loadSettingsSync().also {
            currentSettings.value = it
        }
    }

    suspend fun updateSettings(settings: AppSettings): Result<AppSettings> = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            return@withContext try {
                saveSettings(settings)
                currentSettings.value = settings
                Result.success(settings)
            } catch (e: Exception) {
                logger.error("Failed to update settings", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateProviderConfig(providerName: String, config: ProviderConfig): Result<Boolean> {
        return try {
            val result = configManager.updateProviderConfig(providerName, config)
            if (result) {
                Result.success(true)
            } else {
                Result.failure(IOException("Failed to update provider config"))
            }
        } catch (e: Exception) {
            logger.error("Failed to update provider config: $providerName", e)
            Result.failure(e)
        }
    }

    suspend fun saveConfig(config: LLMConfig): Result<Boolean> {
        return try {
            val result = configManager.saveConfig(config)
            if (result) {
                Result.success(true)
            } else {
                Result.failure(IOException("Failed to save config file"))
            }
        } catch (e: Exception) {
            logger.error("Failed to save config", e)
            Result.failure(e)
        }
    }

    private fun loadSettingsSync(): AppSettings {
        return try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                try {
                    Json.decodeFromString<AppSettings>(content)
                } catch (e: Exception) {
                    logger.error("Failed to parse settings file, creating default", e)
                    createDefaultSettings()
                }
            } else {
                createDefaultSettings()
            }
        } catch (e: Exception) {
            logger.error("Failed to load settings", e)
            AppSettings()
        }
    }

    private fun createDefaultSettings(): AppSettings {
        val settings = AppSettings()
        try {
            saveSettingsSync(settings)
        } catch (e: Exception) {
            logger.error("Failed to save default settings", e)
        }
        return settings
    }

    private fun saveSettingsSync(settings: AppSettings) {
        try {
            Files.createDirectories(settingsDir)

            val tempFile = File.createTempFile("settings", ".json", settingsDir.toFile())
            tempFile.writeText(json.encodeToString(settings))

            if (tempFile.length() > 0) {
                if (settingsFile.exists()) {
                    settingsFile.delete()
                }

                if (!tempFile.renameTo(settingsFile)) {
                    tempFile.copyTo(settingsFile, overwrite = true)
                    tempFile.delete()
                }
            } else {
                throw IOException("Failed to write settings: empty file")
            }
        } catch (e: Exception) {
            logger.error("Failed to save settings", e)
            throw e
        }
    }

    private suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        saveSettingsSync(settings)
    }
}

class ConfigManager private constructor() {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)
    private val configMutex = Mutex()
    private val cachedConfig = atomic<LLMConfig?>(null)

    companion object {
        val instance: ConfigManager by lazy { ConfigManager() }
    }

    suspend fun updateProviderConfig(providerName: String, config: ProviderConfig): Boolean {
        return configMutex.withLock {
            try {
                val currentConfig = cachedConfig.value ?: ConfigLoader.loadLLMConfig()

                val updatedProviders = currentConfig.providers.toMutableMap()
                updatedProviders[providerName] = config

                val newConfig = currentConfig.copy(providers = updatedProviders)

                val result = YamlConfigWriter.writeConfig(newConfig, "config/llm-config.yaml")

                if (result) {
                    cachedConfig.value = newConfig
                    ConfigLoader.reloadConfig()
                    logger.info("Provider config updated successfully for: $providerName")
                    true
                } else {
                    logger.error("Failed to write updated config for provider: $providerName")
                    false
                }
            } catch (e: Exception) {
                logger.error("Failed to update provider config for $providerName", e)
                false
            }
        }
    }

    suspend fun saveConfig(config: LLMConfig): Boolean {
        return configMutex.withLock {
            val result = YamlConfigWriter.writeConfig(config, "config/llm-config.yaml")
            if (result) {
                cachedConfig.value = config
                ConfigLoader.reloadConfig()
                true
            } else {
                false
            }
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