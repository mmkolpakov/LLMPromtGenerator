package com.promptgenerator.domain.usecase

import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.TemplateRepository
import com.promptgenerator.domain.repository.ValidationResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Use case for template management operations
 */
class ManageTemplatesUseCase(
    private val templateRepository: TemplateRepository
) {
    /**
     * Creates a new template
     */
    suspend fun createTemplate(
        name: String,
        content: String,
        description: String = ""
    ): Result<Template> {
        // Validate template
        val validation = validateTemplate(content)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
        }

        // Create template
        val template = Template(
            id = UUID.randomUUID().toString(),
            name = name,
            content = content,
            description = description
        )

        return templateRepository.saveTemplate(template)
    }

    /**
     * Updates an existing template
     */
    suspend fun updateTemplate(template: Template): Result<Template> {
        // Validate template
        val validation = validateTemplate(template.content)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
        }

        return templateRepository.saveTemplate(template)
    }

    /**
     * Gets a template by ID
     */
    suspend fun getTemplate(id: String): Result<Template> {
        return templateRepository.getTemplate(id)
    }

    /**
     * Gets all templates
     */
    fun getAllTemplates(): Flow<List<Template>> {
        return templateRepository.getAllTemplates()
    }

    /**
     * Deletes a template
     */
    suspend fun deleteTemplate(id: String): Result<Boolean> {
        return templateRepository.deleteTemplate(id)
    }

    /**
     * Validates a template
     */
    fun validateTemplate(content: String): ValidationResult {
        return templateRepository.validateTemplate(content)
    }

    /**
     * Extracts placeholders from a template
     */
    fun extractPlaceholders(content: String): Set<String> {
        return templateRepository.extractPlaceholders(content)
    }
}