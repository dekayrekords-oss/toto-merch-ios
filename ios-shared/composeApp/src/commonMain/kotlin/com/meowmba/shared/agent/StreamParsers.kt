package com.meowmba.shared.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsers for streaming model APIs. No network, no platform — every line
 * format is unit-testable on the JVM. Ported from
 * `core/agent/StreamParsers.kt` unchanged in behavior.
 *
 * Ollama streams newline-delimited JSON: {"response":"…","done":false}
 * OpenAI-compatible APIs stream Server-Sent Events: data: {"choices":[…]} …
 */
object StreamParsers {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse one NDJSON line from Ollama's /api/generate stream.
     * Returns null for blank or malformed lines (keepalive noise).
     */
    fun parseOllamaLine(line: String): ModelResponse? {
        if (line.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(line).jsonObject
            val partial = obj["response"]?.jsonPrimitive?.content.orEmpty()
            val done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false
            when {
                done -> ModelResponse.Complete(partial)
                partial.isNotEmpty() -> ModelResponse.Partial(partial)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse one SSE line from an OpenAI-compatible /v1/chat/completions stream.
     * Returns null for non-data lines (comments, blanks, malformed chunks).
     */
    fun parseOpenAISseLine(line: String): ModelResponse? {
        if (!line.startsWith("data: ")) return null
        val data = line.removePrefix("data: ").trim()
        if (data == "[DONE]") return ModelResponse.Complete("")
        return try {
            val obj = json.parseToJsonElement(data).jsonObject
            // Mid-stream error (OpenRouter free models return HTTP 200 then
            // push {"error":{…}} when rate-limited). Surfacing it as an Error —
            // not null — is what lets the router fall back to the next route.
            obj["error"]?.let { err ->
                return ModelResponse.Error(extractErrorText(err) ?: "stream error")
            }
            val content = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content.orEmpty()
            if (content.isNotEmpty()) ModelResponse.Partial(content) else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Turn a non-2xx HTTP body into a human-readable message.
     *
     * OpenAI-compatible APIs (incl. OpenRouter) answer errors with
     * `{"error":{"message":"…","code":…}}`; dumping the raw JSON is cryptic.
     * Falls back to the trimmed body when it is not that shape.
     */
    fun extractApiErrorMessage(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "no detail"
        return try {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val err = obj["error"]
            extractErrorText(err) ?: trimmed.take(200)
        } catch (e: Exception) {
            trimmed.take(200)
        }
    }

    /** Pull the message out of an `error` JSON element (object or string). */
    private fun extractErrorText(err: JsonElement?): String? = try {
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.content
            is JsonPrimitive -> err.content
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    /** Count models in an Ollama /api/tags response: {"models":[…]}. */
    fun countOllamaModels(body: String): Int = countArrayEntries(body, "models")

    /** Count models in an OpenAI /v1/models response: {"data":[…]}. */
    fun countOpenAIModels(body: String): Int = countArrayEntries(body, "data")

    private fun countArrayEntries(body: String, key: String): Int = try {
        json.parseToJsonElement(body).jsonObject[key]?.jsonArray?.size ?: 0
    } catch (e: Exception) {
        0
    }
}
