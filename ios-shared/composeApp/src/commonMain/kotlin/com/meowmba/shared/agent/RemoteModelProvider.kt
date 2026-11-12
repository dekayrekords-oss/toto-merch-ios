package com.meowmba.shared.agent

import com.meowmba.shared.agent.StreamParsers
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.Serializable
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
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * [ModelProvider] for remote streaming APIs — the missing network half of the
 * port. Two wire protocols are supported:
 *  - Ollama (POST /api/generate, newline-delimited JSON)
 *  - OpenAI-compatible (POST /v1/chat/completions, Server-Sent Events) —
 *    also covers llama.cpp, vLLM, and other OpenAI-compatible servers.
 *  - Anthropic (POST /v1/messages, SSE) — the original also shipped this.
 *
 * Rules enforced here (kept from the original):
 *  - HTTP status is checked before parsing — errors are reported honestly
 *  - A missing API key fails fast instead of sending "Bearer null"
 *  - Generation timeouts are generous, connect timeouts are short
 */
class RemoteModelProvider(
    override val name: String,
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val style: ApiStyle = if (name.contains("ollama", ignoreCase = true)) {
        ApiStyle.OLLAMA
    } else {
        ApiStyle.OPENAI_COMPATIBLE
    },
    clientOverride: HttpClient? = null,
) : ModelProvider {

    enum class ApiStyle { OLLAMA, OPENAI_COMPATIBLE, ANTHROPIC }

    /**
     * Defense in depth: if a base url is ever stored with a trailing "/v1",
     * appending "/v1/chat/completions" would hit "/v1/v1/chat/completions" — a
     * guaranteed 404. Stripping the suffix here makes the provider immune.
     */
    private val normalizedBase: String = baseUrl.trimEnd('/').removeSuffix("/v1")
    /** Gemini's OpenAI-compatible endpoint lives at `/v1beta/openai/chat/completions`, not `/v1/…`. */
    private val chatUrl: String = if (normalizedBase.endsWith("/openai") && "generativelanguage.googleapis.com" in normalizedBase)
        "$normalizedBase/chat/completions" else "$normalizedBase/v1/chat/completions"

    private val client = clientOverride ?: HttpClient {
        followRedirects = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 300_000   // local models can be slow
            socketTimeoutMillis = 300_000
        }
    }

    // Ktor may execute response handlers on its IO context. A channel safely
    // carries streamed events back to the collector while preserving cancellation.
    override fun generateStreaming(prompt: String): Flow<ModelResponse> = channelFlow {
        if (style != ApiStyle.OLLAMA && apiKey.isNullOrBlank()) {
            send(ModelResponse.Error("No API key configured for $name — add it in Settings"))
            return@channelFlow
        }
        try {
            when (style) {
                ApiStyle.OLLAMA -> streamOllama(prompt)
                ApiStyle.OPENAI_COMPATIBLE -> streamOpenAI(prompt)
                ApiStyle.ANTHROPIC -> streamAnthropic(prompt)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            warn("$name unreachable: ${e.message ?: "network error"}")
            send(ModelResponse.Error("$name unreachable: ${e.message ?: "network error"}"))
        }
    }

    private suspend fun ProducerScope<ModelResponse>.streamOllama(prompt: String) {
        client.preparePost("$normalizedBase/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(OllamaRequest(model, prompt, stream = true))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = StreamParsers.extractApiErrorMessage(response.bodyAsText())
                warn("$name request failed: HTTP ${response.status.value}: $detail")
                send(ModelResponse.Error("$name HTTP ${response.status.value}: $detail"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                StreamParsers.parseOllamaLine(line)?.let { send(it) }
            }
        }
    }

    private suspend fun ProducerScope<ModelResponse>.streamOpenAI(prompt: String) {
        client.preparePost(chatUrl) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            // OpenRouter attribution headers: harmless elsewhere, expected there.
            header("HTTP-Referer", "https://meowmba.local")
            header("X-Title", "MEOWMBA")
            contentType(ContentType.Application.Json)
            setBody(OpenAIRequest(model, listOf(OpenAIMessage("user", prompt)), stream = true))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = StreamParsers.extractApiErrorMessage(response.bodyAsText())
                warn("$name request failed: HTTP ${response.status.value} for model $model: $detail")
                send(ModelResponse.Error("$name HTTP ${response.status.value}: $detail"))
                return@execute
            }
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                StreamParsers.parseOpenAISseLine(line)?.let { send(it) }
            }
        }
    }

    private suspend fun ProducerScope<ModelResponse>.streamAnthropic(prompt: String) {
        client.preparePost("$normalizedBase/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model); put("max_tokens", 4096); put("stream", true)
                putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
            }.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                send(ModelResponse.Error("$name HTTP ${response.status.value}. Check API key, model and account access."))
                return@execute
            }
            val channel = response.bodyAsChannel()
            var hasText = false
            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val event = Json.parseToJsonElement(line.removePrefix("data:").trim()).jsonObject
                when (event["type"]?.jsonPrimitive?.content) {
                    "content_block_delta" -> {
                        val delta = event["delta"]?.jsonObject
                        if (delta?.get("type")?.jsonPrimitive?.content == "text_delta") {
                            val text = delta["text"]?.jsonPrimitive?.content.orEmpty()
                            hasText = hasText || text.isNotBlank()
                            if (text.isNotEmpty()) send(ModelResponse.Partial(text))
                        }
                    }
                    "error" -> { send(ModelResponse.Error("$name streaming error. Please retry.")); return@execute }
                    "message_delta" -> if (event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content == "max_tokens") {
                        send(ModelResponse.Error("$name response reached its output limit. Ask to continue.")); return@execute
                    }
                    "message_stop" -> {
                        send(if (hasText) ModelResponse.Complete("") else ModelResponse.Error("$name returned no text."))
                        return@execute
                    }
                }
            }
            send(ModelResponse.Error("$name connection ended before the reply completed."))
        }
    }

    override suspend fun generate(prompt: String): ModelResponse {
        val full = StringBuilder()
        var result: ModelResponse = ModelResponse.Error("No response")
        generateStreaming(prompt).collect { response ->
            when (response) {
                is ModelResponse.Partial -> full.append(response.text)
                is ModelResponse.Complete -> result = ModelResponse.Complete(full.toString())
                is ModelResponse.Error -> result = response
            }
        }
        return result
    }

    /**
     * Use the model's real tool-calling protocol for controller decisions.
     * One proposed action is returned as JSON; this never executes a tool.
     * [validate] is supplied by the caller (the controller layer owns what a
     * legal action is); the original defaulted to PhoneAgentLoop.parseAction.
     */
    suspend fun generateToolDecision(prompt: String, schema: String,
                                    validate: (String) -> Boolean = { it.isNotBlank() }): ModelResponse {
        if (style == ApiStyle.ANTHROPIC) return anthropicDecision(prompt, schema, validate)
        if (style != ApiStyle.OPENAI_COMPATIBLE) return generate(prompt)
        if (apiKey.isNullOrBlank()) return ModelResponse.Error("No API key configured for $name — complete Your AI setup")
        return try {
            var instruction = prompt
            var parsed: ModelResponse = ModelResponse.Error("No controller decision")
            repeat(2) {
                val response = client.post(chatUrl) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    header("HTTP-Referer", "https://meowmba.local")
                    header("X-Title", "MEOWMBA")
                    contentType(ContentType.Application.Json)
                    setBody(toolDecisionBody(model, instruction, schema).toString())
                    timeout { requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
                }
                val body = response.bodyAsText()
                if (!response.status.isSuccess()) return ModelResponse.Error("$name HTTP ${response.status.value}: ${StreamParsers.extractApiErrorMessage(body)}")
                parsed = parseToolDecision(body, validate)
                if (parsed is ModelResponse.Complete) return parsed
                // No controller action has run. Repair a malformed proposal
                // once; never replay an executed tool or retry a network error.
                instruction = prompt + "\nYour previous proposal was rejected: " +
                    (parsed as ModelResponse.Error).message.take(200) +
                    ". No action was executed. Return EXACTLY ONE next_phone_action function call matching the supplied schema. Do not batch steps or return prose."
            }
            parsed
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ModelResponse.Error("$name planning failed: ${e.message ?: "network error"}") }
    }

    private suspend fun anthropicDecision(prompt: String, schema: String, validate: (String) -> Boolean): ModelResponse {
        if (apiKey.isNullOrBlank()) return ModelResponse.Error("No API key configured for $name")
        return try {
            val response = client.post("$normalizedBase/v1/messages") {
                header("x-api-key", apiKey); header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                timeout { requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
                setBody(buildJsonObject {
                    put("model", model); put("max_tokens", 2048)
                    putJsonArray("messages") { add(buildJsonObject {
                        put("role", "user"); put("content", prompt + "\nPropose exactly one next_phone_action tool call. Execution and approval are handled by the phone.")
                    }) }
                    putJsonArray("tools") { add(buildJsonObject {
                        put("name", "next_phone_action"); put("description", "Propose one validated controller action; never execute it.")
                        put("input_schema", Json.parseToJsonElement(schema))
                    }) }
                    putJsonObject("tool_choice") { put("type", "auto"); put("disable_parallel_tool_use", true) }
                }.toString())
            }
            if (!response.status.isSuccess()) ModelResponse.Error("$name HTTP ${response.status.value}. Check key, model and account access.")
            else parseAnthropicDecision(response.bodyAsText(), validate)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ModelResponse.Error("$name controller request failed. No action executed.") }
    }

    /** Release the Ktor client. The original implemented java.io.Closeable. */
    fun close() { client.close() }

    /**
     * Health check used by the LLM page's "Test connection" button.
     * Returns a human-readable status; never throws.
     */
    suspend fun ping(): String = try {
        when (style) {
            ApiStyle.OLLAMA -> {
                val response = client.get("$normalizedBase/api/tags")
                if (response.status.isSuccess()) {
                    val count = StreamParsers.countOllamaModels(response.bodyAsText())
                    "Ollama reachable · $count model(s) installed"
                } else {
                    "Ollama HTTP ${response.status.value}"
                }
            }
            ApiStyle.OPENAI_COMPATIBLE, ApiStyle.ANTHROPIC -> {
                if (apiKey.isNullOrBlank()) {
                    "No API key configured — add it in Settings"
                } else {
                    val response = client.get("$normalizedBase/v1/models") {
                        if (style == ApiStyle.ANTHROPIC) {
                            header("x-api-key", apiKey); header("anthropic-version", "2023-06-01")
                        } else header(HttpHeaders.Authorization, "Bearer $apiKey")
                    }
                    if (response.status.isSuccess()) {
                        val count = StreamParsers.countOpenAIModels(response.bodyAsText())
                        "Connected · $count model(s) visible"
                    } else {
                        "HTTP ${response.status.value} — check API key"
                    }
                }
            }
        }
    } catch (e: Exception) {
        "Unreachable: ${e.message ?: "network error"}"
    }

    companion object {
        const val TAG = "RemoteModel"

        internal fun parseAnthropicDecision(body: String, validate: (String) -> Boolean): ModelResponse = runCatching {
            val obj = Json.parseToJsonElement(body).jsonObject
            check(obj["stop_reason"]?.jsonPrimitive?.content == "tool_use") { "No completed controller tool call" }
            val calls = obj["content"]!!.jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }
            check(calls.size == 1) { "Expected exactly one controller action" }
            val call = calls.single().jsonObject
            check(call["name"]?.jsonPrimitive?.content == "next_phone_action") { "Unexpected controller tool" }
            val input = call["input"] as? JsonObject ?: error("Invalid controller input")
            val args = input.toString()
            check(args.length <= 16_000 && validate(args)) { "Invalid controller action" }
            ModelResponse.Complete(args)
        }.getOrElse { ModelResponse.Error("${it.message}. No action executed.") }

        internal fun toolDecisionBody(model: String, prompt: String, schema: String): JsonObject = buildJsonObject {
            put("model", model); put("stream", false); put("max_tokens", 900)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
            putJsonArray("tools") { add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") { put("name", "next_phone_action"); put("description", "Propose one controller action from this schema; execution is handled by the phone after validation."); put("parameters", Json.parseToJsonElement(schema)) }
            }) }
            putJsonObject("tool_choice") { put("type", "function"); putJsonObject("function") { put("name", "next_phone_action") } }
            put("parallel_tool_calls", false)
        }

        internal fun parseToolDecision(body: String, validate: (String) -> Boolean = { it.isNotBlank() }): ModelResponse = runCatching {
            val message = Json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject
            val rawCalls = message["tool_calls"]
            check(rawCalls == null || rawCalls == JsonNull || rawCalls is JsonArray) { "Invalid tool-call envelope" }
            val calls = rawCalls as? JsonArray
            if (calls.isNullOrEmpty()) {
                // Some compatible providers ignore forced tool choice and
                // return the requested JSON in content. Accept one exact,
                // validated proposal; never infer an action from prose.
                val content = message["content"]?.jsonPrimitive?.content ?: error("Model did not return an action")
                check(content.length <= 16_000) { "Action was too large" }
                val clean = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val proposal = Json.parseToJsonElement(clean) as? JsonObject ?: error("Expected one controller action object")
                val args = proposal.toString()
                check(validate(args)) { "Invalid controller action" }
                return@runCatching ModelResponse.Complete(args)
            }
            check(calls.size == 1) { "Model returned multiple actions; nothing executed" }
            val function = calls.single().jsonObject["function"]!!.jsonObject
            check(function["name"]?.jsonPrimitive?.content == "next_phone_action") { "Unexpected tool name; nothing executed" }
            val args = function["arguments"]!!.jsonPrimitive.content
            check(args.length <= 16_000) { "Action was too large" }
            check(validate(args)) { "Invalid controller action" }
            ModelResponse.Complete(args)
        }.getOrElse { ModelResponse.Error(it.message ?: "Invalid planning reply") }

        private fun warn(message: String) {
            // KMP logger seam: JVM/Android would route through Log.w here.
            println("[$TAG] $message")
        }
    }
}

@Serializable
data class OllamaRequest(val model: String, val prompt: String, val stream: Boolean = true)

@Serializable
data class OpenAIRequest(val model: String, val messages: List<OpenAIMessage>, val stream: Boolean)

@Serializable
data class OpenAIMessage(val role: String, val content: String)
