package com.promptgenerator.data.source.local

import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.model.Response
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ResultLocalDataSource(
    private val resultsDir: String = "results",
    private val maxCachedResults: Int = 100
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val results = ConcurrentHashMap<String, GenerationResult>()
    private val resultsFlow = MutableStateFlow<List<GenerationResult>>(emptyList())

    init {
        ensureDirectoryExists()
        loadInitialResults()
    }

    private fun ensureDirectoryExists() {
        val dir = File(resultsDir)
        if (!dir.exists()) {
            val created = dir.mkdirs()
            if (!created) {
                logger.error("Failed to create results directory: ${dir.absolutePath}")
            }
        }
    }

    suspend fun saveResult(result: GenerationResult): String {
        // Store in memory
        results[result.id] = result

        // Update flow
        updateResultsFlow()

        // Save to file
        saveResultToFile(result)

        // Trim cache if needed
        trimCacheIfNeeded()

        return result.id
    }

    suspend fun getResult(id: String): GenerationResult? {
        var result = results[id]

        // If not in memory, try to load from file
        if (result == null) {
            val file = File(File(resultsDir), "$id.json")
            if (file.exists()) {
                try {
                    result = loadResultFromFile(file)
                    // Add to memory cache
                    results[id] = result
                    // Update flow (without sorting, just append)
                    updateResultsFlow()
                } catch (e: Exception) {
                    logger.error("Error loading result from file: ${file.name}", e)
                }
            }
        }

        return result
    }

    fun getAllResults(): Flow<List<GenerationResult>> = resultsFlow.asStateFlow()

    suspend fun deleteResult(id: String): Boolean {
        val result = results.remove(id)

        if (result != null) {
            // Update flow
            updateResultsFlow()

            // Delete file
            val file = File(File(resultsDir), "$id.json")
            if (file.exists()) {
                try {
                    val deleted = file.delete()
                    if (!deleted) {
                        logger.warn("Failed to delete file: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    logger.error("Error deleting file: ${file.name}", e)
                }
            }

            return true
        }

        return false
    }

    private fun updateResultsFlow() {
        // Sort by timestamp descending
        resultsFlow.value = results.values.sortedByDescending { it.timestamp }
    }

    private fun loadInitialResults() {
        val dir = File(resultsDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }

        // Load most recent files first, limited by maxCachedResults
        val resultFiles = dir.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(maxCachedResults)
            ?: emptyList()

        for (file in resultFiles) {
            try {
                val result = loadResultFromFile(file)
                results[result.id] = result
            } catch (e: Exception) {
                logger.error("Error loading result from ${file.name}", e)
            }
        }

        updateResultsFlow()
    }

    private fun loadResultFromFile(file: File): GenerationResult {
        val content = file.readText()
        val resultDto = json.decodeFromString<ResultDto>(content)

        // Convert string-based placeholders back to appropriate types
        val placeholders = resultDto.placeholders.mapValues { (_, valueStr) ->
            try {
                // Try to recover lists if they were serialized as string representations
                if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
                    valueStr.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
                } else {
                    valueStr
                }
            } catch (e: Exception) {
                // If anything fails, keep as string
                valueStr
            }
        }

        return GenerationResult(
            id = resultDto.id,
            timestamp = resultDto.timestamp,
            templateId = resultDto.templateId,
            templateName = resultDto.templateName,
            placeholders = placeholders,
            responses = resultDto.responses.mapValues { (id, resp) ->
                Response(id, resp.content, resp.error)
            },
            isComplete = resultDto.isComplete
        )
    }

    private fun saveResultToFile(result: GenerationResult) {
        try {
            val dir = File(resultsDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }

            // Convert placeholder values to strings for safe serialization
            val placeholdersAsStrings = result.placeholders.mapValues { (_, value) ->
                when (value) {
                    is List<*> -> value.joinToString(", ") { it.toString() }
                    else -> value.toString()
                }
            }

            val responseDtos = result.responses.mapValues { (_, response) ->
                ResponseDto(response.content, response.error)
            }

            val resultDto = ResultDto(
                id = result.id,
                timestamp = result.timestamp,
                templateId = result.templateId,
                templateName = result.templateName,
                placeholders = placeholdersAsStrings,
                responses = responseDtos,
                isComplete = result.isComplete
            )

            val file = File(dir, "${result.id}.json")
            val content = json.encodeToString(resultDto)
            file.writeText(content)
        } catch (e: Exception) {
            logger.error("Error saving result to file", e)
        }
    }

    private fun trimCacheIfNeeded() {
        if (results.size > maxCachedResults) {
            // Sort by timestamp and keep only the most recent
            val sortedResults = results.values.sortedByDescending { it.timestamp }
            val toRemove = sortedResults.drop(maxCachedResults)

            // Remove oldest from memory (files remain on disk)
            toRemove.forEach { results.remove(it.id) }

            // Update flow
            updateResultsFlow()
        }
    }

    @Serializable
    private data class ResultDto(
        val id: String,
        val timestamp: Long,
        val templateId: String,
        val templateName: String,
        val placeholders: Map<String, String>,
        val responses: Map<String, ResponseDto>,
        val isComplete: Boolean
    )

    @Serializable
    private data class ResponseDto(
        val content: String,
        val error: String? = null
    )
}