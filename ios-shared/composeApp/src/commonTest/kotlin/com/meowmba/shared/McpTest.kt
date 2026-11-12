package com.meowmba.shared

import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.ToolEnvironment
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.mcp.McpProtocol
import com.meowmba.shared.mcp.McpToolBridge
import com.meowmba.shared.mcp.PhoneMcpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `core/mcp` had **no direct test in the original** — the only MCP suites were
 * `McpHttpServerTest` and `McpHttpClientTest`, which exercise the socket
 * transports and only reach the JSON-RPC layer through a live port. So the
 * dispatch contract was never asserted on its own, and the assertions that did
 * exist were about `PhoneMcpServer`'s content rather than the protocol.
 *
 * Those content assertions are ported here (device info, the tool list, the
 * unknown-tool result) and the rest pin the guarantees that `McpProtocol`'s
 * KDoc states but nothing checked — above all that [McpProtocol.handle] never
 * throws, because the agent treats a thrown exception as a dead tool and stops
 * offering it.
 */
class McpTest {

    private companion object {
        /** A stand-in tool so a `FakeServer` with no explicit list still serves one. */
        val DEFAULT_TOOL = McpProtocol.McpTool(
            name = "device.battery",
            description = "Battery percent",
            inputSchema = McpProtocol.schema(),
        )
    }

    // -- test doubles -----------------------------------------------------

    private class FakeServer(
        private val tools: List<McpProtocol.McpTool> = listOf(DEFAULT_TOOL),
        private val reply: (String, JsonObject) -> McpProtocol.McpResult = { _, _ ->
            McpProtocol.McpResult("ok")
        },
        private val explode: Boolean = false,
    ) : McpProtocol.Server {
        override fun listTools(): List<McpProtocol.McpTool> = tools

        override suspend fun callTool(name: String, args: JsonObject): McpProtocol.McpResult {
            if (explode) throw IllegalStateException("tool exploded")
            return reply(name, args)
        }
    }

    private fun phoneServer(
        audit: List<String> = (1..200).map { "entry $it" },
        memories: List<String> = listOf("user likes dark roast coffee"),
        tools: List<String> = listOf("phone.battery", "memory.save"),
    ) = PhoneMcpServer(
        appName = "Meowmba",
        appVersion = "1.0",
        deviceModel = "Google Pixel 9",
        androidVersion = "15",
        sdkInt = "35",
        batteryPercent = { 72 },
        board = "komodo",
        language = "en",
        asrBackend = { "Whisper (on-device)" },
        asrUsable = { true },
        registeredTools = { tools },
        memoryRecall = { memories },
        auditRecent = { audit },
    )

    private suspend fun call(server: McpProtocol.Server, request: JsonObject): JsonObject =
        Json.parseToJsonElement(McpProtocol.handle(server, request.toString())).jsonObject

    private suspend fun callRaw(server: McpProtocol.Server, request: String): JsonObject =
        Json.parseToJsonElement(McpProtocol.handle(server, request)).jsonObject

    private suspend fun callTool(
        server: McpProtocol.Server,
        name: String,
        args: JsonObject = buildJsonObject { },
    ): JsonObject = call(
        server,
        buildJsonObject {
            put("jsonrpc", McpProtocol.JSONRPC)
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", name)
                put("arguments", args)
            }
        },
    )

    private fun resultOf(response: JsonObject): JsonObject =
        assertNotNull(response["result"] as? JsonObject, "response had no result: $response")

    private fun textOf(response: JsonObject): String {
        val content = resultOf(response)["content"] as? JsonArray
        assertNotNull(content, "result had no content array: $response")
        return content[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    private fun errorOf(response: JsonObject): JsonObject =
        assertNotNull(response["error"] as? JsonObject, "response had no error: $response")

    // -- the dispatch contract --------------------------------------------

    @Test
    fun initializeAnnouncesProtocolAndServerIdentity() = runTest {
        val res = call(
            FakeServer(),
            buildJsonObject {
                put("jsonrpc", McpProtocol.JSONRPC)
                put("id", 1)
                put("method", "initialize")
            },
        )
        assertEquals(McpProtocol.JSONRPC, res["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), res["id"])
        val result = resultOf(res)
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
        val info = result["serverInfo"] as JsonObject
        assertEquals("FakeServer", info["name"]!!.jsonPrimitive.content)
        assertEquals("1.0", info["version"]!!.jsonPrimitive.content)
        // capabilities.tools must be present (an empty object, not absent):
        // clients gate their tools UI on the key existing.
        assertNotNull((result["capabilities"] as JsonObject)["tools"] as? JsonObject)
    }

    @Test
    fun toolsListCarriesNameDescriptionAndSchema() = runTest {
        val server = FakeServer(
            tools = listOf(
                McpProtocol.McpTool("device.battery", "Battery percent", McpProtocol.schema()),
                McpProtocol.McpTool(
                    "audit.recent",
                    "Recent entries",
                    McpProtocol.schema("limit" to "how many"),
                ),
            ),
        )
        val tools = resultOf(call(server, request("tools/list")))["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("device.battery", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Battery percent", tools[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertTrue(tools[0].jsonObject["inputSchema"] is JsonObject)
        assertEquals("audit.recent", tools[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsCallWrapsTheReplyAsTextContent() = runTest {
        val server = FakeServer(reply = { _, _ -> McpProtocol.McpResult("battery 72%") })
        val res = callTool(server, "device.battery")
        assertEquals("text", (resultOf(res)["content"]!!.jsonArray[0].jsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("battery 72%", textOf(res))
        assertEquals("false", resultOf(res)["isError"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsCallPassesArgumentsThroughToTheServer() = runTest {
        var seen: JsonObject? = null
        val server = FakeServer(reply = { _, args ->
            seen = args
            McpProtocol.McpResult("ok")
        })
        callTool(server, "audit.recent", buildJsonObject { put("limit", "5") })
        assertEquals("5", seen!!["limit"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsCallWithNoArgumentsStillCallsTheTool() = runTest {
        var called = false
        val server = FakeServer(reply = { _, _ ->
            called = true
            McpProtocol.McpResult("ok")
        })
        // params present, `arguments` absent entirely.
        call(
            server,
            buildJsonObject {
                put("jsonrpc", McpProtocol.JSONRPC)
                put("id", 7)
                put("method", "tools/call")
                putJsonObject("params") { put("name", "device.info") }
            },
        )
        assertTrue(called, "an absent arguments object must not skip the call")
    }

    @Test
    fun toolsCallPropagatesTheIsErrorFlag() = runTest {
        val server = FakeServer(reply = { _, _ -> McpProtocol.McpResult("went wrong", isError = true) })
        val res = callTool(server, "device.info")
        assertEquals("true", resultOf(res)["isError"]!!.jsonPrimitive.content)
        assertEquals("went wrong", textOf(res))
    }

    @Test
    fun pingReturnsAnEmptyResult() = runTest {
        val res = call(FakeServer(), request("ping"))
        assertTrue(res.containsKey("result"))
        assertEquals(0, resultOf(res).size, "ping's result should be an empty object")
    }

    @Test
    fun unknownMethodIsAMethodNotFoundErrorThatEchoesTheId() = runTest {
        val res = call(FakeServer(), request("tools/delete", id = 42))
        assertEquals(JsonPrimitive(42), res["id"])
        assertEquals(McpProtocol.METHOD_NOT_FOUND, errorOf(res)["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(errorOf(res)["message"]!!.jsonPrimitive.content.contains("tools/delete"))
    }

    // -- the "never throws" guarantee --------------------------------------

    @Test
    fun aToolThatThrowsBecomesAnInternalErrorResponseNotAThrow() = runTest {
        // The headline guarantee: the agent treats a thrown exception as a dead
        // tool and stops offering it, so a broken tool must come back as JSON-RPC.
        val res = callTool(FakeServer(explode = true), "device.info")
        assertEquals(McpProtocol.INTERNAL_ERROR, errorOf(res)["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(errorOf(res)["message"]!!.jsonPrimitive.content.contains("tool exploded"))
    }

    @Test
    fun malformedJsonIsAParseErrorWithANullId() = runTest {
        val res = callRaw(FakeServer(), "{ this is not json")
        assertEquals(JsonNull, res["id"])
        assertEquals(McpProtocol.PARSE_ERROR, errorOf(res)["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aValidJsonNonObjectIsAParseErrorNotACrash() = runTest {
        // "[1,2]" is valid JSON but not a request. org.json rejected it in the
        // constructor (JSONException), so it must stay a PARSE_ERROR here rather
        // than becoming an INTERNAL_ERROR.
        val res = callRaw(FakeServer(), "[1,2]")
        assertEquals(McpProtocol.PARSE_ERROR, errorOf(res)["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun anAbsentIdSerialisesAsJsonNullRatherThanBeingDropped() = runTest {
        // The spec requires the key, and clients match responses on it.
        val res = call(
            FakeServer(),
            buildJsonObject {
                put("jsonrpc", McpProtocol.JSONRPC)
                put("method", "ping")
            },
        )
        assertTrue(res.containsKey("id"), "id key must be present even when absent from the request")
        assertEquals(JsonNull, res["id"])
    }

    @Test
    fun stringIdsRoundTripUnchanged() = runTest {
        val res = call(FakeServer(), request("ping", id = "req-abc"))
        assertEquals(JsonPrimitive("req-abc"), res["id"])
    }

    @Test
    fun aToolThrowingDuringToolsListAlsoBecomesAnError() = runTest {
        val server = object : McpProtocol.Server {
            override fun listTools(): List<McpProtocol.McpTool> = error("listTools exploded")
            override suspend fun callTool(name: String, args: JsonObject) = McpProtocol.McpResult("")
        }
        val res = call(server, request("tools/list"))
        assertEquals(McpProtocol.INTERNAL_ERROR, errorOf(res)["code"]!!.jsonPrimitive.content.toInt())
    }

    // -- schema() ---------------------------------------------------------

    @Test
    fun schemaDescribesRequiredStringArguments() = runTest {
        val schema = McpProtocol.schema("limit" to "how many entries")
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals("string", props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("how many entries", props["limit"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(listOf("limit"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun schemaWithNoArgumentsIsAnEmptyObjectSchema() = runTest {
        val schema = McpProtocol.schema()
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(0, schema["properties"]!!.jsonObject.size)
        assertEquals(0, schema["required"]!!.jsonArray.size)
    }

    // -- PhoneMcpServer ---------------------------------------------------

    @Test
    fun theHostedSurfaceIsExactlyTheSixReadOnlyQueries() = runTest {
        // The endpoint is reachable from any host on the same Wi-Fi, so the
        // blast radius has to be zero. Pinning the exact set means adding an
        // actionable tool is a deliberate act that has to update this test.
        val names = phoneServer().listTools().map { it.name }
        assertEquals(
            listOf(
                "device.info",
                "device.battery",
                "asr.status",
                "tools.available",
                "memory.recall",
                "audit.recent",
            ),
            names,
        )
    }

    @Test
    fun deviceInfoBatteryAndAsrAreServed() = runTest {
        val server = phoneServer()
        val info = textOf(callTool(server, "device.info"))
        assertTrue(info.contains("Pixel 9"), info)
        assertTrue(info.contains("Meowmba 1.0"), info)
        assertTrue(info.contains("board komodo"), info)
        assertTrue(info.contains("sdk 35"), info)

        assertEquals("battery 72%", textOf(callTool(server, "device.battery")))
        assertEquals("backend: Whisper (on-device)\nusable now: yes", textOf(callTool(server, "asr.status")))
    }

    @Test
    fun toolsAvailableAndMemoryRecallReportEmptyRatherThanBlank() = runTest {
        assertEquals(
            "tools: phone.battery, memory.save",
            textOf(callTool(phoneServer(), "tools.available")),
        )
        assertEquals(
            "no tools registered",
            textOf(callTool(phoneServer(tools = emptyList()), "tools.available")),
        )
        assertEquals(
            "user likes dark roast coffee",
            textOf(callTool(phoneServer(), "memory.recall")),
        )
        assertEquals(
            "no memories",
            textOf(callTool(phoneServer(memories = emptyList()), "memory.recall")),
        )
    }

    @Test
    fun auditRecentDefaultsToTenAndClampsTheLimit() = runTest {
        val server = phoneServer()
        assertEquals(10, textOf(callTool(server, "audit.recent")).split("\n").size)
        assertEquals(1, textOf(callTool(server, "audit.recent", buildJsonObject { put("limit", 0) }))
            .split("\n").size)
        assertEquals(100, textOf(callTool(server, "audit.recent", buildJsonObject { put("limit", 500) }))
            .split("\n").size)
        // org.json's optInt coerced a numeric string; intOrNull does too.
        assertEquals(7, textOf(callTool(server, "audit.recent", buildJsonObject { put("limit", "7") }))
            .split("\n").size)
    }

    @Test
    fun anUnknownToolIsAWellFormedErrorResultNotAThrownError() = runTest {
        // A remote caller cannot conjure a tool that does not exist.
        val res = callTool(phoneServer(), "shell.exec")
        assertEquals("true", resultOf(res)["isError"]!!.jsonPrimitive.content)
        assertEquals("no such tool: shell.exec", textOf(res))
    }

    // -- McpToolBridge ----------------------------------------------------

    @Test
    fun bridgeAdaptsMcpToolsIntoOrdinaryRegistryTools() = runTest {
        val server = FakeServer(
            tools = listOf(
                McpProtocol.McpTool(
                    "device.battery",
                    "Battery percent",
                    McpProtocol.schema("samples" to "how many"),
                ),
            ),
        )
        val tools = McpToolBridge(server, idPrefix = "lan:", riskLevel = RiskLevel.MODERATE).tools()
        assertEquals(1, tools.size)
        val def = tools[0].definition
        assertEquals("lan:device.battery", def.id)
        assertEquals("battery", def.displayName, "displayName is the segment after the last dot")
        assertEquals("Battery percent", def.description)
        assertEquals(mapOf("samples" to "string"), def.inputSchema)
        assertEquals(RiskLevel.MODERATE, def.riskLevel)
        assertEquals(setOf(ToolEnvironment.PHONE), def.environments)
        assertEquals(ConfirmationPolicy.NONE, def.confirmationPolicy)
        assertEquals(60_000, def.timeoutMs)
    }

    @Test
    fun bridgeExecuteReturnsTheServerTextOnSuccess() = runTest {
        val server = FakeServer(reply = { name, _ -> McpProtocol.McpResult("ran $name") })
        val tool = McpToolBridge(server).tools().single()
        assertEquals(ToolResult.Success("ran device.battery"), tool.execute(emptyMap()))
    }

    @Test
    fun bridgeExecuteForwardsArgumentsAsStrings() = runTest {
        var seen: JsonObject? = null
        val server = FakeServer(reply = { _, args ->
            seen = args
            McpProtocol.McpResult("ok")
        })
        val tool = McpToolBridge(server).tools().single()
        tool.execute(mapOf("limit" to "5"))
        assertEquals("5", seen!!["limit"]!!.jsonPrimitive.content)
    }

    @Test
    fun bridgeExecuteTurnsIsErrorIntoAFailure() = runTest {
        val server = FakeServer(reply = { _, _ -> McpProtocol.McpResult("nope", isError = true) })
        val tool = McpToolBridge(server).tools().single()
        assertEquals(ToolResult.Failure("nope"), tool.execute(emptyMap()))
    }

    @Test
    fun bridgeExecuteTurnsAJsonRpcErrorIntoAFailure() = runTest {
        // The tool throws, so handle() emits an error object -- the bridge must
        // surface that as a Failure rather than an empty Success.
        val tool = McpToolBridge(FakeServer(explode = true)).tools().single()
        val result = tool.execute(emptyMap())
        assertTrue(result is ToolResult.Failure, "expected Failure, got $result")
        assertTrue(result.error.contains("tool exploded"))
    }

    private fun request(method: String, id: Int = 1): JsonObject = buildJsonObject {
        put("jsonrpc", McpProtocol.JSONRPC)
        put("id", id)
        put("method", method)
    }

    private fun request(method: String, id: String): JsonObject = buildJsonObject {
        put("jsonrpc", McpProtocol.JSONRPC)
        put("id", id)
        put("method", method)
    }
}
