package com.meowmba.shared

import com.meowmba.shared.agent.AssistantEngine
import com.meowmba.shared.agent.ModelProvider
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.ProviderPreset
import com.meowmba.shared.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TotoLionConfigTest {
    private val validPin = "a".repeat(64)
    private fun lion(url: String = "https://toto.local:9443") = ProviderConfig(
        kind = ProviderKind.TOTO_LION,
        baseUrl = url,
        certSha256 = validPin,
        model = "lion",
        apiKey = "must-not-persist",
    )

    @Test
    fun lionRequiresPinnedRootHttpsEndpointAndHexPin() {
        val configured = lion()
        assertEquals("toto.local", configured.lionEndpoint?.host)
        assertEquals(9443, configured.lionEndpoint?.port)
        assertTrue(configured.isConfigured)

        listOf(
            "http://toto.local:8445",
            "https://user:pass@toto.local:8445",
            "https://toto.local:8445/chat",
            "https://toto.local:8445?next=x",
            "https://toto.local:8445#fragment",
        ).forEach { url ->
            assertNull(lion(url).lionEndpoint, "Lion must reject $url")
            assertFalse(lion(url).isConfigured, "Lion must not connect to $url")
        }
        assertFalse(lion().copy(certSha256 = "g".repeat(64)).isConfigured)
    }

    @Test
    fun lionDefaultsOnlyAnOmittedPortAndNeverCarriesApiKeyAcrossPresetChanges() {
        assertEquals(ProviderConfig.LION_DEFAULT_PORT, lion("https://toto.local").lionEndpoint?.port)
        val openAi = ProviderPreset("Other", ProviderKind.OPENAI_COMPATIBLE, "https://example.com", "m", null)
        assertEquals("", lion().withPreset(openAi).apiKey)
        val lionPreset = ProviderPreset("Lion", ProviderKind.TOTO_LION, "https://toto.local:8445", "lion", null)
        assertEquals("", ProviderConfig(apiKey = "ordinary-key").withPreset(lionPreset).apiKey)
    }

    @Test
    fun settingsStoreRemovesLionApiKeyFromMemoryAndDisk() {
        val storage = MemoryTextStorage()
        val store = SettingsStore(storage)
        store.update(lion())
        assertEquals("", store.config.value.apiKey)
        assertFalse(storage.readText(SettingsStore.KEY).orEmpty().contains("must-not-persist"))

        storage.writeText(SettingsStore.KEY, """{"kind":"TOTO_LION","baseUrl":"https://toto.local:8445","apiKey":"legacy-token","certSha256":"$validPin","model":"lion"}""")
        val reloaded = SettingsStore(storage)
        assertEquals("", reloaded.config.value.apiKey)
        assertFalse(storage.readText(SettingsStore.KEY).orEmpty().contains("legacy-token"))
    }

    @Test
    fun lionPingCallsTheProviderAndNeverClaimsSuccessForAnError() = runTest {
        val failure = FakeProvider(ModelResponse.Error("token is not configured"))
        val engine = AssistantEngine(lion().copy(apiKey = ""), providerFactory = { failure })
        assertEquals("Toto Lion check failed: token is not configured", engine.ping())
        assertEquals(1, failure.calls)

        val success = FakeProvider(ModelResponse.Complete("ready"))
        assertEquals("Connected · Toto Lion", AssistantEngine(lion(), providerFactory = { success }).ping())
        assertEquals(1, success.calls)
    }

    private class FakeProvider(private val reply: ModelResponse) : ModelProvider {
        override val name = "fake Lion"
        var calls = 0
        override fun generateStreaming(prompt: String): Flow<ModelResponse> = emptyFlow()
        override suspend fun generate(prompt: String): ModelResponse { calls++; return reply }
    }
}
