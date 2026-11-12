package com.meowmba.shared.ui

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A durable, user-authored Flow stream for the iOS home while policy sources are ported. */
@Serializable
internal data class FlowNote(
    val id: String,
    val text: String,
    val createdAt: Long,
    val done: Boolean = false,
    val snoozedUntil: Long = 0,
    /** Android FlowComments: the person's last note for Meow on this item. */
    val comment: String? = null,
    val commentAt: Long = 0,
)

internal class HomeFlowStore(private val storage: TextStorage = DefaultTextStorage) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _notes = MutableStateFlow(read())
    val notes: StateFlow<List<FlowNote>> = _notes

    fun keep(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        save(listOf(FlowNote(PlatformId.shortId(), clean, PlatformTime.currentMillis())) + _notes.value)
    }

    fun comment(id: String, text: String) =
        save(_notes.value.map { if (it.id == id) it.copy(comment = text.take(1000), commentAt = PlatformTime.currentMillis()) else it })

    fun done(id: String) = save(_notes.value.map { if (it.id == id) it.copy(done = true) else it })

    fun later(id: String) {
        val until = PlatformTime.currentMillis() + 60 * 60_000L
        save(_notes.value.map { if (it.id == id) it.copy(snoozedUntil = until) else it })
    }

    private fun read(): List<FlowNote> {
        val raw = storage.readText(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FlowNote>>(raw) }.getOrDefault(emptyList())
    }

    private fun save(notes: List<FlowNote>) {
        storage.writeTextAtomic(KEY, json.encodeToString(notes))
        _notes.value = notes
    }

    companion object { const val KEY = "home_flow_notes.json" }
}
