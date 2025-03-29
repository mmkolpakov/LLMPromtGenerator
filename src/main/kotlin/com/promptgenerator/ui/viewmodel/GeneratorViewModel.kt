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
    private val placeholderMutex = Mutex()

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

    private fun validatePlaceholderData(placeholders: Set<String>, data: Map<String, String>): Boolean {
        val emptyPlaceholders = placeholders.filter { placeholder ->
            data[placeholder]?.isBlank() != false
        }

        return emptyPlaceholders.isEmpty()
    }

    private fun startStateUpdateJob() {
        stateUpdateJob?.cancel()
        stateUpdateJob = viewModelScope.launch {
            try {
                promptGeneratorService.currentGenerationState
                    .distinctUntilChanged { old, new ->
                        old?.status == new?.status &&
                                old?.completedCount == new?.completedCount &&
                                old?.isComplete == new?.isComplete
                    }
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
                    updateUiState { currentState ->
                        currentState.copy(templates = templates)
                    }
                    logger.info("Loaded ${templates.size} templates")
                }
            } catch (e: Exception) {
                logger.error("Error loading templates", e)
                updateUiState { currentState ->
                    currentState.copy(errorMessage = "Error loading templates: ${e.message}")
                }
            }
        }
    }

    fun updateTemplateContent(content: String) {
        viewModelScope.launch {
            val validation = validateTemplate(content)
            updateUiState { currentState ->
                currentState.copy(
                    templateContent = content,
                    templateValidation = validation
                )
            }
        }
    }

    fun updateSystemPrompt(systemPrompt: String) {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(systemPrompt = systemPrompt)
            }
        }
    }

    fun updatePlaceholderData(key: String, value: String) {
        viewModelScope.launch {
            placeholderMutex.withLock {
                val updatedData = uiState.placeholderData.toMutableMap().apply {
                    this[key] = value
                }
                updateUiState { currentState ->
                    currentState.copy(placeholderData = updatedData)
                }
            }
        }
    }

    fun addPlaceholder(customName: String = "") {
        viewModelScope.launch {
            placeholderMutex.withLock {
                val validName = customName.replace(Regex("[^a-zA-Z0-9_]"), "")

                if (customName.isNotBlank() && customName.contains("var") &&
                    uiState.placeholderData.isNotEmpty()) {
                    return@withLock
                }

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
                updateUiState { currentState ->
                    currentState.copy(placeholderData = updatedData)
                }
            }
        }
    }

    fun addDetectedPlaceholder(name: String) {
        viewModelScope.launch {
            placeholderMutex.withLock {
                if (!uiState.placeholderData.containsKey(name)) {
                    val updatedData = uiState.placeholderData.toMutableMap().apply {
                        this[name] = ""
                    }
                    updateUiState { currentState ->
                        currentState.copy(placeholderData = updatedData)
                    }
                }
            }
        }
    }

    fun removePlaceholder(key: String) {
        viewModelScope.launch {
            placeholderMutex.withLock {
                val updatedData = uiState.placeholderData.toMutableMap().apply {
                    remove(key)
                }

                updateUiState { currentState ->
                    currentState.copy(placeholderData = updatedData)
                }
            }
        }
    }

    fun renamePlaceholder(oldName: String, newName: String) {
        viewModelScope.launch {
            placeholderMutex.withLock {
                if (oldName == newName) return@withLock

                if (uiState.placeholderData.containsKey(newName)) {
                    updateUiState { currentState ->
                        currentState.copy(
                            errorMessage = "A variable with name '$newName' already exists"
                        )
                    }
                    return@withLock
                }

                val value = uiState.placeholderData[oldName] ?: ""
                val updatedData = uiState.placeholderData.toMutableMap().apply {
                    remove(oldName)
                    put(newName, value)
                }

                val updatedTemplate = uiState.templateContent.replace("{{$oldName}}", "{{$newName}}")
                val validation = validateTemplate(updatedTemplate)

                updateUiState { currentState ->
                    currentState.copy(
                        placeholderData = updatedData,
                        templateContent = updatedTemplate,
                        templateValidation = validation
                    )
                }
            }
        }
    }

    private fun validateTemplate(content: String): ValidationResult {
        return promptGeneratorService.validateTemplate(content)
    }

    fun generatePrompts() {
        if (uiState.isGenerating) {
            logger.info("Generation already in progress")
            return
        }

        logger.info("Generate button clicked, starting generation process")
        logger.info("Current template content: '${uiState.templateContent}'")
        logger.info("Placeholder data: ${uiState.placeholderData}")

        viewModelScope.launch {
            try {
                stateMutex.withLock {
                    isUpdating.value = true

                    logger.info("Setting up generation state")
                    val validation = validateTemplate(uiState.templateContent)
                    val placeholders = promptGeneratorService.extractPlaceholders(uiState.templateContent)
                    logger.info("Template has ${placeholders.size} placeholders: $placeholders")

                    if (!validation.isValid) {
                        logger.warn("Template validation failed: ${validation.errors.firstOrNull()}")
                        updateUiState { currentState ->
                            currentState.copy(
                                errorMessage = "Template error: ${validation.errors.firstOrNull()}"
                            )
                        }
                        isUpdating.value = false
                        return@withLock
                    }

                    val placeholderData = prepareDataForGeneration(uiState.placeholderData)
                    logger.info("Placeholder data prepared: ${placeholderData.keys}")

                    val allPlaceholders = promptGeneratorService.extractPlaceholders(uiState.templateContent)
                        .associateWith { "" as Any }
                        .toMutableMap()

                    val userPlaceholderData = prepareDataForGeneration(uiState.placeholderData)
                    userPlaceholderData.forEach { (key, value) ->
                        allPlaceholders[key] = value
                    }

                    logger.info("Complete placeholder data map: $allPlaceholders")

                    val template = Template(
                        id = uiState.templateId.ifBlank { UUID.randomUUID().toString() },
                        name = uiState.templateName.ifBlank { "Untitled Template" },
                        content = uiState.templateContent
                    )

                    logger.info("Starting generation with template: '${template.name}', placeholder count: ${allPlaceholders.size}")

                    failedRequests.clear()

//                    TODO - из-за него зависает на PREPARING, гад
//                    updateUiState { currentState ->
//                        currentState.copy(
//                            isGenerating = true,
//                            generationId = "",
//                            errorMessage = null,
//                            generationStatus = GenerationStatus.PREPARING,
//                            generationProgress = 0f,
//                            completedCount = 0,
//                            totalCount = 0
//                        )
//                    }

                    isUpdating.value = false

                    try {
                        generateJob?.cancel()
                        generateJob = launch {
                            logger.info("Launch generation job")
                            try {
                                logger.info("Calling promptGeneratorService.generate()")
                                promptGeneratorService.generate(
                                    template = template,
                                    data = allPlaceholders,
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
                                        logger.info("Generation state update: status=${state.status}, complete=${state.isComplete}, responses=${state.responses.size}")
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
                        updateUiState { currentState ->
                            currentState.copy(
                                isGenerating = false,
                                errorMessage = "Failed to start generation: ${e.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Unexpected error starting generation", e)
                uiState = uiState.copy(
                    isGenerating = false,
                    errorMessage = "Error starting generation: ${e.message}"
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
            updateUiState { currentState ->
                currentState.copy(networkError = primaryError)
            }

            errorResponses.forEach { (id, response) ->
                state.responses.keys.find { it == id }?.let { responseId ->
                    state.template.let { template ->
                        val requestContent = if (response.content.isBlank()) {
                            template.content
                        } else {
                            response.content
                        }
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
        try {
            logger.info("Preparing data for generation, raw input: $placeholderData")
            val result = placeholderData.mapValues { (key, value) ->
                try {
                    when {
                        value.isBlank() -> ""
                        value.contains(",") -> {
                            val items = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (items.isEmpty()) "" else items
                        }
                        else -> value
                    }
                } catch (e: Exception) {
                    logger.error("Error processing placeholder $key with value '$value'", e)
                    value
                }
            }

            logger.info("Data preparation complete: $result")
            return result
        } catch (e: Exception) {
            logger.error("Error in prepareDataForGeneration", e)
            return placeholderData.mapValues { it.value }
        }
    }

    private suspend fun updateStateFromGeneration(state: GenerationState) = stateMutex.withLock {
        try {
            isUpdating.value = true

            if (uiState.generationId.isEmpty() || state.id == uiState.generationId) {
                var updatedState = uiState.copy(
                    generationId = state.id,
                    isGenerating = !state.isComplete,
                    generationStatus = state.status,
                    generationProgress = state.progress,
                    completedCount = state.completedCount,
                    totalCount = state.totalCount,
                    errorMessage = state.error
                )

                if (state.isComplete) {
                    updatedState = updatedState.copy(
                        isGenerating = false,
                        results = if (state.status == GenerationStatus.COMPLETED) state.responses else uiState.results,
                        partialResponses = if (state.status != GenerationStatus.COMPLETED) state.responses else uiState.partialResponses
                    )

                    logger.info("Generation complete with status: ${state.status}")
                } else {
                    updatedState = updatedState.copy(
                        partialResponses = state.responses
                    )
                }

                uiState = updatedState
            }
        } catch (e: Exception) {
            logger.error("Error updating state from generation", e)
        } finally {
            isUpdating.value = false
        }
    }

    private suspend fun updateUiState(update: (GeneratorUiState) -> GeneratorUiState) = stateMutex.withLock {
        uiState = update(uiState)
    }

    private fun updateUiAfterCancellation() {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    isGenerating = false,
                    generationStatus = GenerationStatus.CANCELLED,
                    errorMessage = "Generation cancelled"
                )
            }
        }
    }

    private fun updateUiAfterError(error: Exception) {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    isGenerating = false,
                    generationStatus = GenerationStatus.ERROR,
                    errorMessage = "Error: ${error.message}"
                )
            }
        }
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
                    updateUiState { currentState ->
                        currentState.copy(
                            templateId = savedTemplate.id,
                            templateName = savedTemplate.name,
                            successMessage = "Template saved successfully"
                        )
                    }
                    loadAllTemplates()

                }.onFailure { error ->
                    updateUiState { currentState ->
                        currentState.copy(
                            errorMessage = "Failed to save template: ${error.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error saving template", e)
                updateUiState { currentState ->
                    currentState.copy(
                        errorMessage = "Error saving template: ${e.message}"
                    )
                }
            }
        }
    }

    fun loadTemplateDirectly(template: Template) {
        viewModelScope.launch {
            placeholderMutex.withLock {
                val newPlaceholders = promptGeneratorService.extractPlaceholders(template.content)
                val placeholderData = mutableMapOf<String, String>()

                newPlaceholders.forEach { placeholder ->
                    placeholderData[placeholder] = uiState.placeholderData[placeholder] ?: ""
                }

                updateUiState { currentState ->
                    currentState.copy(
                        templateId = template.id,
                        templateName = template.name,
                        templateContent = template.content,
                        placeholderData = placeholderData,
                        templateValidation = validateTemplate(template.content),
                        successMessage = "Template loaded successfully"
                    )
                }
            }
        }
    }

    fun loadTemplate(templateId: String, updatePlaceholders: Boolean = false) {
        viewModelScope.launch {
            try {
                placeholderMutex.withLock {
                    manageTemplatesUseCase.getTemplate(templateId).onSuccess { template ->
                        val newPlaceholders = promptGeneratorService.extractPlaceholders(template.content)
                        val placeholderData = if (updatePlaceholders) {
                            newPlaceholders.associateWith { "" }
                        } else {
                            val currentPlaceholders = uiState.placeholderData.toMutableMap()

                            newPlaceholders.forEach { placeholder ->
                                if (!currentPlaceholders.containsKey(placeholder)) {
                                    currentPlaceholders[placeholder] = ""
                                }
                            }

                            currentPlaceholders
                        }

                        updateUiState { currentState ->
                            currentState.copy(
                                templateId = template.id,
                                templateName = template.name,
                                templateContent = template.content,
                                placeholderData = placeholderData,
                                templateValidation = validateTemplate(template.content),
                                successMessage = "Template loaded successfully"
                            )
                        }
                    }.onFailure { error ->
                        updateUiState { currentState ->
                            currentState.copy(
                                errorMessage = "Failed to load template: ${error.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error loading template", e)
                updateUiState { currentState ->
                    currentState.copy(
                        errorMessage = "Error loading template: ${e.message}"
                    )
                }
            }
        }
    }

    fun createNewTemplate() {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    templateId = "",
                    templateName = "Untitled Template",
                    templateContent = "",
                    placeholderData = emptyMap(),
                    templateValidation = ValidationResult(true),
                    results = emptyMap(),
                    partialResponses = emptyMap()
                )
            }
        }
    }

    fun cancelGeneration() {
        viewModelScope.launch {
            try {
                logger.info("Cancelling generation from ViewModel")
                updateUiState { currentState ->
                    currentState.copy(
                        generationStatus = GenerationStatus.CANCELLED,
                        errorMessage = "Cancelling generation..."
                    )
                }
                withContext(Dispatchers.IO) {
                    promptGeneratorService.cancelGeneration()
                }
                generateJob?.cancel()
                updateUiState { currentState ->
                    currentState.copy(
                        isGenerating = false,
                        generationStatus = GenerationStatus.CANCELLED,
                        errorMessage = "Generation cancelled"
                    )
                }

                logger.info("Generation cancelled successfully")
            } catch (e: Exception) {
                logger.error("Error cancelling generation", e)
                updateUiState { currentState ->
                    currentState.copy(
                        isGenerating = false,
                        generationStatus = GenerationStatus.CANCELLED,
                        errorMessage = "Error cancelling generation: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearResults() {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    results = emptyMap(),
                    partialResponses = emptyMap(),
                    errorMessage = null,
                    successMessage = null,
                    networkError = null
                )
            }
            failedRequests.clear()
        }
    }

    fun clearMessage() {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    errorMessage = null,
                    successMessage = null
                )
            }
        }
    }

    fun clearNetworkError() {
        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    networkError = null
                )
            }
        }
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
                        updateUiState { currentState ->
                            currentState.copy(
                                successMessage = "Exported ${files.size} results to $exportPath",
                                exportPath = exportPath
                            )
                        }
                    }
                    .onFailure { error ->
                        logger.error("Failed to export results: ${error.message}", error)
                        updateUiState { currentState ->
                            currentState.copy(
                                errorMessage = "Failed to export results: ${error.message}"
                            )
                        }
                    }
            } catch (e: Exception) {
                logger.error("Error exporting results", e)
                updateUiState { currentState ->
                    currentState.copy(
                        errorMessage = "Error exporting results: ${e.message}"
                    )
                }
            }
        }
    }

    fun toggleShowPartialResults() {
        val newValue = !uiState.showPartialResults

        viewModelScope.launch {
            updateUiState { currentState ->
                currentState.copy(
                    showPartialResults = newValue
                )
            }

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

    fun retryFailedRequests() {
        viewModelScope.launch {
            try {
                val requestsToRetry = failedRequests.values.toList()
                failedRequests.clear()

                if (requestsToRetry.isEmpty()) {
                    logger.info("No failed requests to retry")
                    return@launch
                }

                updateUiState { currentState ->
                    currentState.copy(
                        networkError = null,
                        isGenerating = true,
                        generationStatus = GenerationStatus.SENDING_REQUESTS
                    )
                }

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
                updateUiState { currentState ->
                    currentState.copy(
                        errorMessage = "Error retrying requests: ${e.message}",
                        isGenerating = false
                    )
                }
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

                updateUiState { currentState ->
                    currentState.copy(
                        isGenerating = true,
                        generationStatus = GenerationStatus.SENDING_REQUESTS
                    )
                }

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
                updateUiState { currentState ->
                    currentState.copy(
                        errorMessage = "Error retrying request: ${e.message}",
                        isGenerating = false
                    )
                }
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
    val placeholderData: Map<String, String> = emptyMap(),
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