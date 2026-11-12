package com.meowmba.shared.memory

import com.meowmba.shared.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MemorySnapshot(val items: List<MemoryItem> = emptyList(), val paused: Boolean = false,
    val retentionDays: Int = 90, val nextId: Long = 1)

/** Durable user-controlled memory. Only explicit saves enter this store. */
class LongTermMemoryStore(private val storage: TextStorage = DefaultTextStorage,
    private val clock: () -> Long = PlatformTime::currentMillis,
    private val embedder: EmbeddingInterface = LocalHashEmbedder()) {
    private val lock = PlatformLock()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun restore(): MemorySnapshot {
        val raw = storage.readText(KEY) ?: return MemorySnapshot()
        return try { json.decodeFromString<MemorySnapshot>(raw) } catch (_: Exception) {
            // Retain the damaged bytes for recovery before starting an empty store.
            storage.writeTextAtomic("long_term_memory.corrupt.json", raw)
            MemorySnapshot()
        }
    }
    private val current = MutableStateFlow(restore())
    val state = current.asStateFlow()
    init { expire() }
    private fun commit(value: MemorySnapshot) {
        storage.writeTextAtomic(KEY, json.encodeToString(MemorySnapshot.serializer(), value))
        current.value = value
    }
    fun expire() = lock.withLock {
        val s = current.value
        if (s.retentionDays > 0) {
            val kept = s.items.filter { it.timestamp >= clock() - s.retentionDays * 86_400_000L }
            if (kept.size != s.items.size) commit(s.copy(items = kept))
        }
    }
    fun candidates(): List<MemoryItem> = lock.withLock { expire(); current.value.items }
    suspend fun remember(text: String, type: MemoryType = MemoryType.SEMANTIC): MemoryItem? {
        val content = text.trim().take(8_000)
        if (content.isEmpty() || current.value.paused) return null
        val embedding = VectorCodec.encode(embedder.getEmbedding(content))
        return lock.withLock {
            val s = current.value
            if (s.paused) null else {
                val duplicate = s.items.firstOrNull { it.content.equals(content, ignoreCase = true) && it.type == type }
                if (duplicate != null) duplicate else {
                    val item = MemoryItem(s.nextId, type, content, clock(), embedding = embedding)
                    commit(s.copy(items = s.items + item, nextId = s.nextId + 1)); item
                }
            }
        }
    }
    fun delete(id: Long) = lock.withLock { commit(current.value.copy(items = current.value.items.filterNot { it.id == id })) }
    fun clear() = lock.withLock { commit(current.value.copy(items = emptyList())) }
    fun setPaused(value: Boolean) = lock.withLock { commit(current.value.copy(paused = value)) }
    fun setRetention(days: Int) = lock.withLock {
        require(days in listOf(0, 30, 90, 365))
        commit(current.value.copy(retentionDays = days)); expire()
    }
    fun export(): String = lock.withLock { expire(); json.encodeToString(MemorySnapshot.serializer(), current.value) }
    companion object { const val KEY = "long_term_memory.json" }
}
