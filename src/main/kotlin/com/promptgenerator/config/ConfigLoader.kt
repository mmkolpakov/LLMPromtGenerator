package com.promptgenerator.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ConfigLoader {
    private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
    private val yaml = Yaml.default
    private val configMutex = Mutex()

    private const val DEFAULT_CONFIG_PATH = "config/llm-config.yaml"
    private const val CONFIG_ENV_VAR = "LLM_CONFIG_PATH"

    private val cachedConfig = atomic<LLMConfig?>(null)

    fun loadLLMConfig(): LLMConfig {
        cachedConfig.value?.let { return it }

        return loadConfigFromSourcesSync().also {
            cachedConfig.value = it
        }
    }

    suspend fun reloadConfig() = configMutex.withLock {
        cachedConfig.value = null
    }

    private fun loadConfigFromSourcesSync(): LLMConfig {
        val configPath = System.getenv(CONFIG_ENV_VAR) ?: DEFAULT_CONFIG_PATH

        return try {
            val configFile = File(configPath)

            if (configFile.exists()) {
                logger.info("Loading configuration from: $configPath")

                try {
                    val content = configFile.readText()
                    val loadedConfig = yaml.decodeFromString(LLMConfig.serializer(), content)
                    applyEnvironmentKeysToConfig(loadedConfig)
                } catch (e: YamlException) {
                    logger.error("Error parsing YAML configuration: ${e.message}", e)
                    createDefaultConfigWithEnvVars()
                }
            } else {
                logger.warn("Configuration file not found: $configPath, using default configuration")
                createDefaultConfigWithEnvVars()
            }
        } catch (e: Exception) {
            logger.error("Error loading configuration: ${e.message}", e)
            createDefaultConfigWithEnvVars()
        }
    }

    private fun createDefaultConfigWithEnvVars(): LLMConfig {
        val defaultConfig = LLMConfig()
        return applyEnvironmentKeysToConfig(defaultConfig)
    }

    private fun applyEnvironmentKeysToConfig(config: LLMConfig): LLMConfig {
        val newProviders = config.providers.entries.associate { (providerName, providerConfig) ->
            val envKey = "LLM_${providerName.uppercase()}_API_KEY"
            val apiKeyFromEnv = System.getenv(envKey)

            if (apiKeyFromEnv != null) {
                logger.info("Applied API key from environment variable for provider: $providerName")
                providerName to providerConfig.copy(apiKey = apiKeyFromEnv)
            } else {
                providerName to providerConfig
            }
        }.toMutableMap()

        System.getenv("LLM_API_KEY")?.let { apiKey ->
            val defaultProvider = config.defaultProvider
            newProviders[defaultProvider]?.let { providerConfig ->
                newProviders[defaultProvider] = providerConfig.copy(apiKey = apiKey)
                logger.info("Applied default API key from environment variable")
            }
        }

        return config.copy(providers = newProviders)
    }

    fun ensureConfigDirectoryExists() {
        val configPath = System.getenv(CONFIG_ENV_VAR) ?: DEFAULT_CONFIG_PATH
        val directory = Paths.get(configPath).parent

        if (directory != null && !Files.exists(directory)) {
            try {
                Files.createDirectories(directory)
                logger.info("Created config directory: $directory")
            } catch (e: Exception) {
                logger.error("Failed to create config directory: $directory", e)
            }
        }
    }
}