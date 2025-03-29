package com.promptgenerator.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promptgenerator.config.ConfigLoader
import com.promptgenerator.config.SettingsManager
import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.model.NetworkError
import com.promptgenerator.domain.model.NetworkErrorType
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.ValidationResult
import com.promptgenerator.domain.service.GenerationState
import com.promptgenerator.domain.service.GenerationStatus
import com.promptgenerator.domain.service.PromptGeneratorService
import com.promptgenerator.domain.usecase.ManageTemplatesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GeneratorViewModel(
    private val promptGeneratorService: PromptGeneratorService,
    private val manageTemplatesUseCase: ManageTemplatesUseCase,
    private val settingsManager: SettingsManager
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generateJob: Job? = null
    private var stateUpdateJob: Job? = null
    private val failedRequests = ConcurrentHashMap<String, Request>()
    private val isUpdating = atomic(false)
    private val stateMutex = Mutex()

    var uiState by mutableStateOf(GeneratorUiState())
        private set

    init {
        loadSettings()
        startStateUpdateJob()
    }

    private fun loadSettings() {
        try {
            val settings = settingsManager.getSettings()
            val config = ConfigLoader.loadLLMConfig()

            uiState = uiState.copy(
                maxCombinations = settings.maxCombinations,
                showPartialResults = settings.showPartialResults,
                exportPath = settings.saveResultsPath,
                systemPrompt = config.defaultSystemPrompt
            )
        } catch (e: Exception) {
            logger.error("Error loading settings", e)
        }
    }

    private fun startStateUpdateJob() {
        stateUpdateJob?.cancel()
        stateUpdateJob = viewModelScope.launch {
            try {
                promptGeneratorService.currentGenerationState
                    .distinctUntilChanged { old, new -> old == new }
                    .collectLatest { state ->
                        if (state != null && !isUpdating.value) {
                            logger.info("Received generation state update: status=${state.status}, complete=${state.isComplete}, " +
                                    "progress=${state.completedCount}/${state.totalCount}")
                            updateStateFromGeneration(state)
                        }
                    }
            } catch (e: Exception) {
                logger.error("Error in state update job", e)
                stateUpdateJob = null
                delay(1000)
                startStateUpdateJob()
            }
        }
    }

    fun loadAllTemplates() {
        viewModelScope.launch {
            try {
                manageTemplatesUseCase.getAllTemplates().collect { templates ->
                    uiState = uiState.copy(templates = templates)
                    logger.info("Loaded ${templates.size} templates")
                }
            } catch (e: Exception) {
                logger.error("Error loading templates", e)
                uiState = uiState.copy(
                    errorMessage = "Error loading templates: ${e.message}"
                )
            }
        }
    }

    fun updateTemplateContent(content: String) {
        uiState = uiState.copy(
            templateContent = content,
            templateValidation = validateTemplate(content)
        )
    }

    fun updateSystemPrompt(systemPrompt: String) {
        uiState = uiState.copy(
            systemPrompt = systemPrompt
        )
    }

    fun updatePlaceholderData(key: String, value: String) {
        val updatedData = uiState.placeholderData.toMutableMap().apply {
            this[key] = value
        }
        uiState = uiState.copy(placeholderData = updatedData)
    }

    fun addPlaceholder(customName: String = "") {
        if (uiState.placeholderData.isNotEmpty() &&
            customName.contains("var")) {
            return
        }

        val validName = customName.replace(Regex("[^a-zA-Z0-9_]"), "")
        var placeholderName = if (validName.isNotBlank()) {
            validName
        } else {
            "var${uiState.placeholderData.size + 1}"
        }

        var counter = 1
        while (uiState.placeholderData.containsKey(placeholderName)) {
            placeholderName = if (validName.isNotBlank()) {
                "${validName}${counter}"
            } else {
                "var${uiState.placeholderData.size + counter}"
            }
            counter++
        }

        val updatedData = uiState.placeholderData.toMutableMap().apply {
            this[placeholderName] = ""
        }
        uiState = uiState.copy(placeholderData = updatedData)
    }

    fun addDetectedPlaceholder(name: String) {
        if (!uiState.placeholderData.containsKey(name)) {
            val updatedData = uiState.placeholderData.toMutableMap().apply {
                this[name] = ""
            }
            uiState = uiState.copy(placeholderData = updatedData)
        }
    }

    fun removePlaceholder(key: String) {
        val updatedData = uiState.placeholderData.toMutableMap().apply {
            remove(key)
        }

        val finalData = if (updatedData.isEmpty()) {
            mapOf("var1" to "")
        } else {
            updatedData
        }

        uiState = uiState.copy(placeholderData = finalData)
    }

    fun renamePlaceholder(oldName: String, newName: String) {
        if (oldName == newName) return

        if (uiState.placeholderData.containsKey(newName)) {
            uiState = uiState.copy(
                errorMessage = "A variable with name '$newName' already exists"
            )
            return
        }

        val value = uiState.placeholderData[oldName] ?: ""
        val updatedData = uiState.placeholderData.toMutableMap().apply {
            remove(oldName)
            put(newName, value)
        }

        val updatedTemplate = uiState.templateContent.replace("{{$oldName}}", "{{$newName}}")

        uiState = uiState.copy(
            placeholderData = updatedData,
            templateContent = updatedTemplate,
            templateValidation = validateTemplate(updatedTemplate)
        )
    }

    private fun validateTemplate(content: String): ValidationResult {
        return promptGeneratorService.validateTemplate(content)
    }

    fun generatePrompts() {
        if (uiState.isGenerating) {
            logger.info("Generation already in progress")
            return
        }

        isUpdating.value = true

        uiState = uiState.copy(
            errorMessage = null,
            networkError = null,
            results = emptyMap(),
            partialResponses = emptyMap()
        )

        val validation = validateTemplate(uiState.templateContent)
        if (!validation.isValid) {
            uiState = uiState.copy(
                errorMessage = "Template error: ${validation.errors.firstOrNull()}"
            )
            isUpdating.value = false
            return
        }

        val placeholderData = prepareDataForGeneration(uiState.placeholderData)

        if (placeholderData.isEmpty() && validation.placeholders.isNotEmpty()) {
            uiState = uiState.copy(
                errorMessage = "Template contains placeholders but no values provided"
            )
            isUpdating.value = false
            return
        }

        val template = Template(
            id = uiState.templateId.ifBlank { UUID.randomUUID().toString() },
            name = uiState.templateName.ifBlank { "Untitled Template" },
            content = uiState.templateContent
        )

        viewModelScope.launch {
            failedRequests.clear()

            uiState = uiState.copy(
                isGenerating = true,
                generationId = "",
                errorMessage = null,
                generationStatus = GenerationStatus.PREPARING,
                generationProgress = 0f,
                completedCount = 0,
                totalCount = 0
            )

            isUpdating.value = false

            try {
                generateJob?.cancel()
                generateJob = viewModelScope.launch {
                    try {
                        logger.info("Starting generation with system prompt: '${uiState.systemPrompt}'")
                        promptGeneratorService.generate(
                            template = template,
                            data = placeholderData,
                            maxCombinations = uiState.maxCombinations,
                            systemPrompt = uiState.systemPrompt
                        )
                            .catch { e ->
                                logger.error("Error in generation flow", e)
                                if (e !is CancellationException) {
                                    updateUiAfterError(e as Exception)
                                }
                            }
                            .collect { state ->
                                logger.info("Generation state update in flow: status=${state.status}, complete=${state.isComplete}")
                                analyzeErrorsInResponses(state)
                            }
                    } catch (e: CancellationException) {
                        logger.info("Generation was cancelled")
                        updateUiAfterCancellation()
                    } catch (e: Exception) {
                        logger.error("Error generating prompts", e)
                        updateUiAfterError(e)
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to launch generation job", e)
                uiState = uiState.copy(
                    isGenerating = false,
                    errorMessage = "Failed to start generation: ${e.message}"
                )
            }
        }
    }

    private suspend fun analyzeErrorsInResponses(state: GenerationState) {
        val errorResponses = state.responses.filter { it.value.error != null }

        if (errorResponses.isEmpty()) return

        val errorPatterns = mapOf(
            "timeout" to NetworkErrorType.TIMEOUT,
            "timed out" to NetworkErrorType.TIMEOUT,
            "rate limit" to NetworkErrorType.RATE_LIMIT,
            "too many requests" to NetworkErrorType.RATE_LIMIT,
            "unauthoriz" to NetworkErrorType.AUTHORIZATION,
            "forbidden" to NetworkErrorType.AUTHORIZATION,
            "api key" to NetworkErrorType.AUTHORIZATION,
            "no api key" to NetworkErrorType.AUTHORIZATION,
            "invalid api key" to NetworkErrorType.AUTHORIZATION,
            "connection" to NetworkErrorType.CONNECTION,
            "could not connect" to NetworkErrorType.CONNECTION,
            "host" to NetworkErrorType.CONNECTION
        )

        val networkErrors = errorResponses.mapNotNull { (id, response) ->
            val errorType = errorPatterns.entries.firstOrNull { (pattern, _) ->
                response.error?.lowercase()?.contains(pattern) == true
            }?.value ?: NetworkErrorType.UNKNOWN

            NetworkError(
                id = id,
                message = response.error ?: "Unknown error",
                type = errorType,
                timestamp = System.currentTimeMillis()
            )
        }

        if (networkErrors.isNotEmpty()) {
            val primaryError = networkErrors.first()
            uiState = uiState.copy(
                networkError = primaryError
            )

            // Save all failed requests for retry
            errorResponses.forEach { (id, response) ->
                state.responses.keys.find { it == id }?.let { responseId ->
                    state.template.let { template ->
                        val requestContent = if (response.content.isBlank()) response.error ?: "" else response.content
                        failedRequests[responseId] = Request(
                            id = responseId,
                            content = requestContent,
                            systemInstruction = uiState.systemPrompt
                        )
                    }
                }
            }
        }
    }

    private fun prepareDataForGeneration(placeholderData: Map<String, String>): Map<String, Any> {
        return placeholderData.filter { (_, value) -> value.isNotBlank() }
            .mapValues { (_, value) ->
                when {
                    value.trim().startsWith("[") && value.trim().endsWith("]") -> {
                        val listContent = value.trim().removeSurrounding("[", "]")
                        parseListValues(listContent)
                    }
                    value.contains(",") -> {
                        parseListValues(value)
                    }
                    else -> {
                        value
                    }
                }
            }
    }

    private fun parseListValues(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var escaped = false

        for (char in input) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }

        return result.map { value ->
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value.substring(1, value.length - 1)
            } else {
                value
            }
        }
    }

    private suspend fun updateStateFromGeneration(state: GenerationState) {
        try {
            isUpdating.value = true

            stateMutex.withLock {
                if (uiState.generationId.isEmpty() || state.id == uiState.generationId) {
                    val finalResponses = if (state.isComplete && state.status == GenerationStatus.COMPLETED) {
                        state.responses
                    } else if (uiState.results.isNotEmpty()) {
                        uiState.results
                    } else {
                        emptyMap()
                    }

                    val partialResponses = if (!state.isComplete || state.status == GenerationStatus.CANCELLED) {
                        state.responses
                    } else {
                        uiState.partialResponses
                    }

                    uiState = uiState.copy(
                        generationId = state.id,
                        isGenerating = !state.isComplete,
                        generationStatus = state.status,
                        generationProgress = state.progress,
                        partialResponses = partialResponses,
                        completedCount = state.completedCount,
                        totalCount = state.totalCount,
                        errorMessage = state.error
                    )

                    if (state.isComplete) {
                        uiState = uiState.copy(
                            isGenerating = false,
                            results = if (state.status == GenerationStatus.COMPLETED) state.responses else finalResponses
                        )

                        logger.info("Generation complete with status: ${state.status}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating state from generation", e)
        } finally {
            isUpdating.value = false
        }
    }

    private fun updateUiAfterCancellation() {
        uiState = uiState.copy(
            isGenerating = false,
            generationStatus = GenerationStatus.CANCELLED,
            errorMessage = "Generation cancelled"
        )
    }

    private fun updateUiAfterError(error: Exception) {
        uiState = uiState.copy(
            isGenerating = false,
            generationStatus = GenerationStatus.ERROR,
            errorMessage = "Error: ${error.message}"
        )
    }

    fun saveCurrentTemplate(name: String) {
        viewModelScope.launch {
            try {
                val template = Template(
                    id = uiState.templateId.ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    content = uiState.templateContent,
                    description = ""
                )

                manageTemplatesUseCase.updateTemplate(template).onSuccess { savedTemplate ->
                    uiState = uiState.copy(
                        templateId = savedTemplate.id,
                        templateName = savedTemplate.name,
                        successMessage = "Template saved successfully"
                    )
                    loadAllTemplates()

                }.onFailure { error ->
                    uiState = uiState.copy(
                        errorMessage = "Failed to save template: ${error.message}"
                    )
                }
            } catch (e: Exception) {
                logger.error("Error saving template", e)
                uiState = uiState.copy(
                    errorMessage = "Error saving template: ${e.message}"
                )
            }
        }
    }

    fun loadTemplateDirectly(template: Template) {
        val newPlaceholders = promptGeneratorService.extractPlaceholders(template.content)
        val placeholderData = mutableMapOf<String, String>()

        newPlaceholders.forEach { placeholder ->
            placeholderData[placeholder] = uiState.placeholderData[placeholder] ?: ""
        }

        if (placeholderData.isEmpty() && newPlaceholders.isEmpty()) {
            placeholderData["var1"] = ""
        }

        uiState = uiState.copy(
            templateId = template.id,
            templateName = template.name,
            templateContent = template.content,
            placeholderData = placeholderData,
            templateValidation = validateTemplate(template.content),
            successMessage = "Template loaded successfully"
        )
    }

    fun loadTemplate(templateId: String, updatePlaceholders: Boolean = false) {
        viewModelScope.launch {
            try {
                manageTemplatesUseCase.getTemplate(templateId).onSuccess { template ->
                    val newPlaceholders = promptGeneratorService.extractPlaceholders(template.content)
                    val placeholderData = if (updatePlaceholders) {
                        if (newPlaceholders.isEmpty()) {
                            mapOf("var1" to "")
                        } else {
                            newPlaceholders.associateWith { "" }
                        }
                    } else {
                        val currentPlaceholders = uiState.placeholderData.toMutableMap()

                        if (newPlaceholders.isNotEmpty()) {
                            newPlaceholders.forEach { placeholder ->
                                if (!currentPlaceholders.containsKey(placeholder)) {
                                    currentPlaceholders[placeholder] = ""
                                }
                            }
                        } else if (currentPlaceholders.isEmpty()) {
                            currentPlaceholders["var1"] = ""
                        }

                        currentPlaceholders
                    }

                    uiState = uiState.copy(
                        templateId = template.id,
                        templateName = template.name,
                        templateContent = template.content,
                        placeholderData = placeholderData,
                        templateValidation = validateTemplate(template.content),
                        successMessage = "Template loaded successfully"
                    )
                }.onFailure { error ->
                    uiState = uiState.copy(
                        errorMessage = "Failed to load template: ${error.message}"
                    )
                }
            } catch (e: Exception) {
                logger.error("Error loading template", e)
                uiState = uiState.copy(
                    errorMessage = "Error loading template: ${e.message}"
                )
            }
        }
    }

    fun createNewTemplate() {
        uiState = uiState.copy(
            templateId = "",
            templateName = "Untitled Template",
            templateContent = "",
            placeholderData = mapOf("var1" to ""),
            templateValidation = ValidationResult(true),
            results = emptyMap(),
            partialResponses = emptyMap()
        )
    }

    fun cancelGeneration() {
        viewModelScope.launch {
            try {
                logger.info("Cancelling generation from ViewModel")
                uiState = uiState.copy(
                    generationStatus = GenerationStatus.CANCELLED,
                    errorMessage = "Cancelling generation..."
                )
                withContext(Dispatchers.IO) {
                    promptGeneratorService.cancelGeneration()
                }
                generateJob?.cancel()
                uiState = uiState.copy(
                    isGenerating = false,
                    generationStatus = GenerationStatus.CANCELLED,
                    errorMessage = "Generation cancelled"
                )

                logger.info("Generation cancelled successfully")
            } catch (e: Exception) {
                logger.error("Error cancelling generation", e)
                uiState = uiState.copy(
                    isGenerating = false,
                    generationStatus = GenerationStatus.CANCELLED,
                    errorMessage = "Error cancelling generation: ${e.message}"
                )
            }
        }
    }

    fun clearResults() {
        uiState = uiState.copy(
            results = emptyMap(),
            partialResponses = emptyMap(),
            errorMessage = null,
            successMessage = null,
            networkError = null
        )
        failedRequests.clear()
    }

    fun clearMessage() {
        uiState = uiState.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    fun clearNetworkError() {
        uiState = uiState.copy(
            networkError = null
        )
    }

    fun exportResults() {
        viewModelScope.launch {
            try {
                val settings = settingsManager.getSettings()
                val exportPath = settings.saveResultsPath
                val exportDir = File(exportPath)
                if (!exportDir.exists()) {
                    exportDir.mkdirs()
                }

                val serializablePlaceholders = uiState.placeholderData.mapValues { (_, value) ->
                    if (value.contains(",")) {
                        value.split(",").map { it.trim() }
                    } else {
                        value
                    }
                }

                val result = GenerationResult(
                    id = uiState.generationId.ifBlank { UUID.randomUUID().toString() },
                    timestamp = System.currentTimeMillis(),
                    templateId = uiState.templateId,
                    templateName = uiState.templateName.ifBlank { "Untitled Template" },
                    placeholders = serializablePlaceholders,
                    responses = if (uiState.results.isNotEmpty()) {
                        uiState.results
                    } else {
                        uiState.partialResponses
                    },
                    isComplete = uiState.results.isNotEmpty()
                )

                logger.info("Exporting results to: $exportPath with format: ${settings.exportFileFormat}")

                promptGeneratorService.exportResults(result, exportPath)
                    .onSuccess { files ->
                        logger.info("Successfully exported ${files.size} files to $exportPath")
                        uiState = uiState.copy(
                            successMessage = "Exported ${files.size} results to $exportPath",
                            exportPath = exportPath
                        )
                    }
                    .onFailure { error ->
                        logger.error("Failed to export results: ${error.message}", error)
                        uiState = uiState.copy(
                            errorMessage = "Failed to export results: ${error.message}"
                        )
                    }
            } catch (e: Exception) {
                logger.error("Error exporting results", e)
                uiState = uiState.copy(
                    errorMessage = "Error exporting results: ${e.message}"
                )
            }
        }
    }

    fun toggleShowPartialResults() {
        val newValue = !uiState.showPartialResults

        uiState = uiState.copy(
            showPartialResults = newValue
        )

        viewModelScope.launch {
            try {
                val settings = settingsManager.getSettings()
                settingsManager.updateSettings(settings.copy(
                    showPartialResults = newValue
                ))
            } catch (e: Exception) {
                logger.error("Error updating showPartialResults setting", e)
            }
        }
    }

    fun updateMaxCombinations(maxCombinations: Int) {
        uiState = uiState.copy(
            maxCombinations = maxCombinations
        )

        viewModelScope.launch {
            try {
                val settings = settingsManager.getSettings()
                settingsManager.updateSettings(settings.copy(
                    maxCombinations = maxCombinations
                ))
            } catch (e: Exception) {
                logger.error("Error updating maxCombinations setting", e)
            }
        }
    }

    fun retryFailedRequests() {
        viewModelScope.launch {
            try {
                val requestsToRetry = failedRequests.values.toList()
                failedRequests.clear()

                if (requestsToRetry.isEmpty()) {
                    logger.info("No failed requests to retry")
                    return@launch
                }

                uiState = uiState.copy(
                    networkError = null,
                    isGenerating = true,
                    generationStatus = GenerationStatus.SENDING_REQUESTS
                )

                val template = Template(
                    id = uiState.templateId,
                    name = uiState.templateName,
                    content = uiState.templateContent
                )

                promptGeneratorService.retryRequests(
                    template = template,
                    requests = requestsToRetry,
                    existingResponses = uiState.results + uiState.partialResponses
                )
            } catch (e: Exception) {
                logger.error("Error retrying failed requests", e)
                uiState = uiState.copy(
                    errorMessage = "Error retrying requests: ${e.message}",
                    isGenerating = false
                )
            }
        }
    }

    fun retryFailedRequest(requestId: String) {
        viewModelScope.launch {
            try {
                val request = failedRequests.remove(requestId) ?: run {
                    logger.warn("Request $requestId not found in failed requests")
                    return@launch
                }

                uiState = uiState.copy(
                    isGenerating = true,
                    generationStatus = GenerationStatus.SENDING_REQUESTS
                )

                val template = Template(
                    id = uiState.templateId,
                    name = uiState.templateName,
                    content = uiState.templateContent
                )

                promptGeneratorService.retryRequests(
                    template = template,
                    requests = listOf(request),
                    existingResponses = uiState.results + uiState.partialResponses
                )
            } catch (e: Exception) {
                logger.error("Error retrying failed request", e)
                uiState = uiState.copy(
                    errorMessage = "Error retrying request: ${e.message}",
                    isGenerating = false
                )
            }
        }
    }

    override fun close() {
        generateJob?.cancel()
        stateUpdateJob?.cancel()
        viewModelScope.cancel()
        logger.info("GeneratorViewModel resources released")
    }
}

data class GeneratorUiState(
    val templateId: String = "",
    val templateName: String = "Untitled Template",
    val templateContent: String = "",
    val placeholderData: Map<String, String> = mapOf("var1" to ""),
    val templateValidation: ValidationResult = ValidationResult(true),
    val systemPrompt: String = "You are a helpful AI assistant tasked with generating responses based on the provided template. Respond only with the output based on the template and variables provided. Do not add any explanations, introductions, or additional text outside the template structure.",
    val templates: List<Template> = emptyList(),

    val isGenerating: Boolean = false,
    val generationId: String = "",
    val generationStatus: GenerationStatus = GenerationStatus.PREPARING,
    val generationProgress: Float = 0f,
    val completedCount: Int = 0,
    val totalCount: Int = 0,

    val results: Map<String, Response> = emptyMap(),
    val partialResponses: Map<String, Response> = emptyMap(),
    val showPartialResults: Boolean = true,

    val exportPath: String = "generated_prompts",

    val errorMessage: String? = null,
    val successMessage: String? = null,
    val networkError: NetworkError? = null,

    val maxCombinations: Int = 1000
)