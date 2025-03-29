package com.promptgenerator.data.source.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class LLMClientUtils {
    private val logger = LoggerFactory.getLogger(LLMClientUtils::class.java)
    private val isCancelled = atomic(false)

    fun setCancelled(cancelled: Boolean) {
        isCancelled.value = cancelled
    }

    suspend fun <T> withRetry(
        requestId: String,
        maxRetries: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 30000,
        factor: Double = 2.0,
        retryOnPredicate: (Throwable) -> Boolean = { isRetryableError(it) },
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var attempt = 0

        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                logger.info("Operation cancelled for request: $requestId")
                throw e
            } catch (e: Exception) {
                attempt++

                if (isCancelled.value) {
                    logger.info("Operation cancelled during retry for request: $requestId")
                    throw CancellationException("Operation cancelled", e)
                }

                if (attempt >= maxRetries || !retryOnPredicate(e)) {
                    logger.error("Max retries reached or non-retriable error for request: $requestId", e)
                    throw e
                }

                logger.warn("Retry attempt $attempt/$maxRetries for request $requestId, backing off for $currentDelay ms. Error: ${e.message}")

                val jitter = (Random.nextDouble() * 0.2 * currentDelay).toLong()
                val delayWithJitter = currentDelay + jitter

                val delayChunks = 100L.milliseconds
                var remainingDelay = delayWithJitter

                while (remainingDelay > 0) {
                    if (isCancelled.value) {
                        logger.info("Retry wait cancelled for request: $requestId")
                        throw CancellationException("Operation cancelled during retry wait", e)
                    }

                    val delayAmount = min(delayChunks.inWholeMilliseconds, remainingDelay)
                    delay(delayAmount)
                    remainingDelay -= delayAmount
                }

                currentDelay = min((currentDelay * factor).toLong(), maxDelay)
            }
        }
    }

    private fun isRetryableError(e: Throwable): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("429") ||
                message.contains("rate limit") ||
                message.contains("rate_limit") ||
                message.contains("too many requests") ||
                message.contains("capacity") ||
                message.contains("overloaded") ||
                message.contains("throttl") ||
                message.contains("timeout") ||
                message.contains("timed out") ||
                message.contains("connection reset") ||
                message.contains("connection closed") ||
                message.contains("unavailable") ||
                message.contains("try again")
    }

    fun logRequestStart(provider: String, requestId: String, model: String) {
        logger.info("Sending request $requestId to $provider using model $model")
    }

    fun logRequestSuccess(provider: String, requestId: String) {
        logger.info("Successfully received response from $provider for request $requestId")
    }

    fun logRequestError(provider: String, requestId: String, error: Throwable, isRetry: Boolean = false) {
        val retryMsg = if (isRetry) " during retry" else ""
        logger.error("Error from $provider for request $requestId$retryMsg: ${error.message}", error)
    }
}