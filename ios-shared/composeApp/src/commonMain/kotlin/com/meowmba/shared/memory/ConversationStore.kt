package com.meowmba.shared.memory

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire form of a stored turn. Kept separate so [ChatMessage] stays a plain ported type. */
@Serializable
private data class StoredTurn(
    val role: String,
    val content: String,
    val timestamp: Long = 0,
    val speakerName: String? = null,
)

/**
 * Durable conversation history.
 *
 * Streaming writes are deliberately *not* persisted per token: [append] saves
 * immediately (a user turn is never lost), [updateLast] mutates memory only,
 * and the caller calls [flush] once a turn finishes. Without that split a
 * 500-token reply would mean 500 filesystem writes.
 */
class ConversationStore(
    private val storage: TextStorage = DefaultTextStorage,
    /** Hard cap on retained turns; oldest are dropped. */
    private val limit: Int = 200,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow(restore())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var dirty = false

    private fun restore(): List<ChatMessage> {
        val raw = storage.readText(KEY) ?: return emptyList()
        val turns = runCatching { json.decodeFromString(ListSerializer, raw) }
            .getOrElse { return emptyList() }
        return turns.map { it.toMessage() }
    }

    /** Adds [message] to the window and persists immediately. */
    fun append(message: ChatMessage) {
        val next = (_messages.value + message).takeLast(limit)
        _messages.value = next
        dirty = true
        flush()
    }

    /**
     * Replaces the text of the newest message in memory only. Used while a
     * reply streams in. A no-op when the window is empty.
     */
    fun updateLast(text: String) {
        val current = _messages.value
        if (current.isEmpty()) return
        _messages.value = current.dropLast(1) + current.last().copy(content = text)
        dirty = true
    }

    /** Replaces the newest message entirely (e.g. to attach a timestamp). */
    fun replaceLast(message: ChatMessage) {
        val current = _messages.value
        if (current.isEmpty()) {
            append(message)
            return
        }
        _messages.value = current.dropLast(1) + message
        dirty = true
    }

    /** Deletes one turn (the chat sheet's per-message delete) and persists. */
    fun removeAt(index: Int) {
        val current = _messages.value
        if (index !in current.indices) return
        _messages.value = current.toMutableList().apply { removeAt(index) }
        dirty = true
        flush()
    }

    /** Writes the in-memory window to storage when it has pending changes. */
    fun flush() {
        if (!dirty) return
        val payload = _messages.value.map { StoredTurn(it.role.name, it.content, it.timestamp, it.speakerName) }
        storage.writeText(KEY, json.encodeToString(ListSerializer, payload))
        dirty = false
    }

    /** Empties the window and removes the stored history. */
    fun clear() {
        _messages.value = emptyList()
        dirty = false
        storage.delete(KEY)
    }

    /**
     * The trailing [count] turns, formatted for PromptBuilder.
     *
     * Call this **before** appending the turn being answered — PromptBuilder
     * appends the current user input itself, so a history that already
     * contained it would ask the model the same question twice.
     */
    fun recentForPrompt(count: Int = 12): List<ChatMessage> =
        _messages.value.takeLast(count)

    private fun StoredTurn.toMessage() = ChatMessage(
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        timestamp = timestamp,
        speakerName = speakerName,
    )

    companion object {
        const val KEY = "conversation.json"
        private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(StoredTurn.serializer())
    }
}
