package com.promptgenerator.data.source.remote

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Utility class for LLM clients with common functionality
 */
class LLMClientUtils {
    private val logger = LoggerFactory.getLogger(LLMClientUtils::class.java)
    private val isCancelled = AtomicBoolean(false)

    /**
     * Sets the cancelled flag
     */
    fun setCancelled(cancelled: Boolean) {
        isCancelled.set(cancelled)
    }

    /**
     * Executes a block with retry logic for rate limiting
     */
    suspend fun <T> withRetry(
        requestId: String,
        maxRetries: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 10000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                // Check if cancelled
                if (isCancelled.get()) {
                    logger.info("Operation cancelled for request: $requestId")
                    throw e
                }

                // Check if we've reached max retries
                if (attempt == maxRetries - 1) throw e

                // If it's a rate limit error, apply backoff
                if (isRateLimitError(e)) {
                    logger.warn("Rate limit hit for request $requestId (attempt ${attempt + 1}/$maxRetries), backing off for $currentDelay ms")

                    // Wait before retry with jitter
                    val jitter = Random.nextLong(currentDelay / 4)
                    delay(currentDelay + jitter)

                    // Increase delay for next attempt
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                } else {
                    // Don't retry other errors
                    throw e
                }
            }
        }

        throw IllegalStateException("Retry loop exited unexpectedly")
    }

    /**
     * Checks if an exception is related to rate limiting
     */
    private fun isRateLimitError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("429") ||
                message.contains("rate limit") ||
                message.contains("too many requests") ||
                message.contains("capacity")
    }

    /**
     * Logs the start of a request
     */
    fun logRequestStart(provider: String, requestId: String, model: String) {
        logger.info("Sending request $requestId to $provider using model $model")
    }

    /**
     * Logs the completion of a request
     */
    fun logRequestSuccess(provider: String, requestId: String) {
        logger.info("Successfully received response from $provider for request $requestId")
    }

    /**
     * Logs an error for a request
     */
    fun logRequestError(provider: String, requestId: String, error: Exception, isRetry: Boolean = false) {
        val retryMsg = if (isRetry) " during retry" else ""
        logger.error("Error from $provider for request $requestId$retryMsg: ${error.message}", error)
    }
}