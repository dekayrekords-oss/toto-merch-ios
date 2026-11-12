package com.meowmba.shared.core.flow

import com.meowmba.shared.memory.DateText
import com.meowmba.shared.weather.WeatherCondition
import com.meowmba.shared.weather.WeatherNow

/**
 * What the iPhone lets Flow know without Android's notification and usage access: the calendar,
 * Reminders, the weather and the battery. Items come and go with the facts; ids start with "ctx:".
 */
object FlowContext {
    data class Event(val startMs: Long, val title: String)
    data class Reminder(val dueMs: Long, val id: String, val title: String)

    fun items(
        now: Long, offset: Int,
        events: List<Event>, reminders: List<Reminder>,
        weather: WeatherNow?, batteryPercent: Int, charging: Boolean,
        dismissed: Set<String>,
    ): List<FlowItem> = buildList {
        // The next event you haven't dismissed: "Got it" on one meeting must not hide the one after it.
        events.filter { it.startMs >= now - 5 * 60_000L }.firstOrNull { "ctx:event:${it.startMs}:${it.title}" !in dismissed }?.let { e ->
            val mins = ((e.startMs - now) / 60_000L).toInt()
            if (mins <= 90) add(FlowItem(
                intentionId = "ctx:event:${e.startMs}:${e.title}", kind = FlowKind.WORK,
                headline = if (mins <= 0) "${e.title} is starting" else "${e.title} in ${mins} min",
                because = "On your calendar at ${DateText.timeLine(e.startMs, offset)}.",
                primary = FlowAction("dismiss", "Got it"), secondary = FlowAction("later", "Later"),
                urgency = if (mins <= 15) Urgency.URGENT else Urgency.WORTH_IT,
                certainty = Certainty.CONFIRMED, sources = listOf("cal"),
            ))
        }
        reminders.forEach { r ->
            val overdue = r.dueMs < now
            add(FlowItem(
                intentionId = "ctx:reminder:${r.id}", kind = FlowKind.ERRAND, headline = r.title,
                because = (if (overdue) "Reminder was due " else "Reminder due ") +
                    (if (DateText.weekdayMonthDay(r.dueMs, offset) == DateText.weekdayMonthDay(now, offset)) "" else DateText.weekdayMonthDay(r.dueMs, offset) + " ") +
                    "at ${DateText.timeLine(r.dueMs, offset)}.",
                primary = FlowAction("complete", "Done"), secondary = FlowAction("later", "Later"),
                urgency = if (overdue) Urgency.WORTH_IT else Urgency.USEFUL,
                certainty = Certainty.CONFIRMED, sources = listOf("rem"),
            ))
        }
        weather?.let { w ->
            val wet = w.condition in setOf(WeatherCondition.RAIN, WeatherCondition.DRIZZLE, WeatherCondition.THUNDERSTORM, WeatherCondition.SNOW)
            val headline = when {
                w.condition == WeatherCondition.THUNDERSTORM -> "Storms around — stay in if you can"
                wet -> "${w.condition.label} out — take a jacket"
                w.highF >= 90 -> "Hot today, up to ${w.highF}° — bring water"
                w.lowF <= 32 -> "Freezing tonight, down to ${w.lowF}°"
                else -> null
            }
            if (headline != null) add(FlowItem(
                intentionId = "ctx:weather:${DateText.weekdayMonthDay(now, offset)}:${w.condition}", kind = FlowKind.HOME,
                headline = headline, because = "It's ${w.temperatureF}° and ${w.condition.label.lowercase()}${w.place?.let { " in $it" } ?: ""}.",
                primary = FlowAction("dismiss", "Got it"), secondary = null,
                urgency = Urgency.USEFUL, certainty = Certainty.CONFIRMED, sources = listOf("wx"),
            ))
        }
        if (batteryPercent in 0..20 && !charging) add(FlowItem(
            intentionId = "ctx:battery:${batteryPercent / 10}", kind = FlowKind.OTHER,
            headline = "Battery at $batteryPercent%", because = "Plug in soon so Meow can keep listening.",
            primary = FlowAction("dismiss", "Got it"), secondary = null,
            urgency = if (batteryPercent <= 10) Urgency.WORTH_IT else Urgency.USEFUL,
            certainty = Certainty.CONFIRMED, sources = listOf("bat"),
        ))
    }.filter { it.intentionId !in dismissed }
}
