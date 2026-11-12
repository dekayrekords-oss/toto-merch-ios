package com.meowmba.shared.workflows

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure preflight before an approval is offered. It cannot contact a service.
 *
 * Ported from `core/workflows/WorkflowMcpArguments.kt`. Two things changed, both
 * because the JVM types are unavailable on iOS:
 *
 * - `decode` returns a kotlinx [JsonObject] instead of `org.json.JSONObject`.
 *   The caller hands the result to the tool's `execute`, so the concrete JSON
 *   type was never part of the contract — only its shape.
 * - `java.math.BigDecimal` is gone; see [isIntegralJsonNumber].
 */
interface WorkflowArgumentValidator {
    fun validateArguments(args: Map<String, String>)
}

/** Present natural named arguments to small models. Preserve the explicit JSON
 * envelope for callers with nested data; do not guess numbers from strings when
 * the server declares a string. The server still validates its complete schema. */
object WorkflowMcpArguments {
    fun describe(schema: String): Map<String, String> {
        val properties = Json.parseToJsonElement(schema).jsonObject["properties"] as? JsonObject
            ?: return mapOf("arguments" to "JSON object string")
        return properties.mapValues { (_, value) ->
            (value as? JsonObject)?.get("type")?.toString()?.replace("\"", "") ?: "string"
        }
    }

    fun decode(schema: String, args: Map<String, String>): JsonObject {
        val root = Json.parseToJsonElement(schema).jsonObject
        val properties = root["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val supplied = if (args.keys == setOf("arguments") && "arguments" !in properties) {
            Json.parseToJsonElement(args.getValue("arguments")) as? JsonObject
                ?: error("arguments must be a JSON object")
        } else buildJsonObject {
            args.forEach { (name, raw) ->
                val spec = properties[name] as? JsonObject ?: JsonObject(emptyMap())
                val types = types(spec)
                val value: JsonElement = when {
                    raw == "null" && "null" in types -> JsonNull
                    "string" in types || types.isEmpty() -> JsonPrimitive(raw)
                    "boolean" in types -> {
                        require(raw in setOf("true", "false")) { "$name must be true or false" }
                        JsonPrimitive(raw == "true")
                    }
                    "integer" in types -> {
                        require(raw.matches(Regex("-?(0|[1-9][0-9]*)"))) { "$name must be an integer" }
                        JsonPrimitive(raw.toLongOrNull() ?: error("$name is outside the supported integer range"))
                    }
                    "number" in types -> {
                        require(raw.length <= 100) { "$name is too large" }
                        Json.parseToJsonElement(raw).also {
                            require(it is JsonPrimitive && !it.isString && it.booleanOrNull == null) {
                                "$name must be a number"
                            }
                        }
                    }
                    "object" in types || "array" in types -> Json.parseToJsonElement(raw)
                    else -> error("Unsupported argument type for $name; use the arguments JSON object")
                }
                put(name, value)
            }
        }
        validate(root, supplied, "arguments")
        return supplied
    }

    private fun types(spec: JsonObject): Set<String> = when (val type = spec["type"]) {
        is JsonPrimitive -> setOf(type.content)
        is JsonArray -> type.map { it.jsonPrimitive.content }.toSet()
        else -> emptySet()
    }

    private fun validate(spec: JsonObject, value: JsonElement, name: String, depth: Int = 0) {
        require(depth <= 12) { "Arguments are nested too deeply" }
        val types = types(spec)
        val matches = when (value) {
            JsonNull -> "null" in types
            is JsonObject -> "object" in types
            is JsonArray -> "array" in types
            is JsonPrimitive -> if (value.isString) "string" in types else when {
                value.booleanOrNull != null -> "boolean" in types
                isJsonNumber(value) -> "number" in types ||
                    "integer" in types && isIntegralJsonNumber(value.content)
                else -> false
            }
        }
        require(types.isEmpty() || matches) { "$name must match type ${types.joinToString()}" }
        (spec["enum"] as? JsonArray)?.let { require(value in it) { "$name must be one of $it" } }
        if (value is JsonObject) {
            val properties = spec["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (spec["required"] as? JsonArray)?.forEach {
                require(it.jsonPrimitive.content in value) { "Missing argument: ${it.jsonPrimitive.content}" }
            }
            value.forEach { (key, child) ->
                val childSpec = properties[key] as? JsonObject
                require(childSpec != null || spec["additionalProperties"] != JsonPrimitive(false)) {
                    "Unknown argument: $key. Allowed: ${properties.keys.joinToString()}"
                }
                (childSpec ?: spec["additionalProperties"] as? JsonObject)
                    ?.let { validate(it, child, "$name.$key", depth + 1) }
            }
        }
        if (value is JsonArray) {
            (spec["items"] as? JsonObject)?.let { item -> value.forEach { validate(item, it, "$name[]", depth + 1) } }
        }
    }
}

/**
 * Port-only. Replaces the JVM `content.toBigDecimalOrNull() != null` test, which
 * does not exist on Kotlin/Native — `BigDecimal` is a JVM type.
 *
 * This is deliberately *not* `content.toDoubleOrNull() != null`: that accepts
 * `Infinity`, `NaN` and `1e999`, none of which is a JSON number, and it would
 * make `"number"` schemas accept values a server would reject.
 *
 * The token is already known to have parsed as JSON, so this only has to answer
 * "is it a number rather than a boolean", which [booleanOrNull] settles.
 */
internal fun isJsonNumber(value: JsonPrimitive): Boolean = !value.isString && value.booleanOrNull == null

/**
 * Port-only. Replaces `BigDecimal(content).stripTrailingZeros().scale() <= 0`,
 * which asks "does this number have no fractional part?" — the test that lets a
 * schema declaring `integer` accept `5` and `5.0` while rejecting `5.5`.
 *
 * Implemented on the literal's digits rather than by converting to `Double`,
 * because the conversion would round: a value like `1e400` becomes `Infinity`
 * and `12345678901234567890123` loses its low digits, so the integrality test
 * would answer for a *different* number than the one supplied.
 *
 * A JSON number is `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. Writing the digits
 * as `intPart + fracPart` and the value as `digits × 10^shift` with
 * `shift = exponent - fracPart.length`, the number is an integer exactly when
 * every digit that `shift` discards is a zero.
 */
internal fun isIntegralJsonNumber(content: String): Boolean {
    val negative = content.startsWith('-')
    val body = if (negative) content.substring(1) else content
    if (body.isEmpty()) return false
    val exponentAt = body.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (exponentAt < 0) body else body.substring(0, exponentAt)
    val exponent = if (exponentAt < 0) 0L else body.substring(exponentAt + 1).toLongOrNull() ?: return false
    val dotAt = mantissa.indexOf('.')
    val intPart = if (dotAt < 0) mantissa else mantissa.substring(0, dotAt)
    val fracPart = if (dotAt < 0) "" else mantissa.substring(dotAt + 1)
    if (intPart.isEmpty() && fracPart.isEmpty()) return false
    val digits = intPart + fracPart
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
    // Discard `-shift` digits from the right; the value is integral iff all of
    // them are zeros. `exponent` can be huge, so guard the arithmetic.
    val discarded = fracPart.length - exponent
    if (discarded <= 0L) return true
    if (discarded >= digits.length) return digits.all { it == '0' }
    return digits.substring(digits.length - discarded.toInt()).all { it == '0' }
}
