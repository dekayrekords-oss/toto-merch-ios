package com.meowmba.shared.agent

import com.meowmba.shared.bridge.DesktopBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Reply-only Toto/Lion endpoint. Motion is rejected at the schema boundary. */
class TotoLionProvider(
    private val host: String,
    private val port: Int = 8445,
    private val certSha256: String,
    private val tokenSecretKey: String = "toto_lion_token",
    private val http: com.meowmba.shared.bridge.PinnedHttpDriver? = DesktopBridge.http,
    private val secrets: com.meowmba.shared.bridge.SecretStoreDriver? = DesktopBridge.secrets,
) : ModelProvider {
    override val name: String = "Toto Lion"
    override fun generateStreaming(prompt: String): Flow<ModelResponse> = flow { emit(generate(prompt)) }
    override suspend fun generate(prompt: String): ModelResponse {
        if (!host.matches(Regex("^[A-Za-z0-9.-]+$")) || host.length > 253 || port !in 1..65535 || !certSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) return ModelResponse.Error("Toto Lion host, port, or certificate pin is invalid")
        val driver = http ?: return ModelResponse.Error("Toto Lion transport is unavailable")
        val token = secrets?.readSecret(tokenSecretKey).orEmpty()
        if (token.isBlank() || token.any { it.isWhitespace() || it.code < 0x20 }) return ModelResponse.Error("Toto Lion token is not configured")
        val body = buildJsonObject { putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) } }.toString().encodeToByteArray()
        return try { withTimeout(20_000) { kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            driver.post(host, port, "/chat", body, certSha256, mapOf("Authorization" to "Bearer $token"), 20_000) { status, bytes, error ->
                if (!cont.isActive) return@post
                if (error != null) cont.resumeWith(Result.success(ModelResponse.Error("Toto Lion request failed")))
                else if (status !in 200..299 || bytes == null || bytes.size > 64 * 1024) cont.resumeWith(Result.success(ModelResponse.Error("Toto Lion request failed (HTTP $status)")))
                else cont.resumeWith(Result.success(parse(bytes.decodeToString())))
            }
        } } } catch (e: CancellationException) { throw e } catch (_: Throwable) { ModelResponse.Error("Toto Lion request timed out") }
    }
    private fun parse(text: String): ModelResponse = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        require(o["motion_enabled"] is JsonPrimitive && o["motion_enabled"]!!.jsonPrimitive.isString.not() && o["motion_enabled"]!!.jsonPrimitive.boolean == false) { "Toto Lion response enabled motion" }
        require(o["model"]?.jsonPrimitive?.isString == true)
        val model = o["model"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(model.isNotEmpty() && model.length <= 256 && model.none { it == '\n' || it == '\r' }) { "Toto Lion response model is invalid" }
        require(o["reply"]?.jsonPrimitive?.isString == true)
        val reply = o["reply"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(reply.length <= 48 * 1024 && reply.none { it == '\u0000' }) { "Toto Lion response is too large" }
        require(reply.isNotEmpty()) { "Toto Lion response has no reply" }
        ModelResponse.Complete(reply)
    }.getOrElse { ModelResponse.Error("Invalid Toto Lion response") }
}
