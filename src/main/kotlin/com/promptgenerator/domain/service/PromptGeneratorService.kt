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
import kotlinx.coroutines.flow.channelFlow
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

        logger.info("Starting new generation with ID: $generationId, template: ${template.name}, data: $data")

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

        return channelFlow {
            send(initialState)

            logger.info("Creating requests from template with ${data.size} placeholder values")

            val updatedState = initialState.copy(status = GenerationStatus.PROCESSING_TEMPLATE)
            _currentGenerationState.value = updatedState
            send(updatedState)

            try {
                logger.info("Calling templateRepository.processTemplate")
                val requests = templateRepository.processTemplate(
                    template,
                    data,
                    maxCombinations,
                    systemPrompt
                )
                logger.info("Received ${requests.size} requests from template processing")

                if (requests.isEmpty()) {
                    logger.warn("No requests generated from template")
                    val completedState = updatedState.copy(
                        status = GenerationStatus.COMPLETED,
                        error = "No requests could be generated from template",
                        isComplete = true
                    )
                    _currentGenerationState.value = completedState
                    send(completedState)
                    return@channelFlow
                }

                logger.info("Proceeding with ${requests.size} generated requests")

                val requestsState = updatedState.copy(
                    status = GenerationStatus.SENDING_REQUESTS,
                    totalCount = requests.size
                )
                _currentGenerationState.value = requestsState
                send(requestsState)

                logger.info("Calling requestRepository.sendRequests")
                requestRepository.sendRequests(requests) { requestId, content, error ->
                    logger.info("Progress update for request $requestId, error: $error")
                }
                    .collect { responses ->
                        logger.info("Received response update with ${responses.size} responses")

                        if (isCancellationRequested.value) {
                            logger.info("Generation was cancelled, stopping flow")
                            throw CancellationException("Generation was cancelled during processing")
                        }

                        val progressState = requestsState.copy(
                            responses = responses,
                            completedCount = responses.size
                        )
                        _currentGenerationState.value = progressState
                        send(progressState)
                    }

                logger.info("All requests completed, finalizing generation")
                val finalState = _currentGenerationState.value!!.copy(
                    status = GenerationStatus.COMPLETED,
                    isComplete = true
                )
                _currentGenerationState.value = finalState
                send(finalState)

            } catch (e: CancellationException) {
                logger.info("Generation process was cancelled")
                val cancelledState = _currentGenerationState.value!!.copy(
                    status = GenerationStatus.CANCELLED,
                    isComplete = true,
                    error = "Generation cancelled"
                )
                _currentGenerationState.value = cancelledState
                send(cancelledState)
                throw e
            } catch (e: Exception) {
                logger.error("Error during generation process", e)
                val errorState = _currentGenerationState.value!!.copy(
                    status = GenerationStatus.ERROR,
                    isComplete = true,
                    error = "Error: ${e.message}"
                )
                _currentGenerationState.value = errorState
                send(errorState)
            }
        }
    }

    fun retryRequests(
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