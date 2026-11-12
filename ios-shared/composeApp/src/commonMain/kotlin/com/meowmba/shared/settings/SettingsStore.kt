package com.meowmba.shared.settings

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Durable provider settings. Reads once at construction, then keeps a
 * [StateFlow] the UI observes so a saved change takes effect immediately.
 *
 * Corrupt or partial JSON falls back to defaults instead of throwing — a
 * settings file written by an older build must never brick the app.
 */
class SettingsStore(
    private val storage: TextStorage = DefaultTextStorage,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _config = MutableStateFlow(read())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    private fun read(): ProviderConfig {
        val raw = storage.readText(KEY) ?: return ProviderConfig()
        val parsed = runCatching { json.decodeFromString(ProviderConfig.serializer(), raw) }
            .getOrElse { return ProviderConfig() }
        val safe = parsed.safeForPersistence()
        // Migrate any old/corrupt Lion API-key field out of Documents storage
        // immediately; Lion tokens belong only in the platform secret store.
        if (safe != parsed) storage.writeText(KEY, json.encodeToString(ProviderConfig.serializer(), safe))
        return safe
    }

    /** Persists [config] and publishes it. */
    fun update(config: ProviderConfig) {
        val safe = config.safeForPersistence()
        _config.value = safe
        storage.writeText(KEY, json.encodeToString(ProviderConfig.serializer(), safe))
    }

    /** Re-reads from storage, discarding any in-memory change. */
    fun reload() {
        _config.value = read()
    }

    /** Removes the stored settings and resets to defaults. */
    fun clear() {
        storage.delete(KEY)
        _config.value = ProviderConfig()
    }

    companion object {
        const val KEY = "provider_config.json"
    }
}
