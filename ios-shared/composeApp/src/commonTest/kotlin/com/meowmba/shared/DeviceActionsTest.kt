package com.meowmba.shared

import com.meowmba.shared.platform.DeviceAction
import com.meowmba.shared.platform.DeviceActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DeviceActionsTest {
    // Thursday 2026-09-24 22:00 in UTC-7.
    private val offset = -420
    private val now = DeviceActions.startOfDay(1_790_300_000_000L, offset) + 22 * 3_600_000L
    private fun parse(t: String) = DeviceActions.parse(t, now, offset)

    @Test fun remindersUnderstandTomorrowAtAndIn() {
        val r = assertIs<DeviceAction.Remind>(parse("remind me to water the plants tomorrow at 9am"))
        assertEquals("water the plants", r.title)
        assertEquals(DeviceActions.startOfDay(now, offset) + 86_400_000L + 9 * 3_600_000L, r.dueMs)
        val soon = assertIs<DeviceAction.Remind>(parse("Remind me to stretch in 20 minutes"))
        assertEquals(now + 20 * 60_000L, soon.dueMs)
        assertEquals(-1L, assertIs<DeviceAction.Remind>(parse("remind me to buy milk")).dueMs)
    }

    @Test fun calendarAndShortcutsAndDrafts() {
        val e = assertIs<DeviceAction.AddEvent>(parse("add dentist to my calendar tomorrow at 3:30 pm"))
        assertEquals("dentist", e.title)
        assertEquals(DeviceActions.startOfDay(now, offset) + 86_400_000L + (15 * 60 + 30) * 60_000L, e.startMs)
        assertEquals(DeviceAction.RunShortcut("Good Morning"), parse("run shortcut \"Good Morning\""))
        assertEquals(DeviceAction.Email("sam@example.com", "dinner", "see you at 7"), parse("email sam@example.com about dinner saying see you at 7"))
        assertEquals(DeviceAction.ReadCalendar(tomorrow = true), parse("what's on my calendar tomorrow?"))
    }

    @Test fun ordinaryChatIsNotACommand() {
        assertNull(parse("what's the best way to open a jar"))
        assertNull(parse("tell me about reminders"))
    }
}
