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
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

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

    private val resultsMutex = Mutex()
    private val results = mutableMapOf<String, GenerationResult>()
    private val resultsFlow = MutableStateFlow<List<GenerationResult>>(emptyList())

    init {
        ensureDirectoryExists()
        loadInitialResults()
    }

    private fun ensureDirectoryExists() {
        try {
            val dir = Path.of(resultsDir)
            if (!dir.exists()) {
                dir.createDirectories()
                logger.info("Created results directory: $resultsDir")
            }
        } catch (e: Exception) {
            logger.error("Failed to create results directory: $resultsDir", e)
        }
    }

    suspend fun saveResult(result: GenerationResult): String = resultsMutex.withLock {
        results[result.id] = result

        updateResultsFlow()

        saveResultToFile(result)

        trimCacheIfNeeded()

        return result.id
    }

    suspend fun getResult(id: String): GenerationResult? = resultsMutex.withLock {
        var result = results[id]

        if (result == null) {
            val file = File(resultsDir, "$id.json")
            if (file.exists()) {
                try {
                    result = loadResultFromFile(file)
                    results[id] = result
                    updateResultsFlow()
                } catch (e: Exception) {
                    logger.error("Error loading result from file: ${file.name}", e)
                }
            }
        }

        return result
    }

    fun getAllResults(): Flow<List<GenerationResult>> = resultsFlow.asStateFlow()

    suspend fun deleteResult(id: String): Boolean = resultsMutex.withLock {
        val result = results.remove(id)

        if (result != null) {
            updateResultsFlow()

            val file = File(resultsDir, "$id.json")
            if (file.exists()) {
                try {
                    if (!file.delete()) {
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
        resultsFlow.value = results.values.sortedByDescending { it.timestamp }
    }

    private fun loadInitialResults() {
        val dir = File(resultsDir)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }

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
        try {
            file.inputStream().bufferedReader().use { reader ->
                val content = reader.readText()
                val resultDto = json.decodeFromString<ResultDto>(content)

                val placeholders = resultDto.placeholders.mapValues { (_, valueStr) ->
                    try {
                        if (valueStr.startsWith("[") && valueStr.endsWith("]")) {
                            valueStr.removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
                        } else {
                            valueStr
                        }
                    } catch (e: Exception) {
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
        } catch (e: Exception) {
            logger.error("Error parsing result file: ${file.name}", e)
            throw e
        }
    }

    private fun saveResultToFile(result: GenerationResult) {
        try {
            val dir = File(resultsDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }

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
            val tempFile = File(dir, "${result.id}.json.tmp")

            tempFile.outputStream().bufferedWriter().use { writer ->
                val content = json.encodeToString(resultDto)
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
                throw IOException("Failed to write result to file (empty file)")
            }

        } catch (e: Exception) {
            logger.error("Error saving result to file", e)
            throw e
        }
    }

    private fun trimCacheIfNeeded() {
        if (results.size > maxCachedResults) {
            val sortedResults = results.values.sortedByDescending { it.timestamp }
            val toRemove = sortedResults.drop(maxCachedResults)

            toRemove.forEach { results.remove(it.id) }

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