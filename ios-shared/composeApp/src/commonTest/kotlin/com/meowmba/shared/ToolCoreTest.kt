package com.meowmba.shared

import com.meowmba.shared.agent.runtime.ToolCallParser
import com.meowmba.shared.agent.runtime.ToolCallPrompt
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.agent.runtime.HermesProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCoreTest {

    private class EchoTool : Tool {
        override val definition = ToolDefinition(
            id = "echo",
            displayName = "Echo",
            description = "Echoes text",
            inputSchema = mapOf("text" to "string"),
            riskLevel = com.meowmba.shared.agent.tools.RiskLevel.SAFE
        )
        override suspend fun execute(args: Map<String, String>): ToolResult =
            ToolResult.Success(args["text"] ?: "")
    }

    private fun registry(): ToolRegistry = ToolRegistry().apply { register(EchoTool()) }

    @Test
    fun parsesCompactContract() {
        val call = ToolCallParser.parse("""{"tool":"echo","args":{"text":"hi"}}""", registry())
        assertNotNull(call)
        assertEquals("echo", call.toolId)
        assertEquals("hi", call.args["text"])
    }

    @Test
    fun parsesHermesStyleKeys() {
        val call = ToolCallParser.parse("""{"name":"echo","arguments":{"text":"yo"}}""", registry())
        assertNotNull(call)
        assertEquals("yo", call.args["text"])
    }

    @Test
    fun parsesFencedJsonWithProse() {
        val output = "Sure! ```json\n{\"tool\":\"echo\",\"args\":{\"text\":\"a{b}\"}}\n``` done"
        val call = ToolCallParser.parse(output, registry())
        assertNotNull(call)
        assertEquals("a{b}", call.args["text"])
    }

    @Test
    fun hallucinatedToolRejected() {
        assertNull(ToolCallParser.parse("""{"tool":"does_not_exist","args":{}}""", registry()))
    }

    @Test
    fun garbageNeverCrashes() {
        assertNull(ToolCallParser.parse("hello world", registry()))
        assertNull(ToolCallParser.parse("""{"tool":{"frames":[1,2,3]}}""", registry()))
        assertNull(ToolCallParser.parse("""{"tool":null}""", registry()))
    }

    @Test
    fun promptContractContainsToolList() {
        val prompt = ToolCallPrompt.build("wiggle ears", registry().definitions())
        assertTrue("echo(text): Echoes text" in prompt)
        assertTrue(prompt.endsWith("JSON:"))
    }

    @Test
    fun hermesNativePromptEmbedsOpenAiSchemas() {
        val prompt = ToolCallPrompt.buildHermesNative("hi", registry().definitions())
        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue("\"name\":\"echo\"" in prompt || "\"name\" : \"echo\"" in prompt)
    }

    @Test
    fun openAiSchemaShape() {
        val tools = HermesProtocol.toOpenAiTools(registry().definitions())
        val root = Json.parseToJsonElement(tools.toString())
        val obj = root.toString().let { Json.parseToJsonElement(it) }
        val first = requireNotNull((obj as kotlinx.serialization.json.JsonArray).firstOrNull()).jsonObject
        assertEquals("function", first["type"]?.toString()?.trim('"'))
        val fn = first["function"]!!.jsonObject
        assertEquals("echo", fn["name"]?.toString()?.trim('"'))
    }
}
