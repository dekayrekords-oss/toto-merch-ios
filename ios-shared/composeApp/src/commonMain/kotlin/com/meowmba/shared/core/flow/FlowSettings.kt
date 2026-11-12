package com.meowmba.shared.core.flow


enum class EveningLook(val key: String) {
    AUTO("auto"), DAY("day"), NIGHT("night");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * User controls for Meow Flow (owner decisions D3, D4, D5, D7, D8).
 * Defaults match docs/MEOW_FLOW_PASS_DOWN.md §3.
 */
data class FlowSettings(
    val voice: Boolean = true,
    val notifyOtherApps: Boolean = true,
    val maxPerDay: Int = 3,
    val minGapMinutes: Int = 60,
    val minUrgency: Urgency = Urgency.WORTH_IT,
    val detectWorkEnd: Boolean = true,
    val theme: String = FlowThemes.DEFAULT_KEY,
    /** ARGB accent override, or null for the theme's own accent. */
    val accent: Int? = null,
    val eveningLook: EveningLook = EveningLook.AUTO,
    val allowJev: Boolean = false,
) {
    /** Clamp anything a stale or hand-edited preference could hold. */
    fun normalized(): FlowSettings = copy(
        maxPerDay = maxPerDay.coerceIn(0, MAX_PER_DAY_LIMIT),
        minGapMinutes = GAP_CHOICES.minByOrNull { kotlin.math.abs(it - minGapMinutes) } ?: 60,
        minUrgency = if (minUrgency == Urgency.NOT_NOW) Urgency.USEFUL else minUrgency,
        theme = if (FlowThemes.has(theme)) theme else FlowThemes.DEFAULT_KEY,
    )

    companion object {
        const val MAX_PER_DAY_LIMIT = 10
        val GAP_CHOICES = listOf(15, 30, 60, 120)
    }
}

/** Minimal key-value seam so settings persistence is testable without Android. */
interface FlowKeyValue {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String?>)
}

/** Reads/writes [FlowSettings] under the `flow.*` keys listed in the master plan §5.4. */
class FlowSettingsStore(private val kv: FlowKeyValue) {

    fun load(): FlowSettings {
        val d = FlowSettings()
        return FlowSettings(
            voice = kv.getString(K_VOICE)?.toBooleanStrictOrNull() ?: d.voice,
            notifyOtherApps = kv.getString(K_NOTIFY)?.toBooleanStrictOrNull() ?: d.notifyOtherApps,
            maxPerDay = kv.getString(K_MAX)?.toIntOrNull() ?: d.maxPerDay,
            minGapMinutes = kv.getString(K_GAP)?.toIntOrNull() ?: d.minGapMinutes,
            minUrgency = kv.getString(K_URGENCY)?.let { v -> Urgency.entries.firstOrNull { it.name == v } } ?: d.minUrgency,
            detectWorkEnd = kv.getString(K_WORK_END)?.toBooleanStrictOrNull() ?: d.detectWorkEnd,
            theme = kv.getString(K_THEME) ?: d.theme,
            accent = kv.getString(K_ACCENT)?.let { parseHex(it) },
            eveningLook = EveningLook.fromKey(kv.getString(K_EVENING)),
            allowJev = kv.getString(K_JEV)?.toBooleanStrictOrNull() ?: d.allowJev,
        ).normalized()
    }

    fun save(settings: FlowSettings) {
        val s = settings.normalized()
        kv.putAll(
            mapOf(
                K_VOICE to s.voice.toString(),
                K_NOTIFY to s.notifyOtherApps.toString(),
                K_MAX to s.maxPerDay.toString(),
                K_GAP to s.minGapMinutes.toString(),
                K_URGENCY to s.minUrgency.name,
                K_WORK_END to s.detectWorkEnd.toString(),
                K_THEME to s.theme,
                K_ACCENT to s.accent?.let { toHex(it) },
                K_EVENING to s.eveningLook.key,
                K_JEV to s.allowJev.toString(),
            ),
        )
    }

    companion object {
        const val PREFS_NAME = "meow_flow"
        const val K_VOICE = "flow.voice"
        const val K_NOTIFY = "flow.notify_other_apps"
        const val K_MAX = "flow.max_per_day"
        const val K_GAP = "flow.min_gap_min"
        const val K_URGENCY = "flow.min_urgency"
        const val K_WORK_END = "flow.detect_work_end"
        const val K_THEME = "flow.theme"
        const val K_ACCENT = "flow.accent"
        const val K_EVENING = "flow.evening_look"
        const val K_JEV = "flow.allow_jev"

        fun toHex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

        fun parseHex(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6 || hex.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
            return (0xFF000000.toInt()) or hex.toInt(16)
        }
    }
}
