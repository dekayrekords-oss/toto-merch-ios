package com.meowmba.shared.agent

/** Streaming model response segments (ported from `core/agent/ModelProvider.kt`). */
sealed class ModelResponse {
    data class Partial(val text: String) : ModelResponse()
    data class Complete(val text: String) : ModelResponse()
    data class Error(val message: String) : ModelResponse()
}
