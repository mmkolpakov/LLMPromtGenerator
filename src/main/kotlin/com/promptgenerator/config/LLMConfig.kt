package com.promptgenerator.config

import kotlinx.serialization.Serializable

@Serializable
data class LLMConfig(
    val defaultProvider: String = "gemini",
    val defaultSystemPrompt: String = "You are a helpful AI assistant tasked with generating responses based on the provided template. Respond only with the output based on the template and variables provided. Do not add any explanations, introductions, or additional text outside the template structure.",
    val providers: MutableMap<String, ProviderConfig> = mutableMapOf(
        "openai" to ProviderConfig(
            baseUrl = "https://api.openai.com",
            apiKey = "",
            defaultModel = "gpt-4o",
            rateLimiting = RateLimitConfig()
        ),
        "anthropic" to ProviderConfig(
            baseUrl = "https://api.anthropic.com",
            apiKey = "",
            anthropicVersion = "2023-06-01",
            defaultModel = "claude-3-sonnet-20240229",
            rateLimiting = RateLimitConfig()
        ),
        "ollama" to ProviderConfig(
            protocol = "http",
            baseUrl = "localhost",
            port = 11434,
            defaultModel = "llama2",
            rateLimiting = RateLimitConfig(
                requestsPerMinute = 300,
                maxConcurrent = 10,
                retryDelay = 500
            )
        ),
        "gemini" to ProviderConfig(
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "",
            defaultModel = "gemini-1.5-pro",
            rateLimiting = RateLimitConfig()
        )
    ),
    val requestDefaults: RequestDefaults = RequestDefaults()
)

@Serializable
data class ProviderConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val port: Int = 0,
    val protocol: String = "https",
    val anthropicVersion: String = "",
    val defaultModel: String = "",
    val rateLimiting: RateLimitConfig = RateLimitConfig()
)

@Serializable
data class RateLimitConfig(
    val requestsPerMinute: Int = 60,
    val maxConcurrent: Int = 5,
    val retryDelay: Long = 1000
)

@Serializable
data class RequestDefaults(
    val temperature: Double = 0.7,
    val maxTokens: Int = 1000,
    val topP: Double = 1.0
)