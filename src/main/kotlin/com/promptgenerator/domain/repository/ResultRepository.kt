package com.promptgenerator.domain.repository

import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.model.Response
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing generation results.
 */
interface ResultRepository {
    /**
     * Processes responses into a results.
     */
    fun processResults(
        generationId: String,
        templateId: String,
        templateName: String,
        placeholders: Map<String, Any>,
        responses: Map<String, Response>,
        isComplete: Boolean = true
    ): GenerationResult

    /**
     * Saves a generation result.
     */
    suspend fun saveResult(result: GenerationResult): Result<String>

    /**
     * Gets a specific generation result.
     */
    suspend fun getResult(id: String): Result<GenerationResult>

    /**
     * Gets all generation results.
     */
    fun getAllResults(): Flow<List<GenerationResult>>

    /**
     * Exports generation results to files.
     */
    suspend fun exportResults(
        result: GenerationResult,
        directory: String = "generated_prompts"
    ): Result<List<String>>

    /**
     * Deletes a generation result.
     */
    suspend fun deleteResult(id: String): Result<Boolean>
}