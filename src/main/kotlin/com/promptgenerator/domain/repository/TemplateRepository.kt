package com.promptgenerator.domain.repository

import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Template
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for template operations.
 */
interface TemplateRepository {
    /**
     * Processes a template by replacing placeholders.
     */
    fun processTemplate(template: Template, data: Map<String, Any>, maxCombinations: Int = 1000, systemInstruction: String? ): List<Request>

    /**
     * Saves a template for future use.
     */
    suspend fun saveTemplate(template: Template): Result<Template>

    /**
     * Gets a template by ID.
     */
    suspend fun getTemplate(id: String): Result<Template>

    /**
     * Gets all templates.
     */
    fun getAllTemplates(): Flow<List<Template>>

    /**
     * Deletes a template.
     */
    suspend fun deleteTemplate(id: String): Result<Boolean>

    /**
     * Validates a template for correct syntax.
     */
    fun validateTemplate(templateContent: String): ValidationResult

    /**
     * Extracts placeholders from template content.
     */
    fun extractPlaceholders(templateContent: String): Set<String>
}

/**
 * Result of template validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val placeholders: Set<String> = emptySet()
)