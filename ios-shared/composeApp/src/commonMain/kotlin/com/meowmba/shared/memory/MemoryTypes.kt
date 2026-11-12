package com.meowmba.shared.memory

/**
 * Platform-neutral memory data model — the shared replacement for the
 * Android app's Room entities (`MemoryEntity`, `MessageEntity`). Persistence
 * is platform-specific (Room on Android, SwiftData/GRDB or SQLDelight on
 * iOS); these types are the portable contract all recall logic operates on.
 */

enum class MemoryType { EPISODIC, SEMANTIC, PROJECT, PREFERENCE }

enum class Importance(val value: Int) { LOW(1), NORMAL(2), HIGH(3) }

@kotlinx.serialization.Serializable
data class MemoryItem(
    val id: Long = 0,
    val type: MemoryType = MemoryType.SEMANTIC,
    val content: String,
    val timestamp: Long,
    val importance: Importance = Importance.NORMAL,
    val tags: List<String> = emptyList(),
    /** Embedding blob (little-endian float bytes), nullable like Room's column. */
    val embedding: ByteArray? = null
)

enum class MessageRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val role: MessageRole = MessageRole.USER,
    val content: String,
    val timestamp: Long = 0,
    /** Who spoke this turn, when two people share the mic. */
    val speakerName: String? = null
)
