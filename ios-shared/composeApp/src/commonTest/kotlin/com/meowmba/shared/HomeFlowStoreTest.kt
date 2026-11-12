package com.meowmba.shared

import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.ui.HomeFlowStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeFlowStoreTest {
    @Test
    fun keptNoteAndCompletionSurviveReload() {
        val storage = MemoryTextStorage()
        val first = HomeFlowStore(storage)
        first.keep("  Call Sam  ")
        val note = first.notes.value.single()
        assertEquals("Call Sam", note.text)
        assertEquals(note, HomeFlowStore(storage).notes.value.single())

        first.done(note.id)
        assertTrue(HomeFlowStore(storage).notes.value.single().done)
    }
}
