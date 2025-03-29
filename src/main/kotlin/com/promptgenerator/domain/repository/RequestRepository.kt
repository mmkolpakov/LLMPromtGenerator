package com.promptgenerator.domain.repository

import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Response
import kotlinx.coroutines.flow.Flow

interface RequestRepository {
    fun sendRequests(
        requests: List<Request>,
        onProgress: suspend (requestId: String, responseContent: String, error: String?) -> Unit
    ): Flow<Map<String, Response>>

    suspend fun cancelRequests()

    fun retryRequest(request: Request): Flow<Response>

    fun retryFailedRequests(
        requests: List<Request>,
        existingResponses: Map<String, Response>
    ): Flow<Map<String, Response>>

    fun getFailedRequests(): Map<String, Request>

    fun close()
}