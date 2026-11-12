package com.meowmba.shared.agent

import com.meowmba.shared.core.HttpUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenRouterModel(
    val id: String,
    val name: String,
    val vision: Boolean,
    val free: Boolean,
    val tools: Boolean = false,
)

/**
 * Live catalog from OpenRouter (`GET /api/v1/models`). The list is public;
 * a key is sent when we have one so the catalog matches the account.
 * Ported from `core/agent/OpenRouterModels.kt`.
 */
object OpenRouterModels {

    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    suspend fun fetch(apiKey: String? = null): Result<List<OpenRouterModel>> =
        runCatching {
            val body = client.get("https://openrouter.ai/api/v1/models") {
                parameter("limit", 1000)
                header("HTTP-Referer", "https://meowmba.local")
                header("X-Title", "MEOWMBA")
                if (!apiKey.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            }.bodyAsText()
            parse(body)
        }

    fun parse(body: String): List<OpenRouterModel> {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val arch = obj["architecture"] as? JsonObject
            val inputs = (arch?.get("input_modalities") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val outputs = (arch?.get("output_modalities") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOf("text")
            if (outputs.isNotEmpty() && "text" !in outputs) return@mapNotNull null
            val pricing = obj["pricing"] as? JsonObject
            val prompt = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull
            val completion = pricing?.get("completion")?.jsonPrimitive?.contentOrNull
            // Hoisted out of the `free` expression below. An absent request price
            // counts as "0", which is what the original inline elvis meant.
            val request = pricing?.get("request")?.jsonPrimitive?.contentOrNull ?: "0"
            OpenRouterModel(
                id = id,
                name = name,
                vision = "image" in inputs,
                free = id.endsWith(":free", ignoreCase = true) ||
                    (prompt == "0" && completion == "0" && request == "0"),
                tools = (obj["supported_parameters"] as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == "tools" } == true,
            )
        }.sortedWith(
            compareByDescending<OpenRouterModel> { it.free }
                .thenBy { it.name.lowercase() }
        )
    }
}

/**
 * Account-specific discovery for OpenAI-compatible providers.
 * Ported from `core/agent/ProviderModels.kt`; java.net.URI -> HttpUrl.
 */
object ProviderModels {
    fun base(provider: String, endpoint: String): String {
        val value = endpoint.trim().trimEnd('/').removeSuffix("/v1").ifBlank {
            when (provider) {
                "openai" -> "https://api.openai.com"
                "anthropic" -> "https://api.anthropic.com"
                "opencode" -> "https://opencode.ai/zen"
                else -> error("Enter the custom provider's base URL.")
            }
        }
        val uri = HttpUrl.parse(value) ?: throw IllegalArgumentException("Enter the custom provider's base URL.")
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.fragment == null) {
            "Use an HTTPS base URL without credentials, query or fragment."
        }
        return value
    }

    suspend fun fetch(provider: String, endpoint: String, key: String, anthropic: Boolean = provider == "anthropic",
                      clientOverride: HttpClient? = null): Result<List<OpenRouterModel>> {
        val client = clientOverride ?: HttpClient {
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 20_000; socketTimeoutMillis = 20_000 }
        }
        return try {
            require(key.isNotBlank()) { "Add an API key to load available models." }
            val root = base(provider, endpoint)
            val models = mutableListOf<OpenRouterModel>()
            var cursor: String? = null
            val cursors = mutableSetOf<String>()
            var more: Boolean
            do {
                val response = client.get("$root/v1/models") {
                    if (anthropic) {
                        header("x-api-key", key.trim()); header("anthropic-version", "2023-06-01")
                        parameter("limit", 1000); cursor?.let { parameter("after_id", it) }
                    } else header(HttpHeaders.Authorization, "Bearer ${key.trim()}")
                }
                check(response.status.isSuccess()) { "Model list HTTP ${response.status.value}. Check the key, endpoint and account access." }
                val body = response.bodyAsText()
                models += parse(body, provider == "openai").filter { provider != "opencode" || zenCompatible(it.id, anthropic) }
                val json = Json.parseToJsonElement(body).jsonObject
                more = anthropic && json["has_more"]?.jsonPrimitive?.booleanOrNull == true
                if (more) {
                    cursor = json["last_id"]?.jsonPrimitive?.contentOrNull
                    check(cursor != null && cursors.add(cursor) && cursors.size < 20) { "Model pagination was incomplete. Try again." }
                }
            } while (more)
            Result.success(models.distinctBy { it.id }.sortedBy { it.name.lowercase() })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(IllegalStateException(if (e is IllegalArgumentException || e is IllegalStateException) e.message else "Model list unavailable. Check the connection and retry.")) }
        finally { if (clientOverride == null) client.close() }
    }

    fun zenCompatible(id: String, anthropic: Boolean): Boolean = if (anthropic)
        id.startsWith("claude-") || id.startsWith("qwen3.")
    else listOf("big-pickle", "deepseek-", "minimax-", "glm-", "kimi-", "mimo-", "ling-", "nemotron-").any { id.startsWith(it) }

    fun parse(body: String, openai: Boolean): List<OpenRouterModel> {
        val data = Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: error("Invalid model list response.")
        return data.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (openai && !chatCandidate(id)) return@mapNotNull null
            OpenRouterModel(id, obj["display_name"]?.jsonPrimitive?.contentOrNull ?: id, false, false)
        }
    }

    // The list endpoint does not advertise endpoint compatibility. Hide known
    // non-chat families, preserve manual IDs, and never claim a catalog is a reply test.
    fun chatCandidate(id: String): Boolean {
        val s = id.lowercase()
        return (s.startsWith("gpt-") || s.startsWith("gpt5") || s.startsWith("gpt6") || s.matches(Regex("o[0-9].*")) || s.startsWith("chatgpt-") || s.startsWith("ft:gpt-")) &&
            listOf("audio", "realtime", "transcrib", "tts", "image", "embedding", "moderation", "search", "deep-research", "codex", "instruct", "-pro").none { it in s }
    }
}
