package com.meowmba.shared.settings

import com.meowmba.shared.agent.RemoteModelProvider
import com.meowmba.shared.core.HttpUrl
import kotlinx.serialization.Serializable

/**
 * Which wire protocol the configured endpoint speaks. Mirrors
 * [RemoteModelProvider.ApiStyle] but is serializable and user-facing, so it
 * lives here rather than in the agent layer.
 */
@Serializable
enum class ProviderKind {
    /** OpenAI-compatible `/v1/chat/completions` — OpenAI, OpenRouter, llama.cpp, vLLM, LM Studio. */
    OPENAI_COMPATIBLE,

    /** Ollama's native `/api/generate` (newline-delimited JSON). */
    OLLAMA,

    /** Anthropic's `/v1/messages`. */
    ANTHROPIC,
    /** Toto/Lion reply-only endpoint over certificate-pinned HTTPS. */
    TOTO_LION,
    ON_DEVICE;

    val apiStyle: RemoteModelProvider.ApiStyle
        get() = when (this) {
            OPENAI_COMPATIBLE -> RemoteModelProvider.ApiStyle.OPENAI_COMPATIBLE
            OLLAMA -> RemoteModelProvider.ApiStyle.OLLAMA
            ANTHROPIC -> RemoteModelProvider.ApiStyle.ANTHROPIC
            TOTO_LION -> error("Toto Lion uses its dedicated reply-only provider")
            ON_DEVICE -> error("On-device inference has no HTTP protocol")
        }

    val needsApiKey: Boolean get() = this != OLLAMA && this != ON_DEVICE && this != TOTO_LION
}

/**
 * Everything the app needs to reach a model. Persisted as JSON through
 * [SettingsStore]; never logged.
 */
@Serializable
data class ProviderConfig(
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    /** Endpoint root, WITHOUT a trailing `/v1` — the provider strips it if present. */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** SHA-256 of the pinned DER leaf certificate; never a plaintext token. */
    val certSha256: String = "",
    val model: String = "",
    val companionName: String = "Toto",
) {
    /** The only address shape the reply-only, certificate-pinned Lion transport accepts. */
    data class LionEndpoint(val host: String, val port: Int)

    /**
     * Lion owns one pinned HTTPS endpoint, not a general URL.  Reject URL
     * decorations rather than silently dropping them before the request is made.
     */
    val lionEndpoint: LionEndpoint?
        get() {
            val url = HttpUrl.parse(baseUrl) ?: return null
            if (url.scheme != "https" || url.rawUserInfo != null || url.rawQuery != null ||
                url.fragment != null || url.path != "/") return null
            val lionHost = url.host?.lowercase() ?: return null
            if (!lionHost.matches(Regex("^[A-Za-z0-9.-]+$"))) return null
            val lionPort = url.port ?: LION_DEFAULT_PORT
            return lionPort.takeIf { it in 1..65535 }?.let { LionEndpoint(lionHost, it) }
        }

    val hasValidLionPin: Boolean
        get() = certSha256.matches(Regex("^[0-9a-fA-F]{64}$"))

    /**
     * True when this config could plausibly produce a reply. Deliberately
     * strict: a base URL and model are always required, and a key is required
     * for every protocol except Ollama (which is usually keyless on localhost).
     */
    val isConfigured: Boolean
        get() = when (kind) {
            ProviderKind.ON_DEVICE -> true
            ProviderKind.TOTO_LION -> lionEndpoint != null && hasValidLionPin
            else -> baseUrl.isNotBlank() && model.isNotBlank() && (!kind.needsApiKey || apiKey.isNotBlank())
        }

    /** Host portion of [baseUrl], lowercased, without scheme, port or userinfo. */
    val host: String
        get() {
            val authority = baseUrl.substringAfter("://", baseUrl)
                .substringBefore('/')
                .substringAfter('@')
            return authority.substringBefore(':').lowercase()
        }

    /**
     * Loopback or RFC 1918 address. A self-hosted llama.cpp / vLLM box is
     * OpenAI-compatible but usually has no auth at all, so it needs a
     * different explanation than "go buy a key".
     */
    val isLocalEndpoint: Boolean
        get() {
            val h = host
            return when {
                h.isEmpty() -> false
                h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]" -> true
                h.startsWith("10.") || h.startsWith("192.168.") -> true
                h.startsWith("172.") -> {
                    val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
                    second != null && second in 16..31
                }
                else -> false
            }
        }

    /**
     * What to tell the user about the API key field, or null when the protocol
     * needs no key at all. Local OpenAI-compatible servers are the confusing
     * case: the field is required by the wire protocol but any value works.
     */
    val keyHint: String?
        get() = when {
            !kind.needsApiKey -> null
            isLocalEndpoint ->
                "This looks like a server on your own network. Many accept any " +
                    "placeholder value here — try the word local if it has no auth."
            else -> "Stored in this app's private storage on the device, and sent " +
                "only to the endpoint above."
        }

    /** Short human label for the status line, e.g. "OpenRouter · llama3.2". */
    fun describe(): String {
        if (kind == ProviderKind.ON_DEVICE) return "On-device · Qwen3 0.6B"
        if (!isConfigured) return "not configured"
        val host = baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "$host · $model"
    }

    /** Applies a preset's endpoint and protocol, keeping the user's key and model (an on-device model never carries over). */
    fun withPreset(preset: ProviderPreset): ProviderConfig = copy(
        kind = preset.kind,
        baseUrl = preset.baseUrl,
        // Lion tokens are Keychain-only. Never carry an old value through a
        // provider switch or serialize it as the generic API-key field.
        apiKey = if (kind == ProviderKind.TOTO_LION || preset.kind == ProviderKind.TOTO_LION) "" else apiKey,
        // A model only follows the user when they chose it: on-device names and other presets' suggestions don't.
        model = (if (kind == ProviderKind.ON_DEVICE || ProviderPresets.ALL.any { it.modelHint == model }) "" else model).ifBlank { preset.modelHint },
    )

    /** Defensive persistence boundary for legacy/corrupt Lion configurations. */
    fun safeForPersistence(): ProviderConfig =
        if (kind == ProviderKind.TOTO_LION && apiKey.isNotEmpty()) copy(apiKey = "") else this

    companion object {
        const val LION_DEFAULT_PORT = 8445
    }
}

/** A known-good endpoint the user can pick instead of typing a URL. */
data class ProviderPreset(
    val label: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val modelHint: String,
    /** Where the user obtains a key, or null when the endpoint needs none. */
    val keyUrl: String?,
)

object ProviderPresets {
    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            label = "OpenRouter",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api",
            modelHint = "google/gemma-2-9b-it:free",
            keyUrl = "https://openrouter.ai/settings/keys",
        ),
        ProviderPreset(
            label = "OpenAI",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com",
            modelHint = "gpt-4o-mini",
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        ProviderPreset(
            label = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            modelHint = "claude-sonnet-4-5",
            keyUrl = "https://platform.claude.com/settings/keys",
        ),
        ProviderPreset(
            label = "Google Gemini",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            modelHint = "gemini-2.5-flash",
            keyUrl = "https://aistudio.google.com/apikey",
        ),
        ProviderPreset(
            label = "Groq",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai",
            modelHint = "llama-3.3-70b-versatile",
            keyUrl = "https://console.groq.com/keys",
        ),
        ProviderPreset(
            label = "Mistral",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.mistral.ai",
            modelHint = "mistral-small-latest",
            keyUrl = "https://console.mistral.ai/api-keys",
        ),
        ProviderPreset(
            label = "DeepSeek",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepseek.com",
            modelHint = "deepseek-chat",
            keyUrl = "https://platform.deepseek.com/api_keys",
        ),
        ProviderPreset(
            label = "xAI Grok",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.x.ai",
            modelHint = "grok-3-mini",
            keyUrl = "https://console.x.ai",
        ),
        ProviderPreset(
            label = "Together AI",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.together.xyz",
            modelHint = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            keyUrl = "https://api.together.ai/settings/api-keys",
        ),
        ProviderPreset(
            label = "Fireworks",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.fireworks.ai/inference",
            modelHint = "accounts/fireworks/models/llama-v3p1-8b-instruct",
            keyUrl = "https://fireworks.ai/account/api-keys",
        ),
        ProviderPreset(
            label = "Cerebras",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://api.cerebras.ai",
            modelHint = "llama3.1-8b",
            keyUrl = "https://cloud.cerebras.ai/platform",
        ),
        ProviderPreset(
            label = "Hugging Face",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://router.huggingface.co",
            modelHint = "meta-llama/Llama-3.1-8B-Instruct",
            keyUrl = "https://huggingface.co/settings/tokens",
        ),
        ProviderPreset(
            label = "OpenCode Zen",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://opencode.ai/zen",
            modelHint = "big-pickle",
            keyUrl = "https://opencode.ai/auth",
        ),
        ProviderPreset(
            label = "Ollama (this machine)",
            kind = ProviderKind.OLLAMA,
            baseUrl = "http://localhost:11434",
            modelHint = "llama3.2",
            keyUrl = null,
        ),
        ProviderPreset(
            label = "LM Studio / llama.cpp / vLLM (LAN)",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:8080",
            modelHint = "local-model",
            keyUrl = null,
        ),
    )
}
