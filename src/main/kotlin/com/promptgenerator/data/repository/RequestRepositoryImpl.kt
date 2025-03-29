package com.promptgenerator.data.repository

import com.promptgenerator.data.source.remote.LLMService
import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.repository.RequestRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RequestRepositoryImpl(
    private val llmService: LLMService
) : RequestRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val isCancelled = atomic(false)
    private val responsesMutex = Mutex()
    private val responsesFlow = MutableStateFlow<Map<String, Response>>(emptyMap())
    private val pendingRetries = ConcurrentHashMap<String, Request>()
    private val activeRequestIds = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val completedCountMutex = Mutex()

    override fun sendRequests(
        requests: List<Request>,
        onProgress: suspend (requestId: String, responseContent: String, error: String?) -> Unit
    ): Flow<Map<String, Response>> = channelFlow {
        if (requests.isEmpty()) {
            send(emptyMap())
            return@channelFlow
        }

        logger.info("Sending ${requests.size} requests")
        isCancelled.value = false

        responsesMutex.withLock {
            responsesFlow.value = emptyMap()
        }
        send(responsesFlow.value)

        supervisorScope {
            val maxConcurrentRequests = llmService.getMaxConcurrentRequests()
            val semaphore = Semaphore(maxConcurrentRequests)
            val completedCount = AtomicInteger(0)
            val totalCount = requests.size

            try {
                val requestJobs = requests.map { request ->
                    launch {
                        semaphore.withPermit {
                            if (!currentCoroutineContext().isActive || isCancelled.value) {
                                return@withPermit
                            }

                            activeRequestIds.add(request.id)

                            try {
                                logger.debug("Sending request: ${request.id}")
                                val response = llmService.sendRequest(request)

                                responsesMutex.withLock {
                                    responsesFlow.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = response
                                        updated
                                    }
                                }

                                if (response.error != null) {
                                    pendingRetries[request.id] = request
                                }

                                onProgress(request.id, response.content, response.error)

                                completedCountMutex.withLock {
                                    if (currentCoroutineContext().isActive && !isCancelled.value) {
                                        send(responsesFlow.value)
                                    }

                                    val current = completedCount.incrementAndGet()
                                    if (current >= totalCount) {
                                        logger.info("All requests completed")
                                        send(responsesFlow.value)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!currentCoroutineContext().isActive || isCancelled.value) {
                                    return@withPermit
                                }

                                logger.error("Error sending request: ${request.id}", e)

                                val errorResponse = Response(
                                    id = request.id,
                                    content = "",
                                    error = "Error: ${e.message}"
                                )

                                responsesMutex.withLock {
                                    responsesFlow.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = errorResponse
                                        updated
                                    }
                                }

                                pendingRetries[request.id] = request
                                onProgress(request.id, "", e.message)

                                completedCountMutex.withLock {
                                    if (currentCoroutineContext().isActive && !isCancelled.value) {
                                        send(responsesFlow.value)
                                    }

                                    val current = completedCount.incrementAndGet()
                                    if (current >= totalCount) {
                                        logger.info("All requests completed (with errors)")
                                        send(responsesFlow.value)
                                    }
                                }
                            } finally {
                                activeRequestIds.remove(request.id)
                            }
                        }
                    }
                }

                requestJobs.forEach { it.join() }
            } catch (e: CancellationException) {
                logger.info("Request sending was cancelled, sending partial results")
                send(responsesFlow.value)
                throw e
            } catch (e: Exception) {
                logger.error("Error sending requests", e)

                responsesMutex.withLock {
                    requests
                        .filter { !responsesFlow.value.containsKey(it.id) }
                        .forEach { request ->
                            val errorResponse = Response(
                                id = request.id,
                                content = "",
                                error = "Error: ${e.message}"
                            )

                            responsesFlow.update { current ->
                                val updated = current.toMutableMap()
                                updated[request.id] = errorResponse
                                updated
                            }

                            pendingRetries[request.id] = request
                        }
                }

                send(responsesFlow.value)
            }
        }
    }
        .onCompletion { cause ->
            logger.info("Flow completing with cause: $cause")
            activeRequestIds.clear()
        }
        .flowOn(Dispatchers.IO)

    override suspend fun cancelRequests() {
        logger.info("Cancelling requests")
        isCancelled.value = true
        llmService.cancelRequests()
    }

    override fun retryRequest(request: Request): Flow<Response> = flow {
        logger.info("Retrying request: ${request.id}")
        isCancelled.value = false
        llmService.resetRetryCounter(request.id)

        activeRequestIds.add(request.id)

        try {
            val response = withTimeoutOrNull(300_000) {
                llmService.sendRequest(request)
            } ?: Response(
                id = request.id,
                content = "",
                error = "Request timed out after 5 minutes"
            )

            logger.info("Retry completed for request: ${request.id}")
            emit(response)

            if (response.error == null) {
                pendingRetries.remove(request.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error retrying request: ${request.id}", e)
            val errorResponse = Response(
                id = request.id,
                content = "",
                error = "Error during retry: ${e.message}"
            )
            emit(errorResponse)
        } finally {
            activeRequestIds.remove(request.id)
        }
    }.flowOn(Dispatchers.IO)

    override fun retryFailedRequests(requests: List<Request>, existingResponses: Map<String, Response>): Flow<Map<String, Response>> = channelFlow {
        logger.info("Retrying ${requests.size} failed requests")
        if (requests.isEmpty()) {
            send(existingResponses)
            return@channelFlow
        }

        isCancelled.value = false
        val responses = MutableStateFlow(existingResponses)

        responsesMutex.withLock {
            responsesFlow.value = existingResponses
        }

        send(responses.value)

        supervisorScope {
            val maxConcurrentRequests = llmService.getMaxConcurrentRequests()
            val semaphore = Semaphore(maxConcurrentRequests)
            val completedCount = AtomicInteger(0)
            val totalCount = requests.size

            try {
                requests.forEach { request ->
                    llmService.resetRetryCounter(request.id)
                }

                val retryJobs = requests.map { request ->
                    launch {
                        semaphore.withPermit {
                            if (!currentCoroutineContext().isActive || isCancelled.value) {
                                return@withPermit
                            }

                            activeRequestIds.add(request.id)

                            try {
                                logger.debug("Retrying request: ${request.id}")
                                val response = llmService.sendRequest(request)

                                responsesMutex.withLock {
                                    responses.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = response
                                        updated
                                    }

                                    responsesFlow.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = response
                                        updated
                                    }
                                }

                                if (response.error == null) {
                                    pendingRetries.remove(request.id)
                                }

                                completedCountMutex.withLock {
                                    if (currentCoroutineContext().isActive && !isCancelled.value) {
                                        send(responses.value)
                                    }

                                    val current = completedCount.incrementAndGet()
                                    if (current >= totalCount) {
                                        logger.info("All retry requests completed")
                                        send(responses.value)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!currentCoroutineContext().isActive || isCancelled.value) {
                                    return@withPermit
                                }

                                logger.error("Error retrying request: ${request.id}", e)

                                val errorResponse = Response(
                                    id = request.id,
                                    content = "",
                                    error = "Error during retry: ${e.message}"
                                )

                                responsesMutex.withLock {
                                    responses.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = errorResponse
                                        updated
                                    }

                                    responsesFlow.update { current ->
                                        val updated = current.toMutableMap()
                                        updated[request.id] = errorResponse
                                        updated
                                    }
                                }

                                completedCountMutex.withLock {
                                    if (currentCoroutineContext().isActive && !isCancelled.value) {
                                        send(responses.value)
                                    }

                                    val current = completedCount.incrementAndGet()
                                    if (current >= totalCount) {
                                        logger.info("All retry requests completed (with errors)")
                                        send(responses.value)
                                    }
                                }
                            } finally {
                                activeRequestIds.remove(request.id)
                            }
                        }
                    }
                }

                retryJobs.forEach { it.join() }
            } catch (e: CancellationException) {
                logger.info("Retry cancelled, sending partial results")
                send(responses.value)
                throw e
            } catch (e: Exception) {
                logger.error("Error retrying requests", e)
                send(responses.value)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun getFailedRequests(): Map<String, Request> {
        return pendingRetries.toMap()
    }

    override fun close() {
        isCancelled.value = false
        pendingRetries.clear()
        llmService.close()
        activeRequestIds.clear()
        scope.cancel()
    }
}