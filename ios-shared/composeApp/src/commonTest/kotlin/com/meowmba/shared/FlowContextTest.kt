package com.meowmba.shared

import com.meowmba.shared.core.flow.FlowContext
import com.meowmba.shared.core.flow.Urgency
import com.meowmba.shared.weather.WeatherCondition
import com.meowmba.shared.weather.WeatherNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowContextTest {
    private val now = 1_790_000_000_000L
    private fun items(
        events: List<FlowContext.Event> = emptyList(), reminders: List<FlowContext.Reminder> = emptyList(),
        weather: WeatherNow? = null, battery: Int = 80, charging: Boolean = false, dismissed: Set<String> = emptySet(),
    ) = FlowContext.items(now, 0, events, reminders, weather, battery, charging, dismissed)

    @Test fun meetingSoonIsUrgent() {
        val it = items(events = listOf(FlowContext.Event(now + 10 * 60_000L, "Standup"))).single()
        assertEquals("Standup in 10 min", it.headline)
        assertEquals(Urgency.URGENT, it.urgency)
    }

    @Test fun farMeetingIsIgnored() =
        assertTrue(items(events = listOf(FlowContext.Event(now + 3 * 3_600_000L, "Later"))).isEmpty())

    @Test fun overdueReminderCompletes() {
        val it = items(reminders = listOf(FlowContext.Reminder(now - 60_000L, "abc", "Call mom"))).single()
        assertEquals("ctx:reminder:abc", it.intentionId)
        assertEquals("complete", it.primary.id)
        assertEquals(Urgency.WORTH_IT, it.urgency)
    }

    @Test fun rainAndLowBattery() {
        val wx = WeatherNow(WeatherCondition.RAIN, 55, 53, 60, 48, 90, 8, true, "Seattle")
        val found = items(weather = wx, battery = 9)
        assertEquals(2, found.size)
        assertTrue(found.any { it.headline.contains("jacket") })
        assertTrue(found.none { items(battery = 9, charging = true).isNotEmpty() })
    }

    @Test fun dismissedStaysGone() {
        val id = items(reminders = listOf(FlowContext.Reminder(now, "x", "Pay rent"))).single().intentionId
        assertTrue(items(reminders = listOf(FlowContext.Reminder(now, "x", "Pay rent")), dismissed = setOf(id)).isEmpty())
    }
}

class NeedleRoutingTest {
    private val now = 1_790_000_000_000L
    private val real = """{"type":"call","success":true,"function_calls":[{"name":"send_text","arguments":{"to":"5550100","body":"running late"}}],"confidence":0.8703}"""

    @Test fun deviceOutputBecomesText() =
        assertEquals(com.meowmba.shared.platform.DeviceAction.Text("5550100", "running late"),
            com.meowmba.shared.platform.Needle.toAction(real, now, 0))

    @Test fun lowConfidenceFallsBackToChat() =
        assertEquals(null, com.meowmba.shared.platform.Needle.toAction(real.replace("0.8703", "0.31"), now, 0))

    @Test fun emptyCallsMeanChat() =
        assertEquals(null, com.meowmba.shared.platform.Needle.toAction("""{"type":"refuse","function_calls":[],"confidence":0.9}""", now, 0))

    @Test fun gateSkipsChitChat() {
        assertTrue(com.meowmba.shared.platform.Needle.mightBeCommand("ping 5550100 that I'm late"))
        assertTrue(!com.meowmba.shared.platform.Needle.mightBeCommand("how are you today?"))
    }
}
