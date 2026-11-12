package com.meowmba.shared

import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.RemoteModelProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RemoteModelProvider wire behavior against ktor-client-mock. */
class RemoteModelProviderTest {

    private fun client(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun partials(events: List<ModelResponse>) =
        events.filterIsInstance<ModelResponse.Partial>().map { it.text }

    @Test
    fun ollamaStreamsNdjson() = runTest {
        val engine = MockEngine {
            respond(
                "{\"response\":\"hel\",\"done\":false}\n{\"response\":\"lo\",\"done\":true}\n",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = RemoteModelProvider(
            "Ollama", "http://localhost:11434", null, "llama3", clientOverride = client(engine),
        )
        val events = provider.generateStreaming("hi").toList()
        assertEquals(listOf("hel"), partials(events))
        val done = events.filterIsInstance<ModelResponse.Complete>().single()
        assertEquals("lo", done.text)
        assertFalse(events.any { it is ModelResponse.Error })
    }

    @Test
    fun openAiStreamsSse() = runTest {
        val engine = MockEngine {
            respond(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"!\"}}]}\n\n" +
                    "data: [DONE]\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val provider = RemoteModelProvider(
            "OpenRouter", "https://openrouter.ai/api/v1", "k", "model-x", clientOverride = client(engine),
        )
        val events = provider.generateStreaming("hello").toList()
        assertEquals(listOf("hi", "!"), partials(events))
        assertTrue(events.any { it is ModelResponse.Complete })
    }

    @Test
    fun httpErrorsSurfaceHonestly() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"message":"bad key"}}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val provider = RemoteModelProvider(
            "OpenAI", "https://api.openai.com", "k", "gpt-x", clientOverride = client(engine),
        )
        val events = provider.generateStreaming("hi").toList()
        val err = events.filterIsInstance<ModelResponse.Error>().single()
        assertTrue(err.message.contains("HTTP 401"), "was: ${err.message}")
        assertTrue(err.message.contains("bad key"))
    }

    @Test
    fun missingApiKeyFailsFast() = runTest {
        val provider = RemoteModelProvider(
            "OpenAI", "https://api.openai.com", null, "gpt-x", clientOverride = client(MockEngine { respond("") }),
        )
        val events = provider.generateStreaming("hi").toList()
        val err = events.filterIsInstance<ModelResponse.Error>().single()
        assertTrue(err.message.contains("No API key configured"))
    }

    @Test
    fun toolDecisionParsesSingleCall() = runTest {
        val body = """{"choices":[{"message":{
            "role":"assistant","content":null,
            "tool_calls":[{"id":"1","type":"function","function":{
                "name":"next_phone_action",
                "arguments":"{\"action\":\"open_app\",\"app\":\"settings\"}"}}]}}]}"""
        val engine = MockEngine { respond(body) }
        val provider = RemoteModelProvider(
            "OpenAI", "https://api.openai.com", "k", "gpt-x", clientOverride = client(engine),
        )
        val result = provider.generateToolDecision("propose one action", """{"type":"object"}""") {
            it.contains("\"open_app\"")
        }
        val complete = result as? ModelResponse.Complete ?: error("expected Complete, got $result")
        assertTrue(complete.text.contains("open_app"))
    }

    @Test
    fun toolDecisionRejectsMultipleCalls() = runTest {
        val body = """{"choices":[{"message":{
            "role":"assistant","content":null,
            "tool_calls":[
                {"function":{"name":"next_phone_action","arguments":"{\"action\":\"a\"}"}},
                {"function":{"name":"next_phone_action","arguments":"{\"action\":\"b\"}"}}]}}]}"""
        val engine = MockEngine { respond(body) }
        val provider = RemoteModelProvider(
            "OpenAI", "https://api.openai.com", "k", "gpt-x", clientOverride = client(engine),
        )
        val result = provider.generateToolDecision("propose", """{}""") { true }
        val err = result as? ModelResponse.Error ?: error("expected Error, got $result")
        assertTrue(err.message.contains("multiple actions"), "was: ${err.message}")
    }

    @Test
    fun toolDecisionAcceptsFencedContentFallback() {
        val body = """{"choices":[{"message":{
            "role":"assistant",
            "content":"```json\n{\"action\":\"open_app\",\"app\":\"camera\"}\n```"}}]}"""
        val result = RemoteModelProvider.parseToolDecision(body) { it.contains("\"open_app\"") }
        val complete = result as? ModelResponse.Complete ?: error("expected Complete, got $result")
        assertTrue(complete.text.contains("camera"))
    }

    @Test
    fun toolDecisionBodyIsForcedSingleCall() {
        val body = RemoteModelProvider.toolDecisionBody("m", "p", """{"type":"object"}""").toString()
        assertTrue(body.contains("next_phone_action"))
        assertTrue(body.contains("\"parallel_tool_calls\":false"))
        assertTrue(body.contains("\"type\":\"function\""))
    }

    @Test
    fun anthropicDecisionParsesToolUse() {
        val body = """{"stop_reason":"tool_use","content":[
            {"type":"tool_use","name":"next_phone_action","input":{"action":"open_app","app":"maps"}}]}"""
        val result = RemoteModelProvider.parseAnthropicDecision(body) { it.contains("\"open_app\"") }
        val complete = result as? ModelResponse.Complete ?: error("expected Complete, got $result")
        assertTrue(complete.text.contains("maps"))
    }

    @Test
    fun anthropicDecisionRejectsNoTool() {
        val body = """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}"""
        val result = RemoteModelProvider.parseAnthropicDecision(body) { true }
        assertTrue(result is ModelResponse.Error)
    }

    @Test
    fun pingCountsOllamaModels() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/tags" -> respond("""{"models":[{"name":"a"},{"name":"b"}]}""")
                else -> respond("{}")
            }
        }
        val provider = RemoteModelProvider(
            "Ollama", "http://localhost:11434", null, "llama3", clientOverride = client(engine),
        )
        assertEquals("Ollama reachable · 2 model(s) installed", provider.ping())
    }

    @Test
    fun pingCountsOpenAIModels() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> respond("""{"data":[{"id":"a"},{"id":"b"},{"id":"c"}]}""")
                else -> respond("{}")
            }
        }
        val provider = RemoteModelProvider(
            "OpenAI", "https://api.openai.com", "k", "gpt-x", clientOverride = client(engine),
        )
        assertEquals("Connected · 3 model(s) visible", provider.ping())
    }

    @Test
    fun normalizedBaseStripsTrailingV1() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/chat/completions" -> respond(
                    """{"choices":[{"message":{"content":"{\"action\":\"ok\"}"}}]}""",
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val provider = RemoteModelProvider(
            "OpenRouter", "https://openrouter.ai/api/v1", "k", "m", clientOverride = client(engine),
        )
        // generateToolDecision must hit /v1/chat/completions (not /v1/v1/…).
        val result = provider.generateToolDecision("p", """{}""") { it.contains("\"ok\"") }
        assertTrue(result is ModelResponse.Complete, "was: $result")
    }
}
