package com.promptgenerator.data.source.remote

import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.promptgenerator.config.ProviderConfig
import com.promptgenerator.config.RequestDefaults
import com.promptgenerator.domain.model.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.http.HttpException

class GeminiClient(
    private val providerConfig: ProviderConfig,
    private val requestDefaults: RequestDefaults
) : LLMClient {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val utils = LLMClientUtils()
    private val isCancelled = AtomicBoolean(false)
    private val client: Client

    init {
        logger.info("Initializing Gemini client for model: ${providerConfig.defaultModel}")

        if (providerConfig.apiKey.isBlank()) {
            logger.warn("API key is blank - Gemini API will likely reject requests!")
        }

        // Initialize the official Google Gemini client
        client = Client.builder()
            .apiKey(providerConfig.apiKey)
            .build()

        logger.info("Google Gen AI SDK client initialized successfully")
    }

    override suspend fun sendCompletionRequest(request: Request): String {
        logger.debug("Sending request to Gemini: ${request.id}")
        utils.logRequestStart("Gemini", request.id, providerConfig.defaultModel)

        if (isCancelled.get()) {
            throw CancellationException("Request cancelled before sending")
        }

        if (providerConfig.apiKey.isBlank()) {
            throw RuntimeException("API key is required for Gemini API")
        }

        return try {
            withContext(Dispatchers.IO) {
                utils.withRetry(request.id) {
                    val result = sendGeminiRequest(request.content)
                    utils.logRequestSuccess("Gemini", request.id)
                    result
                }
            }
        } catch (e: CancellationException) {
            logger.info("Request cancelled: ${request.id}")
            throw e
        } catch (e: Exception) {
            utils.logRequestError("Gemini", request.id, e)
            logger.error("Gemini API request failed for ${request.id}: ${e.message}", e)
            throw RuntimeException("Gemini API request failed: ${e.message}", e)
        }
    }

    private fun sendGeminiRequest(prompt: String): String {
        if (isCancelled.get()) {
            throw CancellationException("Request cancelled during execution")
        }

        try {
            // Create the configuration with the request defaults
            val config = GenerateContentConfig.builder()
                .temperature(requestDefaults.temperature.toFloat())
                .topP(requestDefaults.topP.toFloat())
                .maxOutputTokens(requestDefaults.maxTokens)
                .build()

            // Select the model to use
            val modelName = providerConfig.defaultModel

            // Use the simple text input method for generating content
            val response: GenerateContentResponse = client.models.generateContent(
                modelName,
                prompt,
                config
            )

            // Check for finished response
            if (response.candidates().isEmpty()) {
                logger.warn("No candidates returned from Gemini API")
                return "No response generated"
            }

            // Extract and process the text from the response
            val responseText = response.text() ?: "No Response"

            return responseText

        } catch (e: IOException) {
            logger.error("IO error when calling Gemini API: ${e.message}")
            throw RuntimeException("IO error when calling Gemini API: ${e.message}", e)
        } catch (e: HttpException) {
            logger.error("HTTP error when calling Gemini API: ${e.message}")
            throw RuntimeException("HTTP error when calling Gemini API: ${e.message}", e)
        }
    }

    override fun cancelRequests() {
        isCancelled.set(true)
        utils.setCancelled(true)
        logger.info("Request cancellation flag set")
    }

    override fun close() {
        try {
            // The Google client doesn't have an explicit close method,
            // but we should release any resources we're holding
            logger.info("Gemini client closed")
        } catch (e: Exception) {
            logger.error("Error closing Gemini client", e)
        }
    }
}