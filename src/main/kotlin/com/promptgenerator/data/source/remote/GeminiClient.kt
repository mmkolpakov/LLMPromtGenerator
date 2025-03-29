package com.promptgenerator.data.source.remote

import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.config.RequestDefaults
import com.promptgenerator.domain.model.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import org.apache.http.HttpException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class GeminiClient(
    private val providerConfig: ProviderConfig,
    private val requestDefaults: RequestDefaults
) : LLMClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val utils = LLMClientUtils()
    private val isCancelled = atomic(false)
    private val activeRequests = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestMutex = Mutex()
    private val client: Client

    init {
        logger.info("Initializing Gemini client for model: ${providerConfig.defaultModel}")

        if (providerConfig.apiKey.isBlank()) {
            logger.warn("API key is blank - Gemini API will likely reject requests!")
        }

        client = Client.builder()
            .apiKey(providerConfig.apiKey)
            .build()

        logger.info("Google Gen AI SDK client initialized successfully")
    }

    override suspend fun sendCompletionRequest(request: Request): String {
        logger.debug("Sending request to Gemini: ${request.id}")
        utils.logRequestStart("Gemini", request.id, providerConfig.defaultModel)

        if (isCancelled.value) {
            throw CancellationException("Request cancelled before sending")
        }

        if (providerConfig.apiKey.isBlank()) {
            throw RuntimeException("API key is required for Gemini API")
        }

        return withContext(Dispatchers.IO) {
            utils.withRetry(request.id) {
                val requestJob = scope.launch {
                    try {
                        requestMutex.withLock {
                            if (!isActive || isCancelled.value) {
                                return@withLock
                            }

                            val result = withTimeoutOrNull(300_000) {
                                sendGeminiRequest(
                                    prompt = request.content,
                                    systemPrompt = request.systemInstruction
                                )
                            } ?: throw RuntimeException("Request timed out after 5 minutes")

                            utils.logRequestSuccess("Gemini", request.id)
                        }
                    } catch (e: CancellationException) {
                        logger.info("Gemini request cancelled: ${request.id}")
                        throw e
                    } catch (e: Exception) {
                        utils.logRequestError("Gemini", request.id, e)
                        logger.error("Gemini API request failed for ${request.id}: ${e.message}", e)
                        throw e
                    } finally {
                        activeRequests.remove(request.id)
                    }
                }

                activeRequests[request.id] = requestJob

                val result = sendGeminiRequest(
                    prompt = request.content,
                    systemPrompt = request.systemInstruction
                )

                utils.logRequestSuccess("Gemini", request.id)
                result
            }
        }
    }

    private suspend fun sendGeminiRequest(prompt: String, systemPrompt: String? = null): String {
        if (isCancelled.value) {
            throw CancellationException("Request cancelled during execution")
        }

        return withContext(Dispatchers.IO) {
            try {
                val config = GenerateContentConfig.builder()
                    .temperature(requestDefaults.temperature.toFloat())
                    .topP(requestDefaults.topP.toFloat())
                    .maxOutputTokens(requestDefaults.maxTokens)
                    .build()

                val modelName = providerConfig.defaultModel
                var fullPrompt = prompt

                if (!systemPrompt.isNullOrBlank()) {
                    fullPrompt = "System instructions: $systemPrompt\n\nUser request: $prompt"
                }

                val response: GenerateContentResponse = client.models.generateContent(
                    modelName,
                    fullPrompt,
                    config
                )

                if (response.candidates().isEmpty()) {
                    logger.warn("No candidates returned from Gemini API")
                    return@withContext "No response generated"
                }

                val responseText = response.text() ?: "No Response"

                return@withContext responseText
            } catch (e: IOException) {
                logger.error("IO error when calling Gemini API: ${e.message}")
                throw RuntimeException("IO error when calling Gemini API: ${e.message}", e)
            } catch (e: HttpException) {
                logger.error("HTTP error when calling Gemini API: ${e.message}")
                throw RuntimeException("HTTP error when calling Gemini API: ${e.message}", e)
            }
        }
    }

    override fun cancelRequests() {
        isCancelled.value = true
        utils.setCancelled(true)

        val jobs = activeRequests.values.toList()
        jobs.forEach { job ->
            job.cancel()
        }

        activeRequests.clear()
        logger.info("Request cancellation completed")
    }

    override fun close() {
        try {
            isCancelled.value = false
            utils.setCancelled(false)

            val jobs = activeRequests.values.toList()
            jobs.forEach { job ->
                job.cancel()
            }

            activeRequests.clear()
            scope.cancel()
            logger.info("Gemini client closed")
        } catch (e: Exception) {
            logger.error("Error closing Gemini client", e)
        }
    }
}