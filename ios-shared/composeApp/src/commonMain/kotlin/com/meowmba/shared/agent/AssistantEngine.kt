package com.meowmba.shared.agent

import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.EmbeddingInterface
import com.meowmba.shared.memory.LocalHashEmbedder
import com.meowmba.shared.memory.MemoryItem
import com.meowmba.shared.memory.PromptBuilder
import com.meowmba.shared.memory.RecallEngine
import com.meowmba.shared.security.CleartextPolicy
import com.meowmba.shared.settings.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The brain the shared UI actually talks to. Turns a user turn plus the stored
 * history into a grounded prompt (persona, real clock line, recalled memories,
 * recent turns) and streams the provider's reply back.
 *
 * Every failure path yields [ModelResponse.Error] with a message a user can act
 * on — nothing here throws into the UI, and nothing pretends to be a reply.
 */
class AssistantEngine(
    private val config: ProviderConfig,
    private val providerFactory: (ProviderConfig) -> ModelProvider = ::providerFor,
    private val embedder: EmbeddingInterface = LocalHashEmbedder(),
) {

    /**
     * Capabilities advertised to the model. Empty by default: this port has no
     * phone-control, accessibility or robot layer, and telling the model it can
     * act would make it claim to have done things it cannot do. Platform
     * adapters that add real tools should pass their own list.
     */
    var capabilities: List<String> = emptyList()

    private var cachedProvider: ModelProvider? = null
    private var cachedFor: ProviderConfig? = null

    /** Reuses the underlying client across turns; rebuilt only when config changes. */
    private fun provider(): ModelProvider {
        val existing = cachedProvider
        if (existing != null && cachedFor == config) return existing
        (existing as? RemoteModelProvider)?.close()
        return providerFactory(config).also {
            cachedProvider = it
            cachedFor = config
        }
    }

    /**
     * Streams a reply to [userInput]. [history] should already exclude the
     * current turn — [com.meowmba.shared.memory.ConversationStore.recentForPrompt]
     * returns exactly that.
     */
    fun stream(
        userInput: String,
        history: List<ChatMessage> = emptyList(),
        memories: List<MemoryItem> = emptyList(),
    ): Flow<ModelResponse> = flow {
        if (!config.isConfigured) {
            emit(
                ModelResponse.Error(
                    "No model configured. Open Settings and set an endpoint" +
                        if (config.kind.needsApiKey) " and API key." else "."
                )
            )
            return@flow
        }

        // Refuse a public http:// endpoint before the API key reaches the wire.
        if (config.kind != com.meowmba.shared.settings.ProviderKind.ON_DEVICE) CleartextPolicy.denyReason(config.baseUrl)?.let { reason ->
            emit(ModelResponse.Error(reason))
            return@flow
        }

        val recalled = if (memories.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                val queryEmbedding = embedder.getEmbedding(userInput)
                RecallEngine.rank(queryEmbedding, userInput, memories)
            }.getOrDefault(emptyList())
        }

        val prompt = PromptBuilder.build(
            companionName = config.companionName.ifBlank { "Meow" },
            userInput = userInput,
            recentMessages = history,
            recalled = recalled,
            nowEpochMillis = PlatformTime.currentMillis(),
            utcOffsetMinutes = PlatformTime.utcOffsetMinutes(),
            capabilities = capabilities,
        )

        emitAll(provider().generateStreaming(prompt))
    }

    /** Human-readable connectivity check for the Settings "Test" button. */
    suspend fun ping(): String {
        if (config.kind == com.meowmba.shared.settings.ProviderKind.ON_DEVICE) return if (OnDeviceModel.driver?.isInstalled() == true) "On-device model installed" else "Download the on-device model in LLM first."
        if (config.kind == com.meowmba.shared.settings.ProviderKind.TOTO_LION) {
            if (!config.isConfigured) return "Toto Lion needs a valid HTTPS endpoint and 64-character certificate pin"
            return when (val response = provider().generate("Health check only. Reply briefly; do not use tools or issue commands.")) {
                is ModelResponse.Complete -> "Connected · Toto Lion"
                is ModelResponse.Error -> "Toto Lion check failed: ${response.message}"
                is ModelResponse.Partial -> "Toto Lion check failed: incomplete response"
            }
        }
        if (config.baseUrl.isBlank()) return "No endpoint set"
        CleartextPolicy.denyReason(config.baseUrl)?.let { return it }
        val provider = provider()
        return (provider as? RemoteModelProvider)?.ping()
            ?: "Connected · ${provider.name}"
    }

    /** Releases the provider's HTTP client. */
    fun close() {
        (cachedProvider as? RemoteModelProvider)?.close()
        cachedProvider = null
        cachedFor = null
    }

    companion object {
        /** Builds the wire provider for a config. Kept public so tests can assert on it. */
        fun providerFor(config: ProviderConfig): ModelProvider = when (config.kind) {
            com.meowmba.shared.settings.ProviderKind.ON_DEVICE -> OnDeviceProvider()
            com.meowmba.shared.settings.ProviderKind.TOTO_LION -> {
                val endpoint = requireNotNull(config.lionEndpoint) { "Toto Lion endpoint must be HTTPS with no path, query, fragment, or userinfo" }
                require(config.hasValidLionPin) { "Toto Lion certificate pin must be 64 hexadecimal characters" }
                TotoLionProvider(host = endpoint.host, port = endpoint.port, certSha256 = config.certSha256)
            }
            else -> RemoteModelProvider(
                name = config.kind.name.lowercase().replace('_', '-'),
                baseUrl = config.baseUrl,
                apiKey = config.apiKey.ifBlank { null },
                model = config.model,
                style = config.kind.apiStyle,
            )
        }
    }
}
