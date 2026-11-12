package com.meowmba.shared.mcp

import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolEnvironment
import com.meowmba.shared.agent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Publishes an MCP server's tools into the agent's `ToolRegistry`.
 *
 * The model should not care whether a tool is implemented locally or served
 * over MCP, so each MCP tool is wrapped in an ordinary [Tool] and registered
 * like any other. Calls go through [McpProtocol.handle] — real JSON-RPC — so
 * the same server could be moved out of process later without touching the
 * agent or these adapters.
 *
 * Arguments arrive as `Map<String, String>` (how every other tool in the app
 * is called) and are converted to a JSON object for the MCP call.
 *
 * Ported from `core/mcp/McpToolBridge.kt`; `org.json` → `kotlinx.serialization`.
 */
class McpToolBridge(
    private val server: McpProtocol.Server,
    /** Prefix so two servers cannot collide on a tool id. */
    private val idPrefix: String = "",
    private val riskLevel: RiskLevel = RiskLevel.SAFE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One MCP tool, exposed as an app [Tool]. */
    private inner class Adapted(private val tool: McpProtocol.McpTool) : Tool {
        override val definition = ToolDefinition(
            id = idPrefix + tool.name,
            displayName = tool.name.substringAfterLast('.'),
            description = tool.description,
            inputSchema = schemaToMap(tool.inputSchema),
            riskLevel = riskLevel,
            environments = setOf(ToolEnvironment.PHONE),
            confirmationPolicy = ConfirmationPolicy.NONE,
            // Loading a page can legitimately take a while.
            timeoutMs = 60_000,
        )

        override suspend fun execute(args: Map<String, String>): ToolResult {
            val params = buildJsonObject {
                put("name", tool.name)
                putJsonObject("arguments") { args.forEach { (k, v) -> put(k, v) } }
            }
            val request = buildJsonObject {
                put("jsonrpc", McpProtocol.JSONRPC)
                put("id", 1)
                put("method", "tools/call")
                put("params", params)
            }.toString()
            val response = McpProtocol.handle(server, request)
            return try {
                val body = json.parseToJsonElement(response) as? JsonObject
                    ?: return ToolResult.Failure("bad MCP response: not an object")
                val error = body["error"] as? JsonObject
                if (error != null) {
                    return ToolResult.Failure(
                        error["message"]?.jsonPrimitive?.contentOrNull ?: "MCP error",
                    )
                }
                val result = body["result"] as? JsonObject
                    ?: return ToolResult.Failure("empty MCP result")
                val text = (result["content"] as? JsonArray)
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: ""
                // Read `isError` through a JsonPrimitive cast rather than
                // `.jsonPrimitive`, which throws on an object or array. The
                // original's optBoolean("isError", false) tolerated any shape,
                // and a malformed field should not turn a good result into a
                // "bad MCP response".
                val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                if (isError) ToolResult.Failure(text) else ToolResult.Success(text)
            } catch (e: Exception) {
                ToolResult.Failure("bad MCP response: ${e.message}")
            }
        }
    }

    fun tools(): List<Tool> = server.listTools().map { Adapted(it) }

    private fun schemaToMap(schema: JsonObject): Map<String, String> {
        val props = schema["properties"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        props.forEach { (key, value) ->
            out[key] = (value as? JsonObject)
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: "string"
        }
        return out
    }
}
