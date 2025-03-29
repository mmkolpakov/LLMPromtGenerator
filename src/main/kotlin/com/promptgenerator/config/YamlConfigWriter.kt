package com.promptgenerator.config

import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object YamlConfigWriter {
    private val logger = LoggerFactory.getLogger(YamlConfigWriter::class.java)
    private val yaml = Yaml.default

    fun writeConfig(config: LLMConfig, filePath: String = "config/llm-config.yaml"): Boolean {
        try {
            val path = Path.of(filePath)
            val directory = path.parent

            if (!directory.exists()) {
                directory.createDirectories()
            }

            val tempFile = Files.createTempFile(directory, "llm-config", ".tmp")

            tempFile.toFile().bufferedWriter().use { writer ->
                val yamlString = yaml.encodeToString(LLMConfig.serializer(), config)
                writer.write(yamlString)
            }

            if (tempFile.toFile().length() == 0L) {
                Files.delete(tempFile)
                throw IOException("Failed to write configuration (empty file)")
            }

            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            logger.info("LLM configuration written to $filePath")
            return true
        } catch (e: Exception) {
            logger.error("Failed to write LLM configuration to $filePath", e)
            return false
        }
    }
}