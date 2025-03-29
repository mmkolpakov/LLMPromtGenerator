package com.promptgenerator.data.source.remote

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
        maxDelay: Long = 10000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var attempt = 0

        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                attempt++

                if (isCancelled.value) {
                    logger.info("Operation cancelled for request: $requestId")
                    throw e
                }

                if (attempt >= maxRetries) {
                    logger.error("Max retries reached for request: $requestId", e)
                    throw e
                }

                if (isRateLimitError(e)) {
                    logger.warn("Rate limit hit for request $requestId (attempt $attempt/$maxRetries), backing off for $currentDelay ms")

                    val jitter = Random.nextLong(currentDelay / 4)
                    val delayWithJitter = currentDelay + jitter

                    val delayChunks = 100L.milliseconds
                    var remainingDelay = delayWithJitter

                    while (remainingDelay > 0) {
                        if (isCancelled.value) {
                            logger.info("Retry wait cancelled for request: $requestId")
                            throw e
                        }

                        val delayAmount = min(delayChunks.inWholeMilliseconds, remainingDelay)
                        delay(delayAmount)
                        remainingDelay -= delayAmount
                    }

                    currentDelay = min((currentDelay * factor).toLong(), maxDelay)
                } else {
                    throw e
                }
            }
        }
    }

    private fun isRateLimitError(e: Exception): Boolean {
        val message = e.message?.lowercase() ?: ""
        return message.contains("429") ||
                message.contains("rate limit") ||
                message.contains("rate_limit") ||
                message.contains("too many requests") ||
                message.contains("capacity") ||
                message.contains("overloaded") ||
                message.contains("throttl")
    }

    fun logRequestStart(provider: String, requestId: String, model: String) {
        logger.info("Sending request $requestId to $provider using model $model")
    }

    fun logRequestSuccess(provider: String, requestId: String) {
        logger.info("Successfully received response from $provider for request $requestId")
    }

    fun logRequestError(provider: String, requestId: String, error: Exception, isRetry: Boolean = false) {
        val retryMsg = if (isRetry) " during retry" else ""
        logger.error("Error from $provider for request $requestId$retryMsg: ${error.message}", error)
    }
}