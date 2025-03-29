package com.promptgenerator.domain.model

/**
 * Represents a request generated from a template.
 */
data class Request(
    val id: String,
    val content: String,
    val systemInstruction: String? = null
)