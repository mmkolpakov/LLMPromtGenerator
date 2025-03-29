package com.promptgenerator.domain.model

enum class NetworkErrorType {
    TIMEOUT,
    RATE_LIMIT,
    AUTHORIZATION,
    CONNECTION,
    UNKNOWN
}

data class NetworkError(
    val id: String,
    val message: String,
    val type: NetworkErrorType,
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: String? = null
) {
    val title: String
        get() = when(type) {
            NetworkErrorType.TIMEOUT -> "Request Timeout"
            NetworkErrorType.RATE_LIMIT -> "Rate Limit Exceeded"
            NetworkErrorType.AUTHORIZATION -> "Authorization Error"
            NetworkErrorType.CONNECTION -> "Connection Error"
            NetworkErrorType.UNKNOWN -> "Request Failed"
        }

    val description: String
        get() = when(type) {
            NetworkErrorType.TIMEOUT -> "The LLM provider took too long to respond."
            NetworkErrorType.RATE_LIMIT -> "The LLM provider's rate limit was reached. Try again in a few moments."
            NetworkErrorType.AUTHORIZATION -> "There's an issue with your API key or permissions. Check your settings and API key."
            NetworkErrorType.CONNECTION -> "Failed to establish connection with the LLM provider. Check your internet connection."
            NetworkErrorType.UNKNOWN -> "The LLM provider was unable to process this request."
        }

    val solution: String
        get() = when(type) {
            NetworkErrorType.TIMEOUT -> "Try simplifying your prompt or splitting it into smaller requests. You can also try again later when the service might be less busy."
            NetworkErrorType.RATE_LIMIT -> "Wait a few moments before sending more requests. Consider upgrading your API tier if this happens frequently."
            NetworkErrorType.AUTHORIZATION -> "Update your API key in Settings or check that your account has sufficient permissions for this operation."
            NetworkErrorType.CONNECTION -> "Check your internet connection. If the problem persists, the LLM service might be experiencing outages."
            NetworkErrorType.UNKNOWN -> "Try again or check the detailed error message for more information. If the problem persists, contact support."
        }

    val isCritical: Boolean
        get() = type == NetworkErrorType.AUTHORIZATION
}