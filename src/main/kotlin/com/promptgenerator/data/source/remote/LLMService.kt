package com.promptgenerator.data.source.remote

import com.promptgenerator.config.ConfigLoader
import com.promptgenerator.config.LLMConfig
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.domain.model.NetworkError
import com.promptgenerator.domain.model.NetworkErrorType
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LLMService(
    private val config: LLMConfig = ConfigLoader.loadLLMConfig()
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val isCancelled = AtomicBoolean(false)
    private val activeRequests = ConcurrentHashMap.newKeySet<String>()

    private val providerName: String = config.defaultProvider
    private val providerConfig: ProviderConfig
    private val clients = ConcurrentHashMap<String, LLMClient>()
    private val requestCounter = AtomicInteger(0)
    private val rateLimitingMutex = Mutex()
    private val requestTimestamps = mutableListOf<Long>()
    private val maxRequestsPerMinute: Int
    private val maxConcurrentRequests: Int
    private val modelName: String
    private val systemPrompt: String = config.defaultSystemPrompt
    private val failedAttempts = ConcurrentHashMap<String, Int>()
    private val MAX_RETRY_ATTEMPTS = 3

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
            getOrCreateClient(providerName)
            logger.info("LLM Service initialized with model: $modelName and system prompt configured")
        } catch (e: Exception) {
            logger.error("Failed to initialize client for provider $providerName", e)
            throw e
        }
    }

    fun getMaxConcurrentRequests(): Int = maxConcurrentRequests

    fun getSystemPrompt(): String = systemPrompt

    private fun getOrCreateClient(providerName: String): LLMClient {
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
        if (isCancelled.get()) {
            logger.info("Request ${request.id} skipped - cancellation is active")
            return Response(request.id, "", "Request cancelled")
        }

        activeRequests.add(request.id)
        failedAttempts.putIfAbsent(request.id, 0)
        logger.debug("Sending request: ${request.id}")

        try {
            var cancelledDuringRateLimit = false
            try {
                applyRateLimit()
            } catch (e: CancellationException) {
                cancelledDuringRateLimit = true
                logger.info("Cancelled during rate limiting for request ${request.id}")
                throw e
            }

            if (cancelledDuringRateLimit || isCancelled.get()) {
                logger.info("Request ${request.id} cancelled after rate limiting")
                return Response(request.id, "", "Request cancelled")
            }

            val client = getOrCreateClient(providerName)

            val finalRequest = if (!customSystemPrompt.isNullOrBlank()) {
                request.copy(systemInstruction = customSystemPrompt)
            } else if (request.systemInstruction.isNullOrBlank() && systemPrompt.isNotBlank()) {
                request.copy(systemInstruction = systemPrompt)
            } else {
                request
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
            if (isCancelled.get()) {
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
            activeRequests.remove(request.id)
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

    private suspend fun applyRateLimit() {
        if (isCancelled.get()) {
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

                    rateLimitingMutex.unlock()
                    try {
                        val checkInterval = 100L
                        var remainingWait = waitTime

                        while (remainingWait > 0) {
                            if (isCancelled.get()) {
                                throw CancellationException("Request cancelled during rate limiting wait")
                            }

                            val delayAmount = minOf(checkInterval, remainingWait)
                            kotlinx.coroutines.delay(delayAmount)
                            remainingWait -= delayAmount
                        }
                    } finally {
                        rateLimitingMutex.lock()
                    }

                    if (isCancelled.get()) {
                        throw CancellationException("Request cancelled during rate limiting wait")
                    }
                }

                val newCurrentTime = System.currentTimeMillis()
                val newOneMinuteAgo = newCurrentTime - TimeUnit.MINUTES.toMillis(1)
                requestTimestamps.removeAll { it < newOneMinuteAgo }
            }

            requestTimestamps.add(System.currentTimeMillis())
            requestCounter.incrementAndGet()
        }
    }

    fun cancelRequests() {
        logger.info("Cancelling all requests (${activeRequests.size} active)")
        isCancelled.set(true)
        clients.values.forEach { client ->
            try {
                client.cancelRequests()
            } catch (e: Exception) {
                logger.error("Error cancelling requests in client", e)
            }
        }
        logger.info("All requests cancellation initiated")
    }

    fun resetRetryCounter(requestId: String) {
        failedAttempts.remove(requestId)
    }

    fun close() {
        isCancelled.set(false)
        clients.values.forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                logger.error("Error closing client", e)
            }
        }
        clients.clear()
        activeRequests.clear()
        requestTimestamps.clear()
        failedAttempts.clear()
        logger.info("LLM Service closed")
    }
}