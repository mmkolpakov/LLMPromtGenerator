package com.promptgenerator.data.source.remote

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.exception.OpenAIAPIException
import com.aallam.openai.api.exception.OpenAIIOException
import com.aallam.openai.api.exception.OpenAITimeoutException
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.*
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.config.RequestDefaults
import com.promptgenerator.domain.model.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class OpenAIClient(
    private val providerConfig: ProviderConfig,
    private val requestDefaults: RequestDefaults
) : LLMClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val client: OpenAI
    private val utils = LLMClientUtils()
    private val isCancelled = AtomicBoolean(false)

    init {
        val config = OpenAIConfig(
            token = providerConfig.apiKey,
            host = OpenAIHost(baseUrl = providerConfig.baseUrl),
            timeout = Timeout(
                request = 120.seconds,  // Increased timeout
                connect = 60.seconds,
                socket = 120.seconds    // Increased timeout
            ),
            retry = RetryStrategy(
                maxRetries = 3,
                base = 2.0,
                maxDelay = 10.seconds
            )
        )
        client = OpenAI(config)
        logger.info("OpenAIClient initialized for host: ${config.host.baseUrl} with model: ${providerConfig.defaultModel}")
    }

    override suspend fun sendCompletionRequest(request: Request): String {
        if (isCancelled.get()) {
            throw CancellationException("Request cancelled before sending")
        }

        logger.debug("Sending request to OpenAI: ${request.id}")

        val messages = listOf(
            ChatMessage(
                role = ChatRole.User,
                content = request.content
            )
        )

        val chatRequest = ChatCompletionRequest(
            model = ModelId(providerConfig.defaultModel),
            messages = messages,
            maxTokens = requestDefaults.maxTokens,
            temperature = requestDefaults.temperature,
            topP = requestDefaults.topP
        )

        utils.logRequestStart("OpenAI", request.id, providerConfig.defaultModel)

        return try {
            // Set a timeout for the entire operation
            withTimeout(180_000) { // 3 minutes total timeout
                utils.withRetry(request.id) {
                    try {
                        // Try non-streaming request first
                        val chatCompletion = client.chatCompletion(chatRequest)
                        val result = chatCompletion.choices.firstOrNull()?.message?.content ?: ""

                        if (result.isBlank()) {
                            logger.warn("OpenAI returned an empty response for request: ${request.id}")
                        }

                        utils.logRequestSuccess("OpenAI", request.id)
                        result
                    } catch (e: OpenAIIOException) {
                        logger.warn("Non-streaming request failed for ${request.id}, trying streaming fallback. Error: ${e.message}", e)
                        executeStreamingFallback(chatRequest, request.id, e)
                    } catch (e: OpenAITimeoutException) {
                        logger.warn("OpenAI request timed out for ${request.id}, trying streaming fallback", e)
                        executeStreamingFallback(chatRequest, request.id, e)
                    } catch (e: Exception) {
                        if (isCancelled.get()) throw CancellationException("Request cancelled during execution")
                        throw e
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Request ${request.id} timed out after 3 minutes", e)
            throw RuntimeException("Request timed out after 3 minutes", e)
        } catch (e: CancellationException) {
            logger.info("Request ${request.id} was cancelled")
            throw e
        } catch (e: OpenAIAPIException) {
            utils.logRequestError("OpenAI", request.id, e)
            val errorDetails = "Status=${e.statusCode}, Type=${e.error.detail?.type}, Message=${e.error.detail?.message}"
            throw RuntimeException("OpenAI API error: $errorDetails", e)
        } catch (e: Exception) {
            utils.logRequestError("OpenAI", request.id, e)
            throw RuntimeException("OpenAI request failed: ${e.message}", e)
        }
    }

    private suspend fun executeStreamingFallback(chatRequest: ChatCompletionRequest, requestId: String, originalException: Throwable): String {
        logger.debug("Executing streaming fallback for request: $requestId")

        return try {
            client.chatCompletions(chatRequest)
                .mapNotNull { chunk -> chunk.choices.firstOrNull()?.delta?.content }
                .catch { streamError ->
                    utils.logRequestError("OpenAI", requestId, streamError as Exception, isRetry = true)
                    throw RuntimeException("Streaming fallback failed for request $requestId after initial error.", streamError)
                }
                .map {
                    if (isCancelled.get()) throw CancellationException("Request cancelled during streaming")
                    it
                }
                .reduce { acc, value -> acc + value }
                .also {
                    if (it.isBlank()) {
                        logger.warn("Streaming fallback for request $requestId resulted in empty content. Initial error was: ${originalException.message}")
                        throw RuntimeException("Streaming fallback for $requestId completed but produced no content.", originalException)
                    }
                    logger.debug("Received response from OpenAI (streaming fallback): $requestId")
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            utils.logRequestError("OpenAI", requestId, e, isRetry = true)
            throw RuntimeException("Unexpected error in streaming fallback for request $requestId.", e)
        }
    }

    override fun cancelRequests() {
        isCancelled.set(true)
        utils.setCancelled(true)
    }

    override fun close() {
        try {
            client.close()
            logger.info("OpenAIClient closed.")
        } catch (e: Exception) {
            logger.error("Error closing OpenAI client", e)
        }
    }
}