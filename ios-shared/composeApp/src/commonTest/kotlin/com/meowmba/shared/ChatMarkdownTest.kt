package com.meowmba.shared

import com.meowmba.shared.ui.ChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMarkdownTest {
    @Test
    fun markersBecomeStylesNotText() {
        val r = ChatMarkdown.render("## Today\n1. **Design review at 2:30** — finish the *slides*\n- dry cleaning\nuse `git pull`")
        assertEquals("Today\n1. Design review at 2:30 — finish the slides\n• dry cleaning\nuse git pull", r.text)
        assertTrue(r.spanStyles.size >= 4)
    }

    @Test
    fun ordinaryAsterisksAndUnderscoresStay() {
        assertEquals("2 * 3 = 6 and snake_case_name", ChatMarkdown.render("2 * 3 = 6 and snake_case_name").text)
    }
}

class FlowContextNextEventTest {
    @Test
    fun dismissingTheNextMeetingShowsTheOneAfter() {
        val now = 1_000_000_000L
        val events = listOf(com.meowmba.shared.core.flow.FlowContext.Event(now + 10 * 60_000L, "Standup"),
            com.meowmba.shared.core.flow.FlowContext.Event(now + 60 * 60_000L, "Design review"))
        val items = com.meowmba.shared.core.flow.FlowContext.items(now, 0, events, emptyList(), null, 80, true,
            setOf("ctx:event:${now + 10 * 60_000L}:Standup"))
        assertEquals("Design review in 60 min", items.single().headline)
    }
}

class GuessKindTest {
    @Test
    fun keptNotesLandOnTheRightDeskObject() {

        assertEquals(com.meowmba.shared.core.flow.FlowKind.PEOPLE, com.meowmba.shared.ui.guessKind("Call Mom back about Sunday"))
        assertEquals(com.meowmba.shared.core.flow.FlowKind.MONEY, com.meowmba.shared.ui.guessKind("Send invoice to Northwind, $1,200"))
        assertEquals(com.meowmba.shared.core.flow.FlowKind.ERRAND, com.meowmba.shared.ui.guessKind("Pick up Maya from soccer at 5:30"))
        assertEquals(com.meowmba.shared.core.flow.FlowKind.OTHER, com.meowmba.shared.ui.guessKind("Idea: a podcast about old synths"))
    }
}
