package com.promptgenerator.domain.usecase

import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.repository.ResultRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case for result management operations
 */
class ManageResultsUseCase(
    private val resultRepository: ResultRepository
) {
    /**
     * Gets all generation results
     */
    fun getAllResults(): Flow<List<GenerationResult>> {
        return resultRepository.getAllResults()
    }

    /**
     * Gets a specific result by ID
     */
    suspend fun getResult(id: String): Result<GenerationResult> {
        return resultRepository.getResult(id)
    }

    /**
     * Exports results to files
     */
    suspend fun exportResults(
        result: GenerationResult,
        directory: String = "generated_prompts"
    ): Result<List<String>> {
        return resultRepository.exportResults(result, directory)
    }

    /**
     * Deletes a result
     */
    suspend fun deleteResult(id: String): Result<Boolean> {
        return resultRepository.deleteResult(id)
    }
}