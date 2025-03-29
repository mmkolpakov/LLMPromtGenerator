package com.promptgenerator.data.repository

import com.promptgenerator.data.source.remote.LLMService
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.repository.RequestRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RequestRepositoryImpl(
    private val llmService: LLMService
) : RequestRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val isCancelled = AtomicBoolean(false)
    private val pendingRetries = ConcurrentHashMap<String, Request>()

    override fun sendRequests(
        requests: List<Request>,
        onProgress: suspend (requestId: String, responseContent: String, error: String?) -> Unit
    ): Flow<Map<String, Response>> = channelFlow {
        logger.info("Sending ${requests.size} requests")

        val responses = ConcurrentHashMap<String, Response>()
        val pendingRequests = AtomicInteger(requests.size)
        isCancelled.set(false)

        try {
            val maxConcurrentRequests = llmService.getMaxConcurrentRequests()
            val semaphore = Semaphore(maxConcurrentRequests)
            val activeJobs = mutableListOf<kotlinx.coroutines.Job>()
            send(responses.toMap())

            for (request in requests) {
                if (isCancelled.get()) {
                    logger.info("Request sending cancelled before starting request ${request.id}")
                    break
                }

                val job = launch {
                    semaphore.withPermit {
                        try {
                            if (isCancelled.get()) {
                                logger.info("Request sending cancelled for ${request.id} after acquiring permit")
                                return@withPermit
                            }

                            logger.debug("Sending request: ${request.id}")

                            try {
                                val response = llmService.sendRequest(request)

                                responses[request.id] = response

                                if (response.error != null) {
                                    pendingRetries[request.id] = request
                                }

                                onProgress(request.id, response.content, response.error)
                                if (!isCancelled.get()) {
                                    send(responses.toMap())
                                }

                                if (pendingRequests.decrementAndGet() == 0) {
                                    logger.info("All requests completed for generation")
                                    send(responses.toMap())
                                }

                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                                logger.error("Error sending request: ${request.id}", e)

                                val errorResponse = Response(
                                    id = request.id,
                                    content = "",
                                    error = "Error: ${e.message}"
                                )

                                responses[request.id] = errorResponse
                                pendingRetries[request.id] = request
                                onProgress(request.id, "", e.message)

                                if (!isCancelled.get()) {
                                    send(responses.toMap())
                                }

                                if (pendingRequests.decrementAndGet() == 0) {
                                    logger.info("All requests completed for generation (with errors)")
                                    send(responses.toMap())
                                }
                            }
                        } catch (e: CancellationException) {
                            logger.info("Request ${request.id} was cancelled during execution")
                            throw e
                        } catch (e: Exception) {
                            logger.error("Unexpected error processing request ${request.id}", e)
                            if (pendingRequests.decrementAndGet() == 0) {
                                send(responses.toMap())
                            }
                        }
                    }
                }

                activeJobs.add(job)
            }
            try {
                for (job in activeJobs) {
                    if (!job.isCompleted && !job.isCancelled) {
                        job.join()
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Waiting for jobs was cancelled")
                activeJobs.forEach { if (!it.isCompleted) it.cancel() }
                throw e
            }

            logger.info("All jobs processed, sending final state with ${responses.size} responses")
            send(responses.toMap())

        } catch (e: CancellationException) {
            logger.info("Request sending was cancelled, sending partial results")
            send(responses.toMap())
            throw e
        } catch (e: Exception) {
            logger.error("Error sending requests", e)

            requests
                .filter { !responses.containsKey(it.id) }
                .forEach { request ->
                    val errorResponse = Response(
                        id = request.id,
                        content = "",
                        error = "Error: ${e.message}"
                    )
                    responses[request.id] = errorResponse
                    pendingRetries[request.id] = request
                }

            send(responses.toMap())
        }
    }
        .onCompletion { cause ->
            logger.info("Flow completing with cause: $cause")
        }
        .flowOn(Dispatchers.IO)

    override suspend fun cancelRequests() {
        logger.info("Cancelling requests")
        isCancelled.set(true)
        llmService.cancelRequests()
    }

    override fun retryRequest(request: Request): Flow<Response> = channelFlow {
        logger.info("Retrying request: ${request.id}")
        isCancelled.set(false)
        llmService.resetRetryCounter(request.id)

        try {
            val response = llmService.sendRequest(request)
            logger.info("Retry completed for request: ${request.id}")
            send(response)

            if (response.error == null) {
                pendingRetries.remove(request.id)
            }
        } catch (e: Exception) {
            logger.error("Error retrying request: ${request.id}", e)
            val errorResponse = Response(
                id = request.id,
                content = "",
                error = "Error during retry: ${e.message}"
            )
            send(errorResponse)
        }
    }.flowOn(Dispatchers.IO)

    override fun retryFailedRequests(requests: List<Request>, existingResponses: Map<String, Response>): Flow<Map<String, Response>> = channelFlow {
        logger.info("Retrying ${requests.size} failed requests")

        val responses = ConcurrentHashMap<String, Response>(existingResponses)
        val pendingRequests = AtomicInteger(requests.size)
        isCancelled.set(false)

        try {
            val maxConcurrentRequests = llmService.getMaxConcurrentRequests()
            val semaphore = Semaphore(maxConcurrentRequests)
            val activeJobs = mutableListOf<kotlinx.coroutines.Job>()

            requests.forEach { request ->
                llmService.resetRetryCounter(request.id)
            }

            send(responses.toMap())

            for (request in requests) {
                if (isCancelled.get()) {
                    logger.info("Retry cancelled before starting request ${request.id}")
                    break
                }

                val job = launch {
                    semaphore.withPermit {
                        try {
                            if (isCancelled.get()) return@withPermit

                            logger.debug("Retrying request: ${request.id}")

                            try {
                                val response = llmService.sendRequest(request)
                                responses[request.id] = response

                                if (response.error == null) {
                                    pendingRetries.remove(request.id)
                                }

                                if (!isCancelled.get()) {
                                    send(responses.toMap())
                                }

                                if (pendingRequests.decrementAndGet() == 0) {
                                    logger.info("All retry requests completed")
                                    send(responses.toMap())
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e

                                logger.error("Error retrying request: ${request.id}", e)
                                val errorResponse = Response(
                                    id = request.id,
                                    content = "",
                                    error = "Error during retry: ${e.message}"
                                )
                                responses[request.id] = errorResponse

                                if (!isCancelled.get()) {
                                    send(responses.toMap())
                                }

                                if (pendingRequests.decrementAndGet() == 0) {
                                    logger.info("All retry requests completed (with errors)")
                                    send(responses.toMap())
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("Unexpected error during retry for request ${request.id}", e)
                            if (pendingRequests.decrementAndGet() == 0) {
                                send(responses.toMap())
                            }
                        }
                    }
                }

                activeJobs.add(job)
            }

            for (job in activeJobs) {
                if (!job.isCompleted && !job.isCancelled) {
                    job.join()
                }
            }

            logger.info("All retry jobs processed")
            send(responses.toMap())
        } catch (e: CancellationException) {
            logger.info("Retry cancelled, sending partial results")
            send(responses.toMap())
            throw e
        } catch (e: Exception) {
            logger.error("Error retrying requests", e)
            send(responses.toMap())
        }
    }.flowOn(Dispatchers.IO)

    override fun getFailedRequests(): Map<String, Request> {
        return pendingRetries.toMap()
    }

    override fun close() {
        isCancelled.set(false)
        pendingRetries.clear()
        llmService.close()
    }
}