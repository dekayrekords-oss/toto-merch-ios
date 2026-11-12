package com.meowmba.shared

import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.ProviderPresets
import com.meowmba.shared.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider configuration rules and durable settings round-trips. */
class SettingsAndStorageTest {

    private val openRouter = ProviderConfig(
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://openrouter.ai/api",
        apiKey = "sk-test",
        model = "google/gemma-2-9b-it:free",
    )

    // -- ProviderConfig ---------------------------------------------------

    @Test
    fun configRequiresEndpointAndModel() {
        assertFalse(ProviderConfig().isConfigured, "empty config must not be usable")
        assertFalse(openRouter.copy(baseUrl = "").isConfigured)
        assertFalse(openRouter.copy(model = "").isConfigured)
        assertTrue(openRouter.isConfigured)
    }

    @Test
    fun apiKeyRequiredExceptForOllama() {
        // Keyless local Ollama is the one protocol that works without a secret.
        val ollama = ProviderConfig(
            kind = ProviderKind.OLLAMA,
            baseUrl = "http://localhost:11434",
            apiKey = "",
            model = "llama3.2",
        )
        assertTrue(ollama.isConfigured)
        assertFalse(openRouter.copy(apiKey = "").isConfigured)
        assertFalse(
            openRouter.copy(kind = ProviderKind.ANTHROPIC, apiKey = "").isConfigured
        )
    }

    @Test
    fun describeIsHonestWhenUnconfigured() {
        assertEquals("not configured", ProviderConfig().describe())
        assertTrue(openRouter.describe().contains("openrouter.ai"))
        assertTrue(openRouter.describe().contains("google/gemma-2-9b-it:free"))
    }

    @Test
    fun presetKeepsUserKeyAndOnlyFillsBlankModel() {
        val preset = ProviderPresets.ALL.first { it.label == "Anthropic" }
        val applied = ProviderConfig(apiKey = "sk-keep").withPreset(preset)
        assertEquals(ProviderKind.ANTHROPIC, applied.kind)
        assertEquals(preset.baseUrl, applied.baseUrl)
        assertEquals("sk-keep", applied.apiKey)
        assertEquals(preset.modelHint, applied.model, "blank model should take the hint")

        val existing = ProviderConfig(model = "my-model").withPreset(preset)
        assertEquals("my-model", existing.model, "a chosen model must not be overwritten")

        val fromPhone = ProviderConfig(kind = ProviderKind.ON_DEVICE, model = "Qwen3-0.6B").withPreset(preset)
        assertEquals(preset.modelHint, fromPhone.model, "an on-device model name must not carry over to a cloud endpoint")
    }

    @Test
    fun everyPresetIsSelfConsistent() {
        ProviderPresets.ALL.forEach { preset ->
            assertTrue(preset.baseUrl.isNotBlank(), "${preset.label} has no base url")
            assertTrue(
                preset.baseUrl.startsWith("http://") || preset.baseUrl.startsWith("https://"),
                "${preset.label} base url must carry a scheme",
            )
            assertTrue(preset.modelHint.isNotBlank(), "${preset.label} has no model hint")
            // A signup link only makes sense for a protocol that uses a key.
            // The converse does not hold: a LAN server is keyed by protocol
            // but has no page to obtain a key from.
            assertTrue(
                preset.keyUrl == null || preset.kind.needsApiKey,
                "${preset.label} offers a key signup for a keyless protocol",
            )
        }
    }

    @Test
    fun localEndpointsAreRecognised() {
        // This is what lets the UI explain a keyless llama.cpp box instead of
        // telling the user to go buy an API key.
        assertTrue(openRouter.copy(baseUrl = "http://localhost:8080").isLocalEndpoint)
        assertTrue(openRouter.copy(baseUrl = "http://127.0.0.1:11434").isLocalEndpoint)
        assertTrue(openRouter.copy(baseUrl = "http://192.168.1.10:8080").isLocalEndpoint)
        assertTrue(openRouter.copy(baseUrl = "http://10.0.0.5:8000").isLocalEndpoint)
        assertTrue(openRouter.copy(baseUrl = "http://172.16.4.2:8000").isLocalEndpoint)
        assertTrue(openRouter.copy(baseUrl = "http://172.31.255.1:8000").isLocalEndpoint)

        assertFalse(openRouter.isLocalEndpoint, "a public host is not local")
        assertFalse(openRouter.copy(baseUrl = "http://172.32.0.1").isLocalEndpoint)
        assertFalse(openRouter.copy(baseUrl = "http://11.0.0.1").isLocalEndpoint)
        assertFalse(openRouter.copy(baseUrl = "").isLocalEndpoint)
    }

    @Test
    fun hostIsExtractedFromEveryUrlShape() {
        assertEquals("openrouter.ai", openRouter.host)
        assertEquals("192.168.1.10", openRouter.copy(baseUrl = "http://192.168.1.10:8080").host)
        assertEquals("example.com", openRouter.copy(baseUrl = "https://user:pw@example.com/x/y").host)
        assertEquals("example.com", openRouter.copy(baseUrl = "https://EXAMPLE.com").host)
    }

    @Test
    fun keyHintExplainsTheLocalCaseAndStaysSilentForOllama() {
        assertNull(
            openRouter.copy(kind = ProviderKind.OLLAMA).keyHint,
            "Ollama needs no key, so there is nothing to explain",
        )
        assertTrue(openRouter.keyHint!!.contains("private storage"))
        assertTrue(
            openRouter.copy(baseUrl = "http://192.168.1.10:8080").keyHint!!.contains("own network"),
        )
    }

    // -- SettingsStore ----------------------------------------------------

    @Test
    fun emptyStorageYieldsDefaults() {
        val store = SettingsStore(MemoryTextStorage())
        assertEquals(ProviderConfig(), store.config.value)
    }

    @Test
    fun savedSettingsSurviveANewStore() {
        val storage = MemoryTextStorage()
        SettingsStore(storage).update(openRouter)

        val reloaded = SettingsStore(storage).config.value
        assertEquals(openRouter, reloaded)
    }

    @Test
    fun corruptJsonFallsBackToDefaultsInsteadOfThrowing() {
        // A settings file written by an older build must never brick startup.
        val storage = MemoryTextStorage(mapOf(SettingsStore.KEY to "{not valid json"))
        val store = SettingsStore(storage)
        assertEquals(ProviderConfig(), store.config.value)
    }

    @Test
    fun unknownFieldsAreIgnoredSoNewerBuildsStayReadable() {
        val storage = MemoryTextStorage(
            mapOf(
                SettingsStore.KEY to
                    """{"kind":"OLLAMA","baseUrl":"http://x:1","model":"m","futureField":42}"""
            )
        )
        val store = SettingsStore(storage)
        assertEquals("http://x:1", store.config.value.baseUrl)
        assertEquals(ProviderKind.OLLAMA, store.config.value.kind)
    }

    @Test
    fun clearResetsAndRemovesTheStoredValue() {
        val storage = MemoryTextStorage()
        val store = SettingsStore(storage)
        store.update(openRouter)
        assertNotNull(storage.readText(SettingsStore.KEY))

        store.clear()
        assertEquals(ProviderConfig(), store.config.value)
        assertNull(storage.readText(SettingsStore.KEY))
    }

    @Test
    fun reloadPicksUpChangesMadeToStorageElsewhere() {
        // The store caches in a StateFlow; reload() is how a second writer
        // (another screen, a restored backup) becomes visible.
        val storage = MemoryTextStorage()
        val store = SettingsStore(storage)
        store.update(openRouter)
        assertEquals(openRouter, store.config.value)

        val replacement = openRouter.copy(model = "changed-elsewhere")
        storage.writeText(
            SettingsStore.KEY,
            """{"kind":"OPENAI_COMPATIBLE","baseUrl":"https://openrouter.ai/api",""" +
                """"apiKey":"sk-test","model":"changed-elsewhere"}""",
        )
        assertEquals(openRouter.model, store.config.value.model, "not visible until reload")

        store.reload()
        assertEquals(replacement.model, store.config.value.model)
    }
}
