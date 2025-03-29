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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlin.time.Duration.Companion.seconds

class OpenAIClient(
    private val providerConfig: ProviderConfig,
    private val requestDefaults: RequestDefaults
) : LLMClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val client: OpenAI
    private val utils = LLMClientUtils()
    private val isCancelled = atomic(false)
    private val activeRequests = mutableMapOf<String, Job>()
    private val completionChannel = Channel<String>(Channel.BUFFERED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        val config = OpenAIConfig(
            token = providerConfig.apiKey,
            host = OpenAIHost(baseUrl = providerConfig.baseUrl),
            timeout = Timeout(
                request = 120.seconds,
                connect = 60.seconds,
                socket = 120.seconds
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
        if (isCancelled.value) {
            throw CancellationException("Request cancelled before sending")
        }

        logger.debug("Sending request to OpenAI: ${request.id}")

        val messages = mutableListOf<ChatMessage>()

        request.systemInstruction?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                messages.add(
                    ChatMessage(
                        role = ChatRole.System,
                        content = systemPrompt
                    )
                )
            }
        }

        messages.add(
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

        try {
            return withTimeout(180_000) {
                utils.withRetry(request.id) {
                    try {
                        val requestJob = scope.launch {
                            try {
                                val chatCompletion = client.chatCompletion(chatRequest)
                                val result = chatCompletion.choices.firstOrNull()?.message?.content ?: ""

                                if (result.isBlank()) {
                                    logger.warn("OpenAI returned an empty response for request: ${request.id}")
                                }

                                utils.logRequestSuccess("OpenAI", request.id)
                                completionChannel.send(result)
                            } catch (e: OpenAIIOException) {
                                logger.warn("Non-streaming request failed for ${request.id}, trying streaming fallback. Error: ${e.message}", e)
                                executeStreamingRequest(chatRequest, request.id, e)
                            } catch (e: OpenAITimeoutException) {
                                logger.warn("OpenAI request timed out for ${request.id}, trying streaming fallback", e)
                                executeStreamingRequest(chatRequest, request.id, e)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                logger.error("Error in OpenAI request: ${request.id}", e)
                                throw e
                            }
                        }

                        synchronized(activeRequests) {
                            activeRequests[request.id] = requestJob
                        }

                        val response = completionChannel.receive()

                        synchronized(activeRequests) {
                            activeRequests.remove(request.id)
                        }

                        response
                    } catch (e: Exception) {
                        if (isCancelled.value) throw CancellationException("Request cancelled during execution")
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

    private suspend fun executeStreamingRequest(
        chatRequest: ChatCompletionRequest,
        requestId: String,
        originalException: Throwable? = null
    ) {
        logger.debug("Executing streaming request for: $requestId")

        try {
            val contentBuilder = StringBuilder()

            client.chatCompletions(chatRequest)
                .catch { streamError ->
                    utils.logRequestError("OpenAI", requestId, streamError as Exception, isRetry = true)
                    if (contentBuilder.isEmpty()) {
                        throw RuntimeException("Streaming request failed for $requestId", streamError)
                    } else {
                        logger.warn("Streaming request for $requestId failed but partial content was received")
                    }
                }
                .onCompletion { error ->
                    if (error != null && contentBuilder.isEmpty()) {
                        logger.error("Streaming completion failed with no content: $requestId", error)
                    } else {
                        completionChannel.send(contentBuilder.toString())
                    }
                }
                .collectLatest { chunk ->
                    if (isCancelled.value) throw CancellationException("Request cancelled during streaming")

                    val content = chunk.choices.firstOrNull()?.delta?.content
                    if (content != null) {
                        contentBuilder.append(content)
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val exception = originalException?.let {
                RuntimeException("OpenAI request failed (streaming fallback): ${e.message}", it)
            } ?: e

            throw exception
        }
    }

    override fun cancelRequests() {
        isCancelled.value = true
        utils.setCancelled(true)

        synchronized(activeRequests) {
            activeRequests.values.forEach { job ->
                job.cancel()
            }
            activeRequests.clear()
        }
    }

    override fun close() {
        try {
            isCancelled.value = false

            synchronized(activeRequests) {
                activeRequests.values.forEach { job ->
                    job.cancel()
                }
                activeRequests.clear()
            }

            scope.cancel()
            client.close()
            logger.info("OpenAIClient closed.")
        } catch (e: Exception) {
            logger.error("Error closing OpenAI client", e)
        }
    }
}