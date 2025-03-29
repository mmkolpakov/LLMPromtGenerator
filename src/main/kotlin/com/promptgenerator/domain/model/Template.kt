package com.promptgenerator.domain.model

/**
 * Represents a template for generating prompts.
 */
data class Template(
    val id: String,
    val name: String,
    val content: String,
    val description: String = ""
)