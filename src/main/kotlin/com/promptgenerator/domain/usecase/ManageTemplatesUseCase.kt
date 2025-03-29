package com.promptgenerator.domain.usecase

import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.TemplateRepository
import com.promptgenerator.domain.repository.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import org.slf4j.LoggerFactory
import java.util.UUID

class ManageTemplatesUseCase(
    private val templateRepository: TemplateRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun createTemplate(
        name: String,
        content: String,
        description: String = ""
    ): Result<Template> {
        try {
            val validation = validateTemplate(content)
            if (!validation.isValid) {
                return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
            }

            val template = Template(
                id = UUID.randomUUID().toString(),
                name = name,
                content = content,
                description = description
            )

            return templateRepository.saveTemplate(template)
        } catch (e: Exception) {
            logger.error("Error creating template", e)
            return Result.failure(e)
        }
    }

    suspend fun updateTemplate(template: Template): Result<Template> {
        try {
            val validation = validateTemplate(template.content)
            if (!validation.isValid) {
                return Result.failure(IllegalArgumentException(validation.errors.joinToString("; ")))
            }

            return templateRepository.saveTemplate(template)
        } catch (e: Exception) {
            logger.error("Error updating template", e)
            return Result.failure(e)
        }
    }

    suspend fun getTemplate(id: String): Result<Template> {
        return try {
            templateRepository.getTemplate(id)
        } catch (e: Exception) {
            logger.error("Error getting template: $id", e)
            Result.failure(e)
        }
    }

    fun getAllTemplates(): Flow<List<Template>> {
        return templateRepository.getAllTemplates()
            .catch { e ->
                logger.error("Error getting templates", e)
                emit(emptyList())
            }
    }

    suspend fun deleteTemplate(id: String): Result<Boolean> {
        return try {
            templateRepository.deleteTemplate(id)
        } catch (e: Exception) {
            logger.error("Error deleting template: $id", e)
            Result.failure(e)
        }
    }

    fun validateTemplate(content: String): ValidationResult {
        return templateRepository.validateTemplate(content)
    }

    fun extractPlaceholders(content: String): Set<String> {
        return templateRepository.extractPlaceholders(content)
    }
}