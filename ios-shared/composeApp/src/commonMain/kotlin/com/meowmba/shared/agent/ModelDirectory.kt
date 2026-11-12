package com.meowmba.shared.agent

import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The full model list for whatever provider the settings point at: OpenRouter's public catalog,
 * the account's OpenAI/Anthropic models, the models installed on an Ollama server, or `/v1/models`
 * on any other OpenAI-compatible server (llama.cpp, vLLM, LM Studio…).
 */
object ModelDirectory {
    suspend fun list(config: ProviderConfig): Result<List<OpenRouterModel>> {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return Result.failure(IllegalStateException("Choose a preset or enter the server's base URL first."))
        val key = config.apiKey.trim()
        return when {
            "openrouter.ai" in base -> OpenRouterModels.fetch(key.ifBlank { null })
            config.kind == ProviderKind.ANTHROPIC -> ProviderModels.fetch("anthropic", base, key)
            "api.openai.com" in base -> ProviderModels.fetch("openai", base, key)
            "generativelanguage.googleapis.com" in base -> fetchJson("$base/models", key.ifBlank { null }) { root ->
                (root["data"] as? JsonArray).orEmpty().mapNotNull { m ->
                    (m as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
                        ?.takeIf { "gemini" in it }?.let { OpenRouterModel(it, it, vision = true, free = false) }
                }
            }
            config.kind == ProviderKind.OLLAMA -> fetchJson("$base/api/tags", null) { root ->
                (root["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                    (m as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let { OpenRouterModel(it, it, vision = false, free = true) }
                }
            }
            else -> fetchJson("${base.removeSuffix("/v1")}/v1/models", key.ifBlank { null }) { root ->
                (root["data"] as? JsonArray).orEmpty().mapNotNull { m ->
                    (m as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.let { OpenRouterModel(it, it, vision = false, free = false) }
                }
            }
        }
    }

    private suspend fun fetchJson(url: String, key: String?, read: (JsonObject) -> List<OpenRouterModel>): Result<List<OpenRouterModel>> {
        val client = HttpClient { install(HttpTimeout) { connectTimeoutMillis = 8_000; requestTimeoutMillis = 20_000 } }
        return try {
            val response = client.get(url) { key?.let { header("Authorization", "Bearer $it") } }
            check(response.status.isSuccess()) { "The server answered ${response.status.value}. Check the address and key." }
            Result.success(read(Json.parseToJsonElement(response.bodyAsText()).jsonObject).sortedBy { it.name.lowercase() })
        } catch (e: CancellationException) { throw e }
        catch (e: IllegalStateException) { Result.failure(e) }
        catch (_: Exception) { Result.failure(IllegalStateException("Couldn't reach the server. Check the address and that it is running.")) }
        finally { client.close() }
    }
}
