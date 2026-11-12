package com.meowmba.shared.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The read-only MCP tool surface the phone hosts for an external client such
 * as Android Studio (which connects over LAN as an MCP host).
 *
 * Everything here is a QUERY. There is deliberately no shell, no file write,
 * no device control and nothing destructive — the endpoint is reachable from
 * any host on the same Wi-Fi, so the blast radius has to be zero. If a future
 * build wants an actionable tool it should be gated, not just added.
 *
 * Ported from `core/mcp/PhoneMcpServer.kt`. It was already free of Android
 * imports — every device fact arrives through an injected lambda — so this is
 * a JSON-library translation (`org.json` → `kotlinx.serialization`) and nothing
 * more. That shape is what makes the whole surface testable without a device,
 * and it is worth preserving on the iOS side: the platform supplies the
 * lambdas, this class stays pure.
 */
class PhoneMcpServer(
    private val appName: String,
    private val appVersion: String,
    private val deviceModel: String,
    private val androidVersion: String,
    private val sdkInt: String,
    private val batteryPercent: () -> Int,
    private val board: String,
    private val language: String,
    private val asrBackend: () -> String,
    private val asrUsable: () -> Boolean,
    private val registeredTools: () -> List<String>,
    private val memoryRecall: suspend () -> List<String>,
    private val auditRecent: suspend () -> List<String>,
) : McpProtocol.Server {

    override fun listTools(): List<McpProtocol.McpTool> = listOf(
        McpProtocol.McpTool(
            name = "device.info",
            description = "Read-only identity and build info about this Android phone",
            inputSchema = McpProtocol.schema(),
        ),
        McpProtocol.McpTool(
            name = "device.battery",
            description = "Current battery percentage (0..100)",
            inputSchema = McpProtocol.schema(),
        ),
        McpProtocol.McpTool(
            name = "asr.status",
            description = "Which speech-to-text backend is selected and whether it is usable now",
            inputSchema = McpProtocol.schema(),
        ),
        McpProtocol.McpTool(
            name = "tools.available",
            description = "Names of the agent tools currently registered on the companion",
            inputSchema = McpProtocol.schema(),
        ),
        McpProtocol.McpTool(
            name = "memory.recall",
            description = "Most recent remembered facts, newest first",
            inputSchema = McpProtocol.schema(),
        ),
        McpProtocol.McpTool(
            name = "audit.recent",
            description = "Most recent companion task-log entries (submit/start/complete/fail)",
            inputSchema = McpProtocol.schema("limit" to "how many entries to return (default 10)"),
        ),
    )

    override suspend fun callTool(name: String, args: JsonObject): McpProtocol.McpResult =
        when (name) {
            "device.info" -> McpProtocol.McpResult(
                "app: $appName $appVersion\n" +
                    "device: $deviceModel (board $board)\n" +
                    "android: $androidVersion (sdk $sdkInt)\n" +
                    "language: $language",
            )
            "device.battery" -> McpProtocol.McpResult("battery ${batteryPercent()}%")
            "asr.status" -> McpProtocol.McpResult(
                "backend: ${asrBackend()}\nusable now: ${if (asrUsable()) "yes" else "no"}",
            )
            "tools.available" -> {
                val tools = registeredTools()
                McpProtocol.McpResult(
                    if (tools.isEmpty()) "no tools registered" else "tools: ${tools.joinToString(", ")}",
                )
            }
            "memory.recall" -> {
                val memories = memoryRecall()
                McpProtocol.McpResult(
                    if (memories.isEmpty()) "no memories" else memories.joinToString("\n"),
                )
            }
            "audit.recent" -> {
                // `org.json`'s optInt("limit", 10) also coerced a numeric *string*,
                // and kotlinx's intOrNull does the same via the primitive's content.
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 100)
                val entries = auditRecent().take(limit)
                McpProtocol.McpResult(
                    if (entries.isEmpty()) "no audit entries" else entries.joinToString("\n"),
                )
            }
            else -> McpProtocol.McpResult("no such tool: $name", isError = true)
        }
}
