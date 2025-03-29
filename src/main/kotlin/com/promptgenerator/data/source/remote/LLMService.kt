package com.promptgenerator.data.source.remote

import com.promptgenerator.config.LLMConfig
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.domain.model.NetworkError
import com.promptgenerator.domain.model.NetworkErrorType
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LLMService(
    private val config: LLMConfig = LLMConfig(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val isCancelled = atomic(false)
    private val activeRequests = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val clientsMutex = Mutex()
    private val rateLimitingMutex = Mutex()
    private val jobsMutex = Mutex()

    private val providerName: String = config.defaultProvider
    private val providerConfig: ProviderConfig
    private val clients = ConcurrentHashMap<String, LLMClient>()
    private val requestTimestamps = mutableListOf<Long>()
    private val maxRequestsPerMinute: Int
    private val maxConcurrentRequests: Int
    private val modelName: String
    private val systemPrompt: String = config.defaultSystemPrompt
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val MAX_RETRY_ATTEMPTS = 3
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        logger.info("Initializing LLM Service with provider: $providerName")

        if (!config.providers.containsKey(providerName)) {
            throw IllegalStateException("Provider not found in configuration: $providerName")
        }

        providerConfig = config.providers[providerName]!!
        maxRequestsPerMinute = providerConfig.rateLimiting.requestsPerMinute
        maxConcurrentRequests = providerConfig.rateLimiting.maxConcurrent
        modelName = providerConfig.defaultModel

        try {
            clients.computeIfAbsent(providerName) { name ->
                val provCfg = config.providers[name]
                    ?: throw IllegalStateException("Provider not found: $name")

                if (provCfg.apiKey.isBlank() && !name.equals("ollama", ignoreCase = true)) {
                    throw IllegalStateException("API key missing for provider: $name")
                }

                when (name.lowercase()) {
                    "openai" -> {
                        OpenAIClient(provCfg, config.requestDefaults)
                    }
                    "anthropic" -> {
                        AnthropicClient(provCfg, config.requestDefaults)
                    }
                    "gemini" -> {
                        GeminiClient(provCfg, config.requestDefaults)
                    }
                    "ollama" -> {
                        val ollamaConfig = provCfg.copy(
                            baseUrl = "${provCfg.protocol}://${provCfg.baseUrl}:${provCfg.port}"
                        )
                        OpenAIClient(ollamaConfig, config.requestDefaults)
                    }
                    else -> {
                        logger.error("Provider $name is not supported")
                        throw IllegalArgumentException("Unsupported LLM provider: $name")
                    }
                }
            }
            logger.info("LLM Service initialized with model: $modelName and system prompt configured")
        } catch (e: Exception) {
            logger.error("Failed to initialize client for provider $providerName", e)
            throw e
        }
    }

    fun getMaxConcurrentRequests(): Int = maxConcurrentRequests

    fun getSystemPrompt(): String = systemPrompt

    private suspend fun getOrCreateClient(providerName: String): LLMClient = clientsMutex.withLock {
        return clients.computeIfAbsent(providerName) { name ->
            val providerConfig = config.providers[name]
                ?: throw IllegalStateException("Provider not found: $name")

            if (providerConfig.apiKey.isBlank() && !name.equals("ollama", ignoreCase = true)) {
                throw IllegalStateException("API key missing for provider: $name")
            }

            when (name.lowercase()) {
                "openai" -> {
                    OpenAIClient(providerConfig, config.requestDefaults)
                }
                "anthropic" -> {
                    AnthropicClient(providerConfig, config.requestDefaults)
                }
                "gemini" -> {
                    GeminiClient(providerConfig, config.requestDefaults)
                }
                "ollama" -> {
                    val ollamaConfig = providerConfig.copy(
                        baseUrl = "${providerConfig.protocol}://${providerConfig.baseUrl}:${providerConfig.port}"
                    )
                    OpenAIClient(ollamaConfig, config.requestDefaults)
                }
                else -> {
                    logger.error("Provider $name is not supported")
                    throw IllegalArgumentException("Unsupported LLM provider: $name")
                }
            }
        }
    }

    suspend fun sendRequest(request: Request, customSystemPrompt: String? = null): Response {
        if (isCancelled.value) {
            logger.info("Request ${request.id} skipped - cancellation is active")
            return Response(request.id, "", "Request cancelled")
        }

        activeRequests.add(request.id)
        failedAttempts.putIfAbsent(request.id, 0)
        logger.debug("Sending request: ${request.id}")

        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withRateLimit()

                    if (!isActive || isCancelled.value) {
                        logger.info("Request ${request.id} cancelled after rate limiting")
                        return@withContext
                    }

                    val client = getOrCreateClient(providerName)

                    val finalRequest = when {
                        !customSystemPrompt.isNullOrBlank() -> {
                            request.copy(systemInstruction = customSystemPrompt)
                        }
                        request.systemInstruction.isNullOrBlank() && systemPrompt.isNotBlank() -> {
                            request.copy(systemInstruction = systemPrompt)
                        }
                        else -> request
                    }

                    try {
                        val content = withTimeout(300_000) {
                            client.sendCompletionRequest(finalRequest)
                        }

                        logger.debug("Received response for request: ${request.id}")
                    } catch (e: TimeoutCancellationException) {
                        failedAttempts[request.id] = (failedAttempts[request.id] ?: 0) + 1
                        logger.error("Request timed out after ${e.message}", e)
                    } catch (e: CancellationException) {
                        logger.info("Request ${request.id} was cancelled during execution")
                    } catch (e: Exception) {
                        if (isCancelled.value) {
                            logger.info("Exception occurred but request was already cancelled: ${request.id}")
                        } else {
                            failedAttempts[request.id] = (failedAttempts[request.id] ?: 0) + 1
                            logger.error("Error sending request: ${request.id}", e)
                        }
                    }
                }
            } finally {
                jobsMutex.withLock {
                    activeRequests.remove(request.id)
                    activeJobs.remove(request.id)
                }
            }
        }

        jobsMutex.withLock {
            activeJobs[request.id] = job
        }

        try {
            if (isCancelled.value) {
                return Response(request.id, "", "Request cancelled")
            }

            withRateLimit()

            if (isCancelled.value) {
                logger.info("Request ${request.id} cancelled after rate limiting")
                return Response(request.id, "", "Request cancelled")
            }

            val client = getOrCreateClient(providerName)

            val finalRequest = when {
                !customSystemPrompt.isNullOrBlank() -> {
                    request.copy(systemInstruction = customSystemPrompt)
                }
                request.systemInstruction.isNullOrBlank() && systemPrompt.isNotBlank() -> {
                    request.copy(systemInstruction = systemPrompt)
                }
                else -> request
            }

            val timeoutMs = 300_000L

            val content = withTimeout(timeoutMs) {
                client.sendCompletionRequest(finalRequest)
            }

            logger.debug("Received response for request: ${request.id}")
            return Response(request.id, content)
        } catch (e: TimeoutCancellationException) {
            failedAttempts[request.id] = (failedAttempts[request.id] ?: 0) + 1
            val error = NetworkError(
                id = request.id,
                message = "Request timed out after 5 minutes",
                type = NetworkErrorType.TIMEOUT,
                timestamp = System.currentTimeMillis()
            )
            logger.error("Request timed out after ${e.message}", e)
            return Response(request.id, "", formatErrorMessage(error))
        } catch (e: CancellationException) {
            logger.info("Request ${request.id} was cancelled during execution")
            return Response(request.id, "", "Request cancelled")
        } catch (e: Exception) {
            if (isCancelled.value) {
                logger.info("Exception occurred but request was already cancelled: ${request.id}")
                return Response(request.id, "", "Request cancelled")
            }

            failedAttempts[request.id] = (failedAttempts[request.id] ?: 0) + 1
            val remainingAttempts = MAX_RETRY_ATTEMPTS - (failedAttempts[request.id] ?: 0)

            val errorType = determineErrorType(e)
            val error = NetworkError(
                id = request.id,
                message = e.message ?: "Unknown error",
                type = errorType,
                timestamp = System.currentTimeMillis()
            )

            logger.error("Error sending request: ${request.id}", e)
            return Response(
                id = request.id,
                content = "",
                error = formatErrorMessage(error, remainingAttempts)
            )
        } finally {
            jobsMutex.withLock {
                activeRequests.remove(request.id)
            }
        }
    }

    private fun formatErrorMessage(error: NetworkError, remainingAttempts: Int = 0): String {
        val retryInfo = if (remainingAttempts > 0) " ($remainingAttempts retries remaining)" else ""
        return when (error.type) {
            NetworkErrorType.TIMEOUT -> "Timeout: The request took too long to complete.$retryInfo"
            NetworkErrorType.RATE_LIMIT -> "Rate Limit: Provider's rate limit exceeded. Please wait and try again.$retryInfo"
            NetworkErrorType.AUTHORIZATION -> "Authorization Error: Invalid API key or permissions issue.$retryInfo"
            NetworkErrorType.CONNECTION -> "Connection Error: Could not connect to the LLM provider.$retryInfo"
            NetworkErrorType.UNKNOWN -> "Error: ${error.message}$retryInfo"
        }
    }

    private fun determineErrorType(error: Exception): NetworkErrorType {
        val message = error.message?.lowercase() ?: ""
        return when {
            message.contains("timeout") || message.contains("timed out") -> NetworkErrorType.TIMEOUT
            message.contains("rate limit") || message.contains("too many requests") -> NetworkErrorType.RATE_LIMIT
            message.contains("api key") || message.contains("unauthoriz") || message.contains("forbidden") -> NetworkErrorType.AUTHORIZATION
            message.contains("connect") || message.contains("host") -> NetworkErrorType.CONNECTION
            else -> NetworkErrorType.UNKNOWN
        }
    }

    private suspend fun withRateLimit() {
        if (isCancelled.value) {
            throw CancellationException("Request cancelled before rate limiting")
        }

        rateLimitingMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val oneMinuteAgo = currentTime - TimeUnit.MINUTES.toMillis(1)

            requestTimestamps.removeAll { it < oneMinuteAgo }

            if (requestTimestamps.size >= maxRequestsPerMinute) {
                val oldestRequest = requestTimestamps.minOrNull() ?: oneMinuteAgo
                val waitTime = TimeUnit.MINUTES.toMillis(1) - (currentTime - oldestRequest)

                if (waitTime > 0) {
                    logger.info("Rate limit reached. Waiting for $waitTime ms before next request")

                    var remainingWait = waitTime
                    val checkInterval = 100L

                    while (remainingWait > 0 && !isCancelled.value) {
                        val delayAmount = minOf(checkInterval, remainingWait)
                        delay(delayAmount)
                        remainingWait -= delayAmount

                        if (isCancelled.value) {
                            throw CancellationException("Request cancelled during rate limiting wait")
                        }
                    }
                }

                requestTimestamps.removeAll { it < currentTime - TimeUnit.MINUTES.toMillis(1) }
            }

            requestTimestamps.add(System.currentTimeMillis())
        }
    }

    fun cancelRequests() {
        logger.info("Cancelling all requests (${activeRequests.size} active)")
        isCancelled.value = true

        scope.launch {
            clientsMutex.withLock {
                clients.values.forEach { client ->
                    try {
                        client.cancelRequests()
                    } catch (e: Exception) {
                        logger.error("Error cancelling requests in client", e)
                    }
                }
            }

            jobsMutex.withLock {
                activeJobs.values.forEach { job ->
                    job.cancel()
                }
            }
        }

        logger.info("All requests cancellation initiated")
    }

    fun resetRetryCounter(requestId: String) {
        failedAttempts.remove(requestId)
    }

    fun close() {
        isCancelled.value = false

        scope.launch {
            clientsMutex.withLock {
                clients.values.forEach { client ->
                    try {
                        client.close()
                    } catch (e: Exception) {
                        logger.error("Error closing client", e)
                    }
                }
            }

            jobsMutex.withLock {
                activeJobs.values.forEach { job ->
                    job.cancel()
                }
            }
        }

        clients.clear()
        activeRequests.clear()
        requestTimestamps.clear()
        failedAttempts.clear()
        activeJobs.clear()

        logger.info("LLM Service closed")
    }
}