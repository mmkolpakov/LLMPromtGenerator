package com.promptgenerator.domain.model

/**
 * Represents a response from the LLM provider.
 */
data class Response(
    val id: String,
    val content: String,
    val error: String? = null
)