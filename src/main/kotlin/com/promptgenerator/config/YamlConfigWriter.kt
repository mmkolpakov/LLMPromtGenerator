package com.promptgenerator.config

import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Saves LLM configuration to YAML file using kaml library
 */
object YamlConfigWriter {
    private val logger = LoggerFactory.getLogger(YamlConfigWriter::class.java)
    private val yaml = Yaml.default

    /**
     * Writes LLM configuration to YAML file
     */
    fun writeConfig(config: LLMConfig, filePath: String = "config/llm-config.yaml"): Boolean {
        return try {
            // Create parent directories if they don't exist
            val file = File(filePath)
            file.parentFile?.mkdirs()

            // Serialize config to YAML
            val yamlString = yaml.encodeToString(LLMConfig.serializer(), config)
            file.writeText(yamlString)

            logger.info("LLM configuration written to $filePath")
            true
        } catch (e: Exception) {
            logger.error("Failed to write LLM configuration to $filePath", e)
            false
        }
    }
}