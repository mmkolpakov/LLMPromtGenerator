package com.promptgenerator.data.source.remote

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.config.RequestDefaults
import com.promptgenerator.domain.model.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException as JavaCancellationException

class AnthropicClient(
    private val providerConfig: ProviderConfig,
    private val requestDefaults: RequestDefaults
) : LLMClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val client: AnthropicClientAsync
    private val utils = LLMClientUtils()
    private val isCancelled = atomic(false)
    private val activeJobs = mutableMapOf<String, Job>()
    private val jobsMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        logger.info("Initializing Anthropic client...")
        try {
            client = AnthropicOkHttpClientAsync.builder()
                .apiKey(providerConfig.apiKey)
                .apply {
                    providerConfig.baseUrl.takeIf { it.isNotBlank() }?.let { baseUrl(it) }
                }
                .build()
            logger.info("Anthropic client initialized successfully.")
        } catch (e: Exception) {
            logger.error("Failed to initialize Anthropic client", e)
            throw IllegalStateException("Failed to initialize Anthropic client", e)
        }
    }

    override suspend fun sendCompletionRequest(request: Request): String {
        if (isCancelled.value) {
            throw CancellationException("Request cancelled before sending")
        }

        logger.debug("Sending request to Anthropic: ${request.id}")

        val modelId = providerConfig.defaultModel
        val maxTokens = (requestDefaults.maxTokens).toLong()
        val temperature = requestDefaults.temperature
        val topP = requestDefaults.topP

        val messages = mutableListOf<MessageParam>()

        request.systemInstruction?.let { systemPrompt ->
            if (systemPrompt.isNotBlank()) {
                messages.add(
                    MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(systemPrompt)
                        .build()
                )
            }
        }

        messages.add(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(request.content)
                .build()
        )

        val createParams = MessageCreateParams.builder()
            .model(Model.of(modelId))
            .maxTokens(maxTokens)
            .messages(messages)
            .temperature(temperature)
            .topP(topP)
            .build()

        utils.logRequestStart("Anthropic", request.id, modelId)

        return try {
            utils.withRetry(request.id) {
                if (isCancelled.value) {
                    throw CancellationException("Request cancelled during execution")
                }

                val responseJob = scope.launch {
                    try {
                        val responseMessage = client.messages().create(createParams).await()
                        utils.logRequestSuccess("Anthropic", request.id)
                        parseAnthropicResponse(responseMessage)
                    } catch (e: JavaCancellationException) {
                        throw CancellationException("Request cancelled during execution", e)
                    } catch (e: Exception) {
                        utils.logRequestError("Anthropic", request.id, e)
                        throw RuntimeException("Anthropic request failed for ${request.id}: ${e.message}", e)
                    } finally {
                        jobsMutex.withLock {
                            activeJobs.remove(request.id)
                        }
                    }
                }

                jobsMutex.withLock {
                    activeJobs[request.id] = responseJob
                }

                val responseMessage = client.messages().create(createParams).await()
                utils.logRequestSuccess("Anthropic", request.id)
                parseAnthropicResponse(responseMessage)
            }
        } catch (e: CancellationException) {
            logger.info("Request ${request.id} was cancelled")
            throw e
        } catch (e: Exception) {
            utils.logRequestError("Anthropic", request.id, e)
            throw RuntimeException("Anthropic request failed for ${request.id}: ${e.message}", e)
        }
    }

    private fun parseAnthropicResponse(message: Message?): String {
        if (message == null) {
            logger.warn("Anthropic returned a null message")
            return ""
        }

        val content = message.content()
        if (content.isEmpty()) {
            logger.warn("Anthropic returned an empty content list")
            return ""
        }

        return content
            .filter { it.isText() }.joinToString(separator = "") { it.asText().text() }
            .trim()
    }

    override fun cancelRequests() {
        isCancelled.value = true
        utils.setCancelled(true)

        scope.launch {
            jobsMutex.withLock {
                activeJobs.values.forEach { job ->
                    job.cancel()
                }
                activeJobs.clear()
            }
        }
    }

    override fun close() {
        logger.info("Closing Anthropic client...")
        try {
            isCancelled.value = false

            scope.launch {
                jobsMutex.withLock {
                    activeJobs.values.forEach { job ->
                        job.cancel()
                    }
                    activeJobs.clear()
                }
            }

            scope.cancel()
            client.close()
            logger.info("Anthropic client closed successfully.")
        } catch (e: Exception) {
            logger.error("Error closing Anthropic client", e)
        }
    }
}