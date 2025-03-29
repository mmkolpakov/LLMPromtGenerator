package com.promptgenerator.domain.service

import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.RequestRepository
import com.promptgenerator.domain.repository.ResultRepository
import com.promptgenerator.domain.repository.TemplateRepository
import com.promptgenerator.domain.repository.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PromptGeneratorService(
    private val templateRepository: TemplateRepository,
    private val requestRepository: RequestRepository,
    private val resultRepository: ResultRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _currentGenerationState = MutableStateFlow<GenerationState?>(null)
    val currentGenerationState: StateFlow<GenerationState?> = _currentGenerationState.asStateFlow()

    private val activeGenerationJobs = ConcurrentHashMap<String, Job>()
    private val isCancellationRequested = AtomicBoolean(false)

    fun validateTemplate(templateContent: String): ValidationResult {
        return templateRepository.validateTemplate(templateContent)
    }

    fun extractPlaceholders(templateContent: String): Set<String> {
        return templateRepository.extractPlaceholders(templateContent)
    }

    fun processTemplate(
        template: Template,
        data: Map<String, Any>,
        maxCombinations: Int = 1000,
        systemPrompt: String? = null
    ): List<Request> {
        return templateRepository.processTemplate(
            template = template,
            data = data,
            maxCombinations = maxCombinations,
            systemInstruction = systemPrompt
        )
    }

    fun generate(
        template: Template,
        data: Map<String, Any>,
        maxCombinations: Int = 1000,
        systemPrompt: String? = null
    ): Flow<GenerationState> {
        val generationId = UUID.randomUUID().toString()
        isCancellationRequested.set(false)

        val initialState = GenerationState(
            id = generationId,
            template = template,
            placeholders = data,
            status = GenerationStatus.PREPARING,
            responses = emptyMap(),
            completedCount = 0,
            totalCount = 0,
            isComplete = false,
            error = null
        )

        _currentGenerationState.value = initialState

        val stateFlow = MutableStateFlow(initialState)

        logger.info("Starting generation $generationId")

        val job = scope.launch {
            try {
                stateFlow.update { it.copy(status = GenerationStatus.PROCESSING_TEMPLATE) }
                _currentGenerationState.value = stateFlow.value

                val requests = templateRepository.processTemplate(
                    template,
                    data,
                    maxCombinations,
                    systemPrompt
                )

                if (requests.isEmpty()) {
                    logger.info("No requests generated from template for $generationId")
                    stateFlow.update {
                        it.copy(
                            status = GenerationStatus.COMPLETED,
                            error = "No requests generated from template",
                            isComplete = true
                        )
                    }
                    _currentGenerationState.value = stateFlow.value
                    return@launch
                }

                if (isCancellationRequested.get()) {
                    throw CancellationException("Generation was cancelled after template processing")
                }

                stateFlow.update {
                    it.copy(
                        status = GenerationStatus.SENDING_REQUESTS,
                        totalCount = requests.size
                    )
                }
                _currentGenerationState.value = stateFlow.value

                val responses = mutableMapOf<String, Response>()

                try {
                    withTimeout(600_000) {
                        requestRepository.sendRequests(requests) { requestId, content, error ->
                            val response = Response(requestId, content, error)

                            synchronized(responses) {
                                responses[requestId] = response
                            }
                            stateFlow.update {
                                it.copy(
                                    responses = responses.toMap(),
                                    completedCount = responses.size
                                )
                            }
                            _currentGenerationState.value = stateFlow.value
                        }
                            .flowOn(Dispatchers.IO)
                            .onStart {
                                logger.info("Starting request processing for generation $generationId")
                            }
                            .onEach { latestResponses ->
                                if (isCancellationRequested.get()) {
                                    throw CancellationException("Generation was cancelled during processing")
                                }

                                logger.info("Received update with ${latestResponses.size} responses for generation $generationId")

                                stateFlow.update {
                                    it.copy(
                                        responses = latestResponses,
                                        completedCount = latestResponses.size
                                    )
                                }
                                _currentGenerationState.value = stateFlow.value

                                if (latestResponses.size == requests.size) {
                                    logger.info("All responses received for generation $generationId")
                                }
                            }
                            .onCompletion { cause ->
                                logger.info("Request flow completed for generation $generationId, cause: $cause")

                                if (cause != null && cause !is CancellationException) {
                                    logger.error("Error during request processing for $generationId", cause)
                                }
                            }
                            .catch { e ->
                                logger.error("Exception in request stream for $generationId", e)

                                if (e is CancellationException) throw e

                                stateFlow.update {
                                    it.copy(
                                        status = GenerationStatus.ERROR,
                                        isComplete = true,
                                        error = "Error: ${e.message}"
                                    )
                                }
                                _currentGenerationState.value = stateFlow.value

                                throw e
                            }
                            .collect()
                    }
                } catch (e: CancellationException) {
                    logger.info("Request collection was cancelled for generation $generationId")
                    throw e
                }

                logger.info("Request collection finished for generation $generationId")
                if (isCancellationRequested.get()) {
                    throw CancellationException("Generation was cancelled after requests completed")
                }

                stateFlow.update { it.copy(status = GenerationStatus.PROCESSING_RESULTS) }
                _currentGenerationState.value = stateFlow.value

                val result = resultRepository.processResults(
                    generationId = generationId,
                    templateId = template.id,
                    templateName = template.name,
                    placeholders = data,
                    responses = stateFlow.value.responses,
                    isComplete = true
                )

                resultRepository.saveResult(result)

                logger.info("Generation $generationId completed successfully")

                stateFlow.update {
                    it.copy(
                        status = GenerationStatus.COMPLETED,
                        isComplete = true
                    )
                }
                _currentGenerationState.value = stateFlow.value

            } catch (e: CancellationException) {
                logger.info("Generation cancelled: $generationId")

                val partialResult = resultRepository.processResults(
                    generationId = generationId,
                    templateId = template.id,
                    templateName = template.name,
                    placeholders = data,
                    responses = stateFlow.value.responses,
                    isComplete = false
                )

                resultRepository.saveResult(partialResult)

                stateFlow.update {
                    it.copy(
                        status = GenerationStatus.CANCELLED,
                        isComplete = true,
                        error = "Generation cancelled"
                    )
                }

                _currentGenerationState.value = stateFlow.value

                throw e
            } catch (e: Exception) {
                logger.error("Error during generation: $generationId", e)

                if (stateFlow.value.responses.isNotEmpty()) {
                    val partialResult = resultRepository.processResults(
                        generationId = generationId,
                        templateId = template.id,
                        templateName = template.name,
                        placeholders = data,
                        responses = stateFlow.value.responses,
                        isComplete = false
                    )

                    resultRepository.saveResult(partialResult)
                }

                stateFlow.update {
                    it.copy(
                        status = GenerationStatus.ERROR,
                        isComplete = true,
                        error = "Error: ${e.message}"
                    )
                }

                _currentGenerationState.value = stateFlow.value
            } finally {
                activeGenerationJobs.remove(generationId)
                isCancellationRequested.set(false)
            }
        }

        activeGenerationJobs[generationId] = job

        return stateFlow
    }

    suspend fun retryRequests(
        template: Template,
        requests: List<Request>,
        existingResponses: Map<String, Response>
    ) {
        val currentState = _currentGenerationState.value
        if (currentState == null || currentState.isComplete) {
            logger.error("Cannot retry requests without an active generation")
            return
        }

        val generationId = currentState.id

        val job = scope.launch {
            try {
                _currentGenerationState.update {
                    it?.copy(
                        status = GenerationStatus.SENDING_REQUESTS,
                        isComplete = false
                    )
                }

                requestRepository.retryFailedRequests(requests, existingResponses)
                    .collect { updatedResponses ->
                        _currentGenerationState.update {
                            it?.copy(
                                responses = updatedResponses,
                                completedCount = updatedResponses.count { (_, response) -> response.error == null }
                            )
                        }
                    }

                _currentGenerationState.update {
                    it?.copy(
                        status = GenerationStatus.COMPLETED,
                        isComplete = true
                    )
                }

                // Save updated results
                _currentGenerationState.value?.let { state ->
                    val result = resultRepository.processResults(
                        generationId = generationId,
                        templateId = template.id,
                        templateName = template.name,
                        placeholders = state.placeholders,
                        responses = state.responses,
                        isComplete = true
                    )
                    resultRepository.saveResult(result)
                }

            } catch (e: Exception) {
                logger.error("Error retrying requests", e)
                _currentGenerationState.update {
                    it?.copy(
                        status = GenerationStatus.ERROR,
                        error = "Error retrying requests: ${e.message}",
                        isComplete = true
                    )
                }
            }
        }

        activeGenerationJobs[generationId] = job
    }

    suspend fun cancelGeneration() = withContext(Dispatchers.IO) {
        logger.info("Cancelling generation")

        try {
            isCancellationRequested.set(true)

            requestRepository.cancelRequests()

            activeGenerationJobs.values.forEach { it.cancel() }
            scope.coroutineContext[Job]?.cancelChildren()

            _currentGenerationState.value?.let { currentState ->
                if (!currentState.isComplete) {
                    _currentGenerationState.value = currentState.copy(
                        status = GenerationStatus.CANCELLED,
                        isComplete = true,
                        error = "Generation cancelled"
                    )
                }
            }

        } catch (e: Exception) {
            logger.error("Error cancelling generation", e)
        }
    }

    suspend fun exportResults(
        result: GenerationResult,
        directory: String = "generated_prompts"
    ): Result<List<String>> {
        return resultRepository.exportResults(result, directory)
    }

    fun close() {
        scope.coroutineContext[Job]?.cancel()
        activeGenerationJobs.values.forEach { it.cancel() }
        activeGenerationJobs.clear()
        requestRepository.close()
        isCancellationRequested.set(false)
    }
}

data class GenerationState(
    val id: String,
    val template: Template,
    val placeholders: Map<String, Any>,
    val status: GenerationStatus,
    val responses: Map<String, Response>,
    val completedCount: Int,
    val totalCount: Int,
    val isComplete: Boolean,
    val error: String?
) {
    val progress: Float = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
}

enum class GenerationStatus {
    PREPARING,
    PROCESSING_TEMPLATE,
    SENDING_REQUESTS,
    PROCESSING_RESULTS,
    COMPLETED,
    CANCELLED,
    ERROR
}