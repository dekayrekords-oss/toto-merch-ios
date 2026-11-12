package com.meowmba.shared.core

/**
 * Minimal RFC 3339 instant / ISO date parser replacing java.time (JVM-only)
 * for Hub metadata timestamps. Supports:
 *   "2026-07-12T03:04:05.000Z", "…+02:00", "2026-07-12" (UTC midnight).
 * Returns epoch milliseconds, or null for anything malformed.
 */
object IsoDates {

    private val PATTERN = Regex(
        """(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?\s*(Z|[+-]\d{2}:?\d{2})?)?"""
    )

    fun parse(value: String?): Long? {
        if (value == null) return null
        val m = PATTERN.matchEntire(value.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val hasTime = m.groupValues[4].isNotEmpty()
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        val second = m.groupValues[6].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val fraction = m.groupValues[7].let { if (it.isEmpty()) 0 else it.padEnd(3, '0').take(3).toInt() }
        val zone = m.groupValues[8]
        val offsetMillis = when {
            zone.isEmpty() || zone == "Z" -> 0
            else -> {
                val sign = if (zone.startsWith("-")) -1 else 1
                val digits = zone.removePrefix("+").removePrefix("-").replace(":", "")
                if (digits.length != 4) return null
                val oh = digits.substring(0, 2).toIntOrNull() ?: return null
                val om = digits.substring(2, 4).toIntOrNull() ?: return null
                if (oh > 23 || om > 59) return null
                sign * (oh * 3600 + om * 60) * 1000
            }
        }
        val days = daysFromCivil(year, month, day)
        val secondsUtc = days * 86400L + hour * 3600 + minute * 60 + second - offsetMillis / 1000
        return secondsUtc * 1000 + fraction
    }

    /** Howard Hinnant days-from-civil. */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
