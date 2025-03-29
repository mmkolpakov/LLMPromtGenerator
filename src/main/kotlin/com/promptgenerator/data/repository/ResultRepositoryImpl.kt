package com.promptgenerator.data.repository

import com.promptgenerator.config.SettingsManager
import com.promptgenerator.data.source.local.ResultLocalDataSource
import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.repository.ResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class ResultRepositoryImpl(
    private val localDataSource: ResultLocalDataSource,
    private val settingsManager: SettingsManager
) : ResultRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun processResults(
        generationId: String,
        templateId: String,
        templateName: String,
        placeholders: Map<String, Any>,
        responses: Map<String, Response>,
        isComplete: Boolean
    ): GenerationResult {
        logger.info("Processing ${responses.size} responses for generation $generationId")

        return GenerationResult(
            id = generationId,
            timestamp = System.currentTimeMillis(),
            templateId = templateId,
            templateName = templateName,
            placeholders = placeholders,
            responses = responses,
            isComplete = isComplete
        )
    }

    override suspend fun saveResult(result: GenerationResult): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                localDataSource.saveResult(result)
                Result.success(result.id)
            } catch (e: Exception) {
                logger.error("Error saving result", e)
                Result.failure(e)
            }
        }

    override suspend fun getResult(id: String): Result<GenerationResult> =
        withContext(Dispatchers.IO) {
            try {
                val result = localDataSource.getResult(id)
                if (result != null) {
                    Result.success(result)
                } else {
                    Result.failure(NoSuchElementException("Result not found: $id"))
                }
            } catch (e: Exception) {
                logger.error("Error getting result", e)
                Result.failure(e)
            }
        }

    override fun getAllResults(): Flow<List<GenerationResult>> =
        localDataSource.getAllResults()

    override suspend fun exportResults(
        result: GenerationResult,
        directory: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val settings = settingsManager.getSettings()
            val fileExtension = settings.exportFileFormat

            val outputDir = Path.of(directory)
            if (!outputDir.exists()) {
                try {
                    outputDir.createDirectories()
                } catch (e: IOException) {
                    logger.error("Failed to create directory: $directory", e)
                    throw IOException("Failed to create directory: $directory", e)
                }
            } else if (!outputDir.isDirectory()) {
                throw IOException("Path exists but is not a directory: $directory")
            }

            val dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val templateNameSafe = result.templateName
                .replace(Regex("[^a-zA-Z0-9-_]"), "_")
                .take(20)

            val savedFiles = mutableListOf<String>()

            result.responses.forEach { (id, response) ->
                if (response.error != null) return@forEach

                val fileName = "${templateNameSafe}_${dateStr}_${id.substring(0, 6)}.${fileExtension}"
                val file = outputDir.resolve(fileName).toFile()

                try {
                    file.outputStream().bufferedWriter().use { writer ->
                        writer.write(response.content)
                    }

                    if (!file.exists() || file.length() == 0L) {
                        throw IOException("Failed to write content to file: ${file.absolutePath}")
                    }

                    savedFiles.add(file.absolutePath)
                    logger.info("Saved result to ${file.absolutePath} as .${fileExtension} file")
                } catch (e: Exception) {
                    logger.error("Error writing to file ${file.absolutePath}", e)
                    throw e
                }
            }

            Result.success(savedFiles)
        } catch (e: Exception) {
            logger.error("Error exporting results", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteResult(id: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val result = localDataSource.deleteResult(id)
                Result.success(result)
            } catch (e: Exception) {
                logger.error("Error deleting result", e)
                Result.failure(e)
            }
        }
}