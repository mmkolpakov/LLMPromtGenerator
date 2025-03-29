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
import java.util.UUID

/**
 * Local data source for templates
 */
class TemplateLocalDataSource(
    private val templatesDir: String = "templates"
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json { prettyPrint = true }

    private val templates = mutableMapOf<String, Template>()
    private val templatesFlow = MutableStateFlow<List<Template>>(emptyList())

    init {
        loadTemplates()
    }

    /**
     * Saves template to local storage
     */
    suspend fun saveTemplate(template: Template): Template {
        val templateToSave = if (template.id.isBlank()) {
            template.copy(id = UUID.randomUUID().toString())
        } else {
            template
        }

        // Store in memory
        templates[templateToSave.id] = templateToSave

        // Update flow
        templatesFlow.value = templates.values.toList()

        // Save to file
        saveTemplateToFile(templateToSave)

        return templateToSave
    }

    /**
     * Gets template by ID
     */
    suspend fun getTemplate(id: String): Template? = templates[id]

    /**
     * Gets all templates
     */
    fun getAllTemplates(): Flow<List<Template>> = templatesFlow.asStateFlow()

    /**
     * Deletes template by ID
     */
    suspend fun deleteTemplate(id: String): Boolean {
        val template = templates.remove(id)

        if (template != null) {
            // Update flow
            templatesFlow.value = templates.values.toList()

            // Delete file
            val file = File(File(templatesDir), "$id.json")
            if (file.exists()) {
                file.delete()
            }

            return true
        }

        return false
    }

    /**
     * Loads templates from storage
     */
    private fun loadTemplates() {
        val dir = File(templatesDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }

        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val content = file.readText()
                val templateDto = json.decodeFromString<TemplateDto>(content)

                val template = Template(
                    id = templateDto.id,
                    name = templateDto.name,
                    content = templateDto.content,
                    description = templateDto.description
                )

                templates[template.id] = template
            } catch (e: Exception) {
                logger.error("Error loading template from ${file.name}", e)
            }
        }

        templatesFlow.value = templates.values.toList()
    }

    /**
     * Saves template to file
     */
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
            val content = json.encodeToString(templateDto)
            file.writeText(content)
        } catch (e: Exception) {
            logger.error("Error saving template to file", e)
        }
    }

    /**
     * DTO for template serialization
     */
    @Serializable
    private data class TemplateDto(
        val id: String,
        val name: String,
        val content: String,
        val description: String
    )
}