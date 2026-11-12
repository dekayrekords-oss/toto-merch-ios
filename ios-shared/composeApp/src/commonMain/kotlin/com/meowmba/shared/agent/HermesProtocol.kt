package com.meowmba.shared.agent.runtime

import com.meowmba.shared.agent.tools.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI function-calling schema conversion for Hermes-style endpoints.
 * Ported from the pure protocol half of `core/agent/runtime/HermesAgentBrain.kt`
 * (the network client stays platform-specific; this is the shared contract).
 */
object HermesProtocol {

    /** Convert registry definitions to OpenAI function-calling format. */
    fun toOpenAiTools(definitions: List<ToolDefinition>): JsonArray = buildJsonArray {
        definitions.forEach { tool ->
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.id)
                    put("description", tool.description)
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            tool.inputSchema.forEach { (argName, argType) ->
                                putJsonObject(argName) {
                                    put("type", if (argType == "int") "integer" else "string")
                                }
                            }
                        }
                        putJsonArray("required") {
                            tool.inputSchema.filterValues { !it.endsWith('?') }.keys.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            })
        }
    }
}
