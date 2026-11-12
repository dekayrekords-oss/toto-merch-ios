package com.meowmba.shared.agent.runtime

import com.meowmba.shared.agent.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a model's tool-routing output into a validated tool call.
 * Ported from `core/agent/runtime/ToolCallParser.kt`, unchanged in behavior.
 *
 * Small models rarely emit perfectly clean JSON, so this handles: raw JSON,
 * ```json code fences, prose before/after the object, and Hermes-style
 * <tool_call>…</tool_call> tags with {"name", "arguments"} keys as well as
 * the compact {"tool", "args"} contract.
 *
 * Validation is strict: unknown tools and unparseable output both yield null
 * — never a guess. A null result means "treat as conversation", so the model
 * can NEVER cause a tool to run by producing malformed text.
 *
 * Pure Kotlin, fully unit-testable.
 */
object ToolCallParser {

    data class ParsedToolCall(
        val toolId: String,
        val args: Map<String, String>
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse model output. Returns a validated call, or null for
     * "no tool / conversation / garbage".
     */
    fun parse(output: String, registry: ToolRegistry): ParsedToolCall? {
        val obj = extractJsonObject(output) ?: return null

        // Both key styles: {"tool", "args"} and Hermes {"name", "arguments"}.
        // .jsonPrimitive THROWS on non-primitives, so every access goes through
        // primitiveTextOrNull — garbage must yield null, never a crash.
        val toolId = obj["tool"].primitiveTextOrNull()
            ?: obj["name"].primitiveTextOrNull()
            ?: return null
        if (toolId == "null" || toolId.isBlank()) return null
        if (registry.byId(toolId) == null) return null   // hallucinated tool: reject

        val rawArgs = (obj["args"] ?: obj["arguments"]) as? JsonObject
        val args = rawArgs?.entries?.associate { (key, value) ->
            key to when (value) {
                // Structured args ride as JSON text: avatar.animate's "frames"
                // as a real array is exactly the string the tool parses.
                is JsonPrimitive -> value.content
                else -> value.toString()
            }
        } ?: emptyMap()

        return ParsedToolCall(toolId, args)
    }

    /** Content when this is a primitive, null otherwise — never throws. */
    private fun JsonElement?.primitiveTextOrNull(): String? =
        (this as? JsonPrimitive)?.content

    /**
     * Extract the first balanced JSON object from arbitrary text, respecting
     * quoted strings and escapes. Handles code fences and surrounding prose.
     */
    internal fun extractJsonObject(text: String): JsonObject? {
        var start = text.indexOf('{')
        while (start >= 0) {
            val end = findMatchingBrace(text, start)
            if (end > start) {
                val candidate = text.substring(start, end + 1)
                try {
                    val parsed = json.parseToJsonElement(candidate)
                    if (parsed is JsonObject) return parsed
                } catch (e: Exception) {
                    // not valid JSON here; keep scanning
                }
            }
            start = text.indexOf('{', start + 1)
        }
        return null
    }

    /** Index of the '}' matching the '{' at [start], or -1. String-aware. */
    private fun findMatchingBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> { /* ignore braces inside strings */ }
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
