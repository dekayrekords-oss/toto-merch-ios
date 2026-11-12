package com.meowmba.shared.agent

import kotlinx.coroutines.flow.Flow

/**
 * Provider-neutral generation contract (ported from
 * `core/agent/ModelProvider.kt`). Platform clients (Ollama over Ktor,
 * OpenAI-compatible SSE, on-device MediaPipe) implement this; the brains and
 * the router only ever see [ModelResponse].
 */
interface ModelProvider {
    val name: String

    /** Generates a response from the model as a stream of partial results. */
    fun generateStreaming(prompt: String): Flow<ModelResponse>

    /** Generates a complete response from the prompt. */
    suspend fun generate(prompt: String): ModelResponse
}

/**
 * A provider that can constrain decoding to a JSON schema (grammar-constrained
 * generation), so a small model returns a well-formed decision object instead of
 * prose that has to be salvaged.
 *
 * Port-only. The original tested `provider is LiteRtProvider`, naming the
 * Android engine class directly — which a shared source set cannot do, since
 * there is no `LiteRtProvider` in `commonMain`. Naming the class was the wrong
 * question anyway: what the caller needs to know is whether the provider
 * *accepts a schema*, not which engine happens to implement it. An iOS engine
 * that supports grammar constraints can adopt this without any change here.
 *
 * Callers must fall back to [ModelProvider.generate] when a provider does not
 * implement this, exactly as the original did.
 */
interface SchemaConstrainedProvider {
    suspend fun generateStructured(prompt: String, jsonSchema: String = """{"type":"object"}"""): ModelResponse
}
