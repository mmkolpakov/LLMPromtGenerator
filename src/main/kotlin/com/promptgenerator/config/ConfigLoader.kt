package com.promptgenerator.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object ConfigLoader {
    private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)
    private val yaml = Yaml.default
    private val configLock = ReentrantReadWriteLock()

    private const val DEFAULT_CONFIG_PATH = "config/llm-config.yaml"
    private const val CONFIG_ENV_VAR = "LLM_CONFIG_PATH"

    // Cached configuration to avoid repeated file reads
    private var cachedConfig: LLMConfig? = null

    fun loadLLMConfig(): LLMConfig = configLock.read {
        // Return cached config if available
        cachedConfig?.let { return it }

        // Otherwise, load from file/env
        return loadConfigFromSources().also {
            cachedConfig = it
        }
    }

    fun reloadConfig() {
        configLock.write {
            cachedConfig = null
        }
    }

    private fun loadConfigFromSources(): LLMConfig {
        val configPath = System.getenv(CONFIG_ENV_VAR) ?: DEFAULT_CONFIG_PATH

        return try {
            val configFile = File(configPath)

            val config = if (configFile.exists()) {
                logger.info("Loading configuration from: $configPath")

                try {
                    val content = configFile.readText()
                    yaml.decodeFromString(LLMConfig.serializer(), content)
                } catch (e: YamlException) {
                    logger.error("Error parsing YAML configuration: ${e.message}", e)
                    throw e
                }
            } else {
                logger.warn("Configuration file not found: $configPath, using default configuration")
                LLMConfig()
            }

            // Apply environment keys (making a copy to avoid mutating the original)
            applyEnvironmentKeysToConfig(config)
        } catch (e: Exception) {
            logger.error("Error loading configuration: ${e.message}", e)

            // Last resort - use built-in defaults with environment keys
            val defaultConfig = LLMConfig()
            applyEnvironmentKeysToConfig(defaultConfig)
        }
    }

    private fun applyEnvironmentKeysToConfig(config: LLMConfig): LLMConfig {
        // Create a copy of the config to avoid mutation
        val newConfig = config.copy(
            providers = config.providers.toMutableMap()
        )

        // Check for provider-specific API keys
        newConfig.providers.keys.forEach { providerName ->
            val envKey = "LLM_${providerName.uppercase()}_API_KEY"
            System.getenv(envKey)?.let { apiKey ->
                val providerConfig = newConfig.providers[providerName] ?: return@let
                val updatedConfig = providerConfig.copy(apiKey = apiKey)
                newConfig.providers[providerName] = updatedConfig
                logger.info("Applied API key from environment variable for provider: $providerName")
            }
        }

        // Check for default API key
        System.getenv("LLM_API_KEY")?.let { apiKey ->
            val defaultProvider = newConfig.defaultProvider
            val providerConfig = newConfig.providers[defaultProvider] ?: return@let
            val updatedConfig = providerConfig.copy(apiKey = apiKey)
            newConfig.providers[defaultProvider] = updatedConfig
            logger.info("Applied default API key from environment variable")
        }

        return newConfig
    }
}