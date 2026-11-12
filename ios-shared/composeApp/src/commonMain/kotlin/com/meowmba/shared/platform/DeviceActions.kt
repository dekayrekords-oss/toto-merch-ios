package com.meowmba.shared.platform

import com.meowmba.shared.memory.DateText

/** Calendar and Reminders (iOS: EventKit). Times are epoch milliseconds; -1 = no time. */
interface CalendarDriver {
    fun hasAccess(): Boolean
    fun requestAccess(onResult: (granted: Boolean, reason: String?) -> Unit)
    fun addReminder(title: String, dueMs: Long, onDone: (ok: Boolean, message: String) -> Unit)
    fun addEvent(title: String, startMs: Long, minutes: Int, onDone: (ok: Boolean, message: String) -> Unit)
    /** Events from [fromMs] to [toMs] as "startMs|title" lines, soonest first. */
    fun events(fromMs: Long, toMs: Long, onResult: (List<String>) -> Unit)
    /** Open reminders due before [untilMs] as "dueMs|id|title" lines, soonest first. */
    fun reminders(untilMs: Long, onResult: (List<String>) -> Unit)
    fun completeReminder(id: String, onDone: (ok: Boolean) -> Unit)
    /** Cancels only the occurrence starting at [startMs] titled [title]; reports what happened. */
    fun cancelEvent(startMs: Long, title: String, onDone: (ok: Boolean, message: String) -> Unit)
}

/** Battery (iOS: UIDevice). -1 when unknown. */
interface DeviceStatusDriver {
    fun batteryPercent(): Int
    fun charging(): Boolean
    /** Calls back on the main thread whenever charging starts or stops. */
    fun observeCharging(onChange: (charging: Boolean) -> Unit)
}

/**
 * Things Meow can do on an iPhone when asked in chat or by voice. iOS lets an app open other apps,
 * run the user's Shortcuts, draft (never send) mail and messages, and use Calendar and Reminders
 * with permission. It does not let an app read other apps' mail or drive other apps' screens.
 */
sealed interface DeviceAction {
    data class OpenApp(val app: IosApp) : DeviceAction
    data class RunShortcut(val name: String) : DeviceAction
    data class Email(val to: String, val subject: String, val body: String) : DeviceAction
    data class Text(val to: String, val body: String) : DeviceAction
    data class Remind(val title: String, val dueMs: Long) : DeviceAction
    data class AddEvent(val title: String, val startMs: Long) : DeviceAction
    data class ReadCalendar(val tomorrow: Boolean) : DeviceAction
}

object DeviceActions {
    fun parse(text: String, now: Long, offsetMinutes: Int): DeviceAction? {
        val t = text.trim().trimEnd('.', '!', '?')
        Regex("^(?:please\\s+)?run (?:my |the )?shortcut\\s+[\"“]?(.+?)[\"”]?$", RegexOption.IGNORE_CASE).find(t)?.let {
            return DeviceAction.RunShortcut(it.groupValues[1].trim())
        }
        Regex("^(?:please\\s+)?remind me (?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val (title, due) = splitWhen(it.groupValues[1], now, offsetMinutes)
            return DeviceAction.Remind(title, due)
        }
        Regex("^(?:please\\s+)?(?:add|put|schedule)\\s+(.+?)\\s+(?:to|on|in) my calendar(.*)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val (title, start) = splitWhen(it.groupValues[1] + " " + it.groupValues[2], now, offsetMinutes)
            return DeviceAction.AddEvent(title, if (start > 0) start else nextHour(now))
        }
        Regex("^(?:what(?:'s| is)|whats) (?:on )?my (?:calendar|schedule|agenda)(?: for)?\\s*(today|tomorrow)?.*$", RegexOption.IGNORE_CASE).find(t)?.let {
            return DeviceAction.ReadCalendar(it.groupValues[1].equals("tomorrow", true))
        }
        Regex("^(?:please\\s+)?(?:send an? )?e-?mail (?:to\\s+)?(\\S+@\\S+)(?:\\s+(?:about|re|subject)\\s+(.+?))?(?:\\s+(?:saying|that says|:)\\s+(.+))?$", RegexOption.IGNORE_CASE).find(t)?.let {
            return DeviceAction.Email(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3].trim())
        }
        Regex("^(?:please\\s+)?(?:send a )?(?:text|message|imessage) (?:to\\s+)?([+\\d][\\d\\s()-]{5,})\\s*(?:saying|that says|:)?\\s*(.*)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return DeviceAction.Text(it.groupValues[1].filter { c -> c.isDigit() || c == '+' }, it.groupValues[2].trim())
        }
        IosApps.match(t)?.let { return DeviceAction.OpenApp(it) }
        return null
    }

    /** "call mom tomorrow at 5pm" → ("call mom", time). Understands at/in/tomorrow/tonight/this evening. */
    internal fun splitWhen(phrase: String, now: Long, offset: Int): Pair<String, Long> {
        var title = phrase.trim()
        var dayShift = 0
        var minuteOfDay: Int? = null
        var relative: Long? = null
        Regex("\\bin (\\d+) (minute|minutes|min|mins|hour|hours|hr|hrs)\\b", RegexOption.IGNORE_CASE).find(title)?.let {
            val n = it.groupValues[1].toLong()
            relative = n * if (it.groupValues[2].startsWith("h", true)) 3_600_000L else 60_000L
            title = title.replace(it.value, " ")
        }
        if (Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) { dayShift = 1; title = title.replace(Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE), " ") }
        Regex("\\b(tonight|this evening)\\b", RegexOption.IGNORE_CASE).find(title)?.let { minuteOfDay = 20 * 60; title = title.replace(it.value, " ") }
        Regex("\\bthis afternoon\\b", RegexOption.IGNORE_CASE).find(title)?.let { minuteOfDay = 15 * 60; title = title.replace(it.value, " ") }
        Regex("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b|\\bat\\s+(\\d{1,2}):(\\d{2})\\b", RegexOption.IGNORE_CASE).find(title)?.let { m ->
            var h = (m.groupValues[1].ifBlank { m.groupValues[4] }).toInt()
            val min = (m.groupValues[2].ifBlank { m.groupValues[5] }).ifBlank { "0" }.toInt()
            val ampm = m.groupValues[3].lowercase()
            if (ampm.startsWith("p") && h < 12) h += 12
            if (ampm.startsWith("a") && h == 12) h = 0
            minuteOfDay = h * 60 + min
            title = title.replace(m.value, " ")
        }
        title = title.replace(Regex("\\s+(?:at|on|for)\\s*$", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
        relative?.let { return title to now + it }
        val mod = minuteOfDay ?: return title to (if (dayShift > 0) startOfDay(now, offset) + 86_400_000L + 9 * 3_600_000L else -1L)
        var at = startOfDay(now, offset) + dayShift * 86_400_000L + mod * 60_000L
        if (dayShift == 0 && at < now) at += 86_400_000L
        return title to at
    }

    fun startOfDay(now: Long, offset: Int): Long {
        val local = now + offset * 60_000L
        return local - Math.floorMod(local, 86_400_000L) - offset * 60_000L
    }

    private fun nextHour(now: Long) = (now / 3_600_000L + 1) * 3_600_000L

    fun describe(ms: Long, offset: Int): String =
        DateText.weekdayMonthDay(ms, offset) + " at " + DateText.timeLine(ms, offset)

    private object Math { fun floorMod(a: Long, b: Long) = ((a % b) + b) % b }
}
