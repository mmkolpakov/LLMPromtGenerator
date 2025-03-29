package com.promptgenerator.domain.model

/**
 * Represents a complete generation result with metadata.
 */
data class GenerationResult(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val templateId: String,
    val templateName: String,
    val placeholders: Map<String, Any>,
    val responses: Map<String, Response>,
    val isComplete: Boolean = false
)