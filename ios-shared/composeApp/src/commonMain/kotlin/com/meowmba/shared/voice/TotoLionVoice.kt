package com.meowmba.shared.voice

import com.meowmba.shared.bridge.DesktopBridge
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Optional advanced Lion voice path (`toto-qwen-20261019` on Meow Lion).
 *
 * Merch defaults to the on-phone Toto voice. This client is used only when
 * the user turns on "Use Lion voice (advanced)". It never logs or serializes
 * the token. If Lion is not configured or the request fails, callers fall
 * back to the iPhone's built-in voice.
 */
object TotoLionVoice {
    const val TOKEN_KEY = "toto_lion_token"
    private const val MAX_TEXT = 1000
    private const val MAX_WAV_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 20_000L

    fun endpoint(config: ProviderConfig = SettingsStore().config.value): ProviderConfig.LionEndpoint? {
        if (config.kind == ProviderKind.TOTO_LION) return config.lionEndpoint
        // A Lion-shaped URL + pin can be used for voice even when chat uses another provider.
        return config.lionEndpoint
    }

    fun available(
        config: ProviderConfig = SettingsStore().config.value,
        secrets: com.meowmba.shared.bridge.SecretStoreDriver? = DesktopBridge.secrets,
    ): Boolean {
        val token = secrets?.readSecret(TOKEN_KEY).orEmpty()
        return endpoint(config) != null && config.hasValidLionPin &&
            token.isNotBlank() && token.none { it.isWhitespace() || it.code < 0x20 }
    }

    suspend fun render(
        text: String,
        config: ProviderConfig = SettingsStore().config.value,
        http: com.meowmba.shared.bridge.PinnedHttpDriver? = DesktopBridge.http,
        secrets: com.meowmba.shared.bridge.SecretStoreDriver? = DesktopBridge.secrets,
    ): ByteArray? {
        val spoken = text.trim().take(MAX_TEXT)
        if (spoken.isEmpty()) return null
        val dest = endpoint(config) ?: return null
        if (!config.hasValidLionPin) return null
        val driver = http ?: return null
        val token = secrets?.readSecret(TOKEN_KEY).orEmpty()
        if (token.isBlank() || token.any { it.isWhitespace() || it.code < 0x20 }) return null
        val body = buildJsonObject { put("text", spoken) }.toString().encodeToByteArray()
        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    driver.post(dest.host, dest.port, "/speak", body, config.certSha256,
                        mapOf("Authorization" to "Bearer $token"), TIMEOUT_MS.toInt()) { status, bytes, error ->
                        if (!cont.isActive) return@post
                        val wav = bytes?.takeIf { error == null && status in 200..299 && isWav(it) && it.size <= MAX_WAV_BYTES }
                        cont.resumeWith(Result.success(wav))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
}
