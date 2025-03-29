package com.promptgenerator.data.source.remote

import com.promptgenerator.domain.model.Request

/**
 * Interface for LLM client implementations
 */
interface LLMClient {
    /**
     * Sends a completion request to the LLM service
     */
    suspend fun sendCompletionRequest(request: Request): String

    /**
     * Cancels any ongoing requests
     */
    fun cancelRequests() {
        // Default empty implementation
    }

    /**
     * Closes resources associated with the client
     */
    fun close()
}