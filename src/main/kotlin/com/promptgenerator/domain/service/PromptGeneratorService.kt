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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
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
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

class PromptGeneratorService(
    private val templateRepository: TemplateRepository,
    private val requestRepository: RequestRepository,
    private val resultRepository: ResultRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentGenerationState = MutableStateFlow<GenerationState?>(null)
    val currentGenerationState: StateFlow<GenerationState?> = _currentGenerationState.asStateFlow()

    private val activeGenerationJobs = ConcurrentHashMap<String, Job>()
    private val isCancellationRequested = atomic(false)

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
        isCancellationRequested.value = false

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

                val processingTime = measureTimeMillis {
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

                    if (isCancellationRequested.value) {
                        throw CancellationException("Generation was cancelled after template processing")
                    }

                    stateFlow.update {
                        it.copy(
                            status = GenerationStatus.SENDING_REQUESTS,
                            totalCount = requests.size
                        )
                    }
                    _currentGenerationState.value = stateFlow.value

                    val responses = ConcurrentHashMap<String, Response>()

                    try {
                        withTimeout(600_000) {
                            requestRepository.sendRequests(requests) { requestId, content, error ->
                                val response = Response(requestId, content, error)
                                responses[requestId] = response

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
                                    if (isCancellationRequested.value) {
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
                }

                logger.info("Template processing took $processingTime ms for generation $generationId")
                logger.info("Request collection finished for generation $generationId")

                if (isCancellationRequested.value) {
                    throw CancellationException("Generation was cancelled after requests completed")
                }

                stateFlow.update { it.copy(status = GenerationStatus.PROCESSING_RESULTS) }
                _currentGenerationState.value = stateFlow.value

                val saveTime = measureTimeMillis {
                    val result = resultRepository.processResults(
                        generationId = generationId,
                        templateId = template.id,
                        templateName = template.name,
                        placeholders = data,
                        responses = stateFlow.value.responses,
                        isComplete = true
                    )

                    resultRepository.saveResult(result)
                }

                logger.info("Result saving took $saveTime ms for generation $generationId")
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

                try {
                    val partialResult = resultRepository.processResults(
                        generationId = generationId,
                        templateId = template.id,
                        templateName = template.name,
                        placeholders = data,
                        responses = stateFlow.value.responses,
                        isComplete = false
                    )

                    resultRepository.saveResult(partialResult)
                } catch (ex: Exception) {
                    logger.error("Error saving partial results during cancellation: $generationId", ex)
                }

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
                    try {
                        val partialResult = resultRepository.processResults(
                            generationId = generationId,
                            templateId = template.id,
                            templateName = template.name,
                            placeholders = data,
                            responses = stateFlow.value.responses,
                            isComplete = false
                        )

                        resultRepository.saveResult(partialResult)
                    } catch (ex: Exception) {
                        logger.error("Error saving partial results after error: $generationId", ex)
                    }
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
                isCancellationRequested.value = false
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
                    .collectLatest { updatedResponses ->
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
            isCancellationRequested.value = true

            requestRepository.cancelRequests()

            for (job in activeGenerationJobs.values) {
                job.cancel()
            }

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
        isCancellationRequested.value = false

        for (job in activeGenerationJobs.values) {
            job.cancel()
        }

        activeGenerationJobs.clear()
        requestRepository.close()
        scope.cancel()
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