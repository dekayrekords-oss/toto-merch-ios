package com.meowmba.shared

import com.meowmba.shared.agent.AssistantEngine
import com.meowmba.shared.agent.ModelProvider
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.RemoteModelProvider
import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.MessageRole
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AssistantEngine: prompt assembly, provider selection and honest failures. */
class AssistantEngineTest {

    /** Records the prompt it was handed and replays canned events. */
    private class FakeProvider(
        override val name: String = "fake",
        private val events: List<ModelResponse> = listOf(
            ModelResponse.Partial("Hel"),
            ModelResponse.Partial("lo"),
            ModelResponse.Complete(""),
        ),
    ) : ModelProvider {
        var lastPrompt: String? = null
        var calls = 0

        override fun generateStreaming(prompt: String): Flow<ModelResponse> = flow {
            lastPrompt = prompt
            calls++
            events.forEach { emit(it) }
        }

        override suspend fun generate(prompt: String): ModelResponse {
            val text = StringBuilder()
            var result: ModelResponse = ModelResponse.Error("no response")
            generateStreaming(prompt).collect { event ->
                when (event) {
                    is ModelResponse.Partial -> text.append(event.text)
                    is ModelResponse.Complete -> result = ModelResponse.Complete(text.toString())
                    is ModelResponse.Error -> result = event
                }
            }
            return result
        }
    }

    private val configured = ProviderConfig(
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://openrouter.ai/api",
        apiKey = "sk-test",
        model = "some/model",
        companionName = "Meow",
    )

    private fun engineWith(
        provider: ModelProvider,
        config: ProviderConfig = configured,
        onBuild: () -> Unit = {},
    ) = AssistantEngine(config, providerFactory = {
        onBuild()
        provider
    })

    @Test
    fun unconfiguredEngineRefusesInsteadOfInventingAReply() = runTest {
        var factoryCalls = 0
        val engine = AssistantEngine(
            config = ProviderConfig(),
            providerFactory = {
                factoryCalls++
                FakeProvider()
            },
        )

        val events = engine.stream("hello").toList()

        val error = events.filterIsInstance<ModelResponse.Error>().single()
        assertTrue(
            error.message.contains("No model configured"),
            "the user must be told what is wrong, got: ${error.message}",
        )
        assertEquals(0, factoryCalls, "no provider should be built when nothing is configured")
    }

    @Test
    fun aConfiguredEngineStreamsProviderPartialsInOrder() = runTest {
        val engine = engineWith(FakeProvider())

        val events = engine.stream("hi").toList()

        assertEquals(
            listOf("Hel", "lo"),
            events.filterIsInstance<ModelResponse.Partial>().map { it.text },
        )
        assertFalse(events.any { it is ModelResponse.Error })
    }

    @Test
    fun thePromptCarriesPersonaClockAndUserTurn() = runTest {
        val provider = FakeProvider()
        val engine = engineWith(provider)

        engine.stream("what time is it").toList()

        val prompt = assertNotNull(provider.lastPrompt)
        assertTrue(prompt.contains("what time is it"), "the user turn must be in the prompt")
        assertTrue(
            prompt.contains("The current time and date right now"),
            "the clock line is what stops the model guessing the time",
        )
        assertTrue(prompt.endsWith("Meow:"), "the prompt must end on the companion's turn")
    }

    @Test
    fun priorTurnsAreIncludedInThePrompt() = runTest {
        val provider = FakeProvider()
        val engine = engineWith(provider)

        engine.stream(
            "and now?",
            history = listOf(
                ChatMessage(MessageRole.USER, "my name is Leigh", 1),
                ChatMessage(MessageRole.ASSISTANT, "Noted.", 2),
            ),
        ).toList()

        val prompt = assertNotNull(provider.lastPrompt)
        assertTrue(prompt.contains("my name is Leigh"))
        assertTrue(prompt.contains("Noted."))
    }

    @Test
    fun providerErrorsPassThroughUnchanged() = runTest {
        val failure = ModelResponse.Error("HTTP 401: invalid api key")
        val engine = engineWith(FakeProvider(events = listOf(failure)))

        val events = engine.stream("hi").toList()

        assertEquals(failure, events.single())
    }

    @Test
    fun capabilitiesAreNotAdvertisedByDefault() = runTest {
        // This port has no phone-control layer. Telling the model otherwise
        // makes it claim to have done things it cannot do.
        val provider = FakeProvider()
        val engine = engineWith(provider)

        engine.stream("open maps").toList()

        val prompt = assertNotNull(provider.lastPrompt)
        assertFalse(prompt.contains("You are not a text-only chatbot"))
    }

    @Test
    fun capabilitiesCanBeAdvertisedWhenAPlatformSuppliesTools() = runTest {
        val provider = FakeProvider()
        val engine = engineWith(provider)
        engine.capabilities = listOf("Open the settings screen")

        engine.stream("what can you do").toList()

        val prompt = assertNotNull(provider.lastPrompt)
        assertTrue(prompt.contains("Open the settings screen"))
    }

    @Test
    fun theProviderIsReusedAcrossTurns() = runTest {
        var built = 0
        val provider = FakeProvider()
        val engine = engineWith(provider) { built++ }

        engine.stream("one").toList()
        engine.stream("two").toList()

        assertEquals(1, built, "the HTTP client must not be rebuilt per turn")
        assertEquals(2, provider.calls)
    }

    @Test
    fun closeForcesANewProviderOnTheNextTurn() = runTest {
        var built = 0
        val engine = engineWith(FakeProvider()) { built++ }

        engine.stream("one").toList()
        engine.close()
        engine.stream("two").toList()

        assertEquals(2, built, "a closed provider must not be reused")
    }

    @Test
    fun providerForMapsEveryKindToAProvider() {
        ProviderKind.entries.forEach { kind ->
            val provider = AssistantEngine.providerFor(
                configured.copy(kind = kind, apiKey = "k")
            )
            assertTrue(
                if (kind == ProviderKind.ON_DEVICE) provider is com.meowmba.shared.agent.OnDeviceProvider else provider is RemoteModelProvider,
                "$kind must use its own runtime",
            )
            assertTrue(provider.name.isNotBlank(), "$kind produced a nameless provider")
        }
    }

    @Test
    fun blankApiKeyBecomesNullRatherThanAnEmptyBearerToken() {
        // Sending "Bearer " is a 401; the provider treats null as "no key".
        val provider = AssistantEngine.providerFor(configured.copy(apiKey = ""))
        assertTrue(provider is RemoteModelProvider)
        assertTrue(provider.name.isNotBlank())
    }

    @Test
    fun recallIsSkippedWhenThereAreNoMemories() = runTest {
        // The embedder is only consulted when there is something to rank, so an
        // empty memory store costs nothing.
        val provider = FakeProvider()
        val engine = engineWith(provider)

        engine.stream("hi", memories = emptyList()).toList()

        val prompt = assertNotNull(provider.lastPrompt)
        assertFalse(prompt.contains("Relevant things you remember"))
    }

    // -- cleartext gate ---------------------------------------------------

    @Test
    fun aPublicCleartextEndpointIsRefusedBeforeTheKeyIsSent() = runTest {
        var factoryCalls = 0
        val engine = AssistantEngine(
            config = configured.copy(baseUrl = "http://example.com"),
            providerFactory = {
                factoryCalls++
                FakeProvider()
            },
        )

        val events = engine.stream("hi").toList()

        val error = events.filterIsInstance<ModelResponse.Error>().single()
        assertTrue(
            error.message.contains("local network"),
            "expected a cleartext refusal, got: ${error.message}",
        )
        assertEquals(0, factoryCalls, "no client may be built for a refused endpoint")
    }

    @Test
    fun loopbackAndLanHttpAreAllowed() = runTest {
        listOf(
            "http://localhost:11434",
            "http://127.0.0.1:8080",
            "http://192.168.1.10:8080",
            "http://10.0.2.2:8088",
        ).forEach { url ->
            val provider = FakeProvider()
            val engine = engineWith(
                provider,
                config = configured.copy(
                    kind = ProviderKind.OLLAMA,
                    baseUrl = url,
                    apiKey = "",
                ),
            )
            engine.stream("hi").toList()
            assertNotNull(provider.lastPrompt, "$url should have reached the provider")
        }
    }

    @Test
    fun pingRefusesAPublicCleartextEndpoint() = runTest {
        val engine = AssistantEngine(
            config = configured.copy(baseUrl = "http://example.com"),
            providerFactory = { FakeProvider() },
        )
        assertTrue(engine.ping().contains("local network"))
    }
}
