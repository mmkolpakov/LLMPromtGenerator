package com.promptgenerator.domain.usecase

import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.repository.ResultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory

class ManageResultsUseCase(
    private val resultRepository: ResultRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun getAllResults(): Flow<List<GenerationResult>> {
        return resultRepository.getAllResults()
            .catch { e ->
                logger.error("Error getting results", e)
                emit(emptyList())
            }
            .map { results ->
                results.sortedByDescending { it.timestamp }
            }
    }

    suspend fun getResult(id: String): Result<GenerationResult> {
        return try {
            resultRepository.getResult(id)
        } catch (e: Exception) {
            logger.error("Error getting result: $id", e)
            Result.failure(e)
        }
    }

    suspend fun exportResults(
        result: GenerationResult,
        directory: String = "generated_prompts"
    ): Result<List<String>> {
        return try {
            resultRepository.exportResults(result, directory)
        } catch (e: Exception) {
            logger.error("Error exporting results", e)
            Result.failure(e)
        }
    }

    suspend fun deleteResult(id: String): Result<Boolean> {
        return try {
            resultRepository.deleteResult(id)
        } catch (e: Exception) {
            logger.error("Error deleting result: $id", e)
            Result.failure(e)
        }
    }
}