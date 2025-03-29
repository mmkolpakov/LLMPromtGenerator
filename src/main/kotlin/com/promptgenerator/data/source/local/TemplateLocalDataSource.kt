package com.promptgenerator.data.source.local

import com.promptgenerator.domain.model.Template
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class TemplateLocalDataSource(
    private val templatesDir: String = "templates"
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { prettyPrint = true }

    private val templatesMutex = Mutex()
    private val templates = mutableMapOf<String, Template>()
    private val templatesFlow = MutableStateFlow<List<Template>>(emptyList())

    init {
        ensureDirectoryExists()
        loadTemplates()
    }

    private fun ensureDirectoryExists() {
        try {
            val dir = Path.of(templatesDir)
            if (!dir.exists()) {
                dir.createDirectories()
                logger.info("Created templates directory: $templatesDir")
            }
        } catch (e: Exception) {
            logger.error("Failed to create templates directory: $templatesDir", e)
        }
    }

    suspend fun saveTemplate(template: Template): Template = templatesMutex.withLock {
        val templateToSave = if (template.id.isBlank()) {
            template.copy(id = UUID.randomUUID().toString())
        } else {
            template
        }

        templates[templateToSave.id] = templateToSave

        updateTemplatesFlow()

        saveTemplateToFile(templateToSave)

        return templateToSave
    }

    suspend fun getTemplate(id: String): Template? = templatesMutex.withLock {
        templates[id]
    }

    fun getAllTemplates(): Flow<List<Template>> = templatesFlow.asStateFlow()

    suspend fun deleteTemplate(id: String): Boolean = templatesMutex.withLock {
        val template = templates.remove(id)

        if (template != null) {
            updateTemplatesFlow()

            val file = File(templatesDir, "$id.json")
            if (file.exists()) {
                try {
                    if (!file.delete()) {
                        logger.warn("Failed to delete template file: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    logger.error("Error deleting template file: ${file.name}", e)
                }
            }

            return true
        }

        return false
    }

    private fun loadTemplates() {
        val dir = File(templatesDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }

        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                file.inputStream().bufferedReader().use { reader ->
                    val content = reader.readText()
                    val templateDto = json.decodeFromString<TemplateDto>(content)

                    val template = Template(
                        id = templateDto.id,
                        name = templateDto.name,
                        content = templateDto.content,
                        description = templateDto.description
                    )

                    templates[template.id] = template
                }
            } catch (e: Exception) {
                logger.error("Error loading template from ${file.name}", e)
            }
        }

        updateTemplatesFlow()
    }

    private fun updateTemplatesFlow() {
        templatesFlow.value = templates.values.toList()
    }

    private fun saveTemplateToFile(template: Template) {
        try {
            val dir = File(templatesDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val templateDto = TemplateDto(
                id = template.id,
                name = template.name,
                content = template.content,
                description = template.description
            )

            val file = File(dir, "${template.id}.json")
            val tempFile = File(dir, "${template.id}.json.tmp")

            tempFile.outputStream().bufferedWriter().use { writer ->
                val content = json.encodeToString(templateDto)
                writer.write(content)
            }

            if (tempFile.length() > 0) {
                if (file.exists()) {
                    file.delete()
                }

                if (!tempFile.renameTo(file)) {
                    Files.move(tempFile.toPath(), file.toPath())
                }
            } else {
                tempFile.delete()
                throw IOException("Failed to write template to file (empty file)")
            }

        } catch (e: Exception) {
            logger.error("Error saving template to file", e)
            throw e
        }
    }

    @Serializable
    private data class TemplateDto(
        val id: String,
        val name: String,
        val content: String,
        val description: String
    )
}