package com.meowmba.shared

import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.ConversationStore
import com.meowmba.shared.memory.MessageRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Durable conversation history: persistence timing, capping and restore. */
class ConversationStoreTest {

    private fun turn(role: MessageRole, text: String, at: Long = 0) =
        ChatMessage(role = role, content = text, timestamp = at)

    @Test
    fun startsEmptyWhenNothingIsStored() {
        val store = ConversationStore(MemoryTextStorage())
        assertEquals(emptyList(), store.messages.value)
    }

    @Test
    fun aUserTurnIsPersistedImmediately() {
        val storage = MemoryTextStorage()
        val store = ConversationStore(storage)

        store.append(turn(MessageRole.USER, "hello"))

        // No flush() call: a user turn must never be lost.
        assertTrue(
            storage.readText(ConversationStore.KEY)!!.contains("hello"),
            "user turns must reach storage without an explicit flush",
        )
    }

    @Test
    fun streamingUpdatesDoNotTouchStorageUntilFlushed() {
        val storage = MemoryTextStorage()
        val store = ConversationStore(storage)
        store.append(turn(MessageRole.USER, "hi"))
        store.append(turn(MessageRole.ASSISTANT, ""))

        val before = storage.readText(ConversationStore.KEY)
        repeat(50) { store.updateLast("partial-$it") }
        assertEquals(
            before,
            storage.readText(ConversationStore.KEY),
            "per-token updates must not write to storage",
        )

        store.flush()
        assertTrue(storage.readText(ConversationStore.KEY)!!.contains("partial-49"))
    }

    @Test
    fun inMemoryViewUpdatesDuringStreaming() {
        val store = ConversationStore(MemoryTextStorage())
        store.append(turn(MessageRole.USER, "hi"))
        store.append(turn(MessageRole.ASSISTANT, ""))

        store.updateLast("Hel")
        assertEquals("Hel", store.messages.value.last().content)
        store.updateLast("Hello")
        assertEquals("Hello", store.messages.value.last().content)
        assertEquals(2, store.messages.value.size, "streaming must not add bubbles")
    }

    @Test
    fun updateLastOnEmptyWindowIsANoOp() {
        val store = ConversationStore(MemoryTextStorage())
        store.updateLast("nothing to update")
        assertEquals(emptyList(), store.messages.value)
    }

    @Test
    fun windowIsCappedAndKeepsTheNewestTurns() {
        val store = ConversationStore(MemoryTextStorage(), limit = 3)
        repeat(6) { store.append(turn(MessageRole.USER, "turn-$it")) }

        assertEquals(3, store.messages.value.size)
        assertEquals(
            listOf("turn-3", "turn-4", "turn-5"),
            store.messages.value.map { it.content },
        )
    }

    @Test
    fun historySurvivesANewStoreOverTheSameStorage() {
        val storage = MemoryTextStorage()
        ConversationStore(storage).apply {
            append(turn(MessageRole.USER, "first"))
            append(turn(MessageRole.ASSISTANT, "second"))
        }

        val restored = ConversationStore(storage).messages.value
        assertEquals(listOf("first", "second"), restored.map { it.content })
        assertEquals(MessageRole.ASSISTANT, restored.last().role)
    }

    @Test
    fun recentForPromptReturnsTheNewestTurnsIncludingTheLast() {
        val store = ConversationStore(MemoryTextStorage())
        repeat(5) { store.append(turn(MessageRole.USER, "m$it")) }

        // The caller must capture history BEFORE appending the turn being
        // answered; the newest stored turn is therefore included here.
        assertEquals(
            listOf("m3", "m4"),
            store.recentForPrompt(count = 2).map { it.content },
        )
    }

    @Test
    fun clearEmptiesMemoryAndStorage() {
        val storage = MemoryTextStorage()
        val store = ConversationStore(storage)
        store.append(turn(MessageRole.USER, "bye"))
        store.clear()

        assertEquals(emptyList(), store.messages.value)
        assertNull(storage.readText(ConversationStore.KEY))
    }

    @Test
    fun corruptHistoryDegradesToEmptyRatherThanThrowing() {
        val storage = MemoryTextStorage(
            mapOf(ConversationStore.KEY to "[[[ definitely not json")
        )
        assertEquals(emptyList(), ConversationStore(storage).messages.value)
    }

    @Test
    fun unknownRoleInStoredHistoryFallsBackToUser() {
        val storage = MemoryTextStorage(
            mapOf(
                ConversationStore.KEY to
                    """[{"role":"ROBOT","content":"beep","timestamp":1}]"""
            )
        )
        val restored = ConversationStore(storage).messages.value.single()
        assertEquals(MessageRole.USER, restored.role)
        assertEquals("beep", restored.content)
    }
}
