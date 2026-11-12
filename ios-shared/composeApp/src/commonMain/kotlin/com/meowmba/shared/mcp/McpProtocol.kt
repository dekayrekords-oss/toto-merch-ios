package com.meowmba.shared.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal Model Context Protocol (MCP) surface — JSON-RPC 2.0.
 *
 * Ported from `core/mcp/McpProtocol.kt`. The wire contract, error codes and
 * response shapes are verbatim; the only change is the JSON library.
 * `org.json` is JVM/Android-only, so this uses `kotlinx.serialization`, which
 * the rest of the port already standardised on.
 *
 * One consequence worth knowing when reading the diff: a kotlinx [JsonObject]
 * is **immutable**, where `org.json.JSONObject` was mutated with `put()` and
 * returned. So every object here is built with `buildJsonObject { … }` and
 * never reassigned. The emitted bytes are the same.
 *
 * Why in-process rather than over a socket: the tools being exposed drive a
 * local WebView, which lives and dies with this app process. Putting a network
 * boundary in front of it would buy nothing but a new failure mode (and a
 * listening port). The wire format is still real MCP JSON-RPC 2.0, so the same
 * tool objects can be served over stdio/HTTP later without touching the tools.
 */
object McpProtocol {

    const val JSONRPC = "2.0"

    // JSON-RPC error codes (plus MCP's own where they overlap).
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    private val json = Json { ignoreUnknownKeys = true }

    private val emptyObject = buildJsonObject { }

    /** One exposed capability. [inputSchema] is a JSON Schema object. */
    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
    )

    /** What a tool returned. [isError] keeps a failed call from looking like text. */
    data class McpResult(
        val text: String,
        val isError: Boolean = false,
    )

    /** Implemented by anything that serves MCP tools. */
    interface Server {
        fun listTools(): List<McpTool>
        suspend fun callTool(name: String, args: JsonObject): McpResult
    }

    /**
     * Dispatch one JSON-RPC request against [server].
     *
     * Never throws — a tool that blows up must come back as a JSON-RPC error
     * response, not kill the caller (the agent treats a thrown exception as a
     * dead tool and stops offering it).
     *
     * The parse step is deliberately its own `try`: `org.json` signalled a
     * malformed request with a dedicated `JSONException` type, so the original
     * could separate "bad JSON" from "the tool threw". kotlinx throws
     * `SerializationException`/`IllegalArgumentException` for the former, but
     * an `IllegalArgumentException` can equally come from *inside* a tool — so
     * scoping the catch to parsing is what preserves the original's error
     * codes. Widening it would report a broken tool as PARSE_ERROR.
     */
    suspend fun handle(server: Server, rawRequest: String): String {
        val req = try {
            json.parseToJsonElement(rawRequest) as? JsonObject
                ?: return error(null, PARSE_ERROR, "invalid JSON: expected an object")
        } catch (e: Exception) {
            return error(null, PARSE_ERROR, "invalid JSON: ${e.message}")
        }

        val id = req["id"]
        val method = req["method"]?.jsonPrimitive?.contentOrNull.orEmpty()

        return try {
            val result: JsonElement = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("serverInfo") {
                        put("name", serverName(server))
                        put("version", "1.0")
                    }
                    putJsonObject("capabilities") { putJsonObject("tools") { } }
                }
                "tools/list" -> buildJsonObject {
                    putJsonArray("tools") {
                        server.listTools().forEach { tool ->
                            add(buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            })
                        }
                    }
                }
                "tools/call" -> {
                    val params = req["params"] as? JsonObject ?: emptyObject
                    val name = params["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val args = params["arguments"] as? JsonObject ?: emptyObject
                    val out = server.callTool(name, args)
                    buildJsonObject {
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", out.text)
                            })
                        }
                        put("isError", out.isError)
                    }
                }
                "ping" -> emptyObject
                else -> return error(id, METHOD_NOT_FOUND, "unknown method: $method")
            }
            buildJsonObject {
                put("jsonrpc", JSONRPC)
                // An absent id must serialise as JSON null, not be dropped: the
                // spec requires the key, and clients match responses on it.
                put("id", id ?: JsonNull)
                put("result", result)
            }.toString()
        } catch (e: Exception) {
            error(null, INTERNAL_ERROR, e.message ?: "internal error")
        }
    }

    private fun serverName(server: Server): String =
        server::class.simpleName ?: "mcp"

    private fun error(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", JSONRPC)
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()

    /** Builds the JSON Schema object for a tool that takes named string args. */
    fun schema(vararg required: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                required.forEach { (name, desc) ->
                    putJsonObject(name) {
                        put("type", "string")
                        put("description", desc)
                    }
                }
            }
            putJsonArray("required") { required.forEach { add(it.first) } }
        }
}
