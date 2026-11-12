package com.meowmba.shared.weather

import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.memory.DateText

/**
 * Where the sun is, in the four buckets that actually change how the sky is painted.
 *
 * Binary day/night was not enough once the scene became ambient: a clear afternoon and a
 * clear 6pm are the same weather but a completely different colour, and running the day
 * palette until the sun physically sets made every evening look wrong.
 *
 * Derived from the local clock rather than from real sunrise/sunset tables. The error is at
 * most an hour or so seasonally, which is invisible in a background wash — and it costs no
 * network call, no location fix, and no extra dependency.
 */
enum class TimeOfDay {
    DAWN,
    DAY,
    DUSK,
    NIGHT;

    val isLight: Boolean get() = this == DAWN || this == DAY || this == DUSK

    companion object {
        fun fromHour(hour: Int): TimeOfDay = when (hour) {
            in 5..7 -> DAWN
            in 8..16 -> DAY
            in 17..19 -> DUSK
            else -> NIGHT
        }

        fun now(): TimeOfDay = fromHour(DateText.hourOfDay(PlatformTime.currentMillis(), PlatformTime.utcOffsetMinutes()))

        /**
         * Reconciles the clock with what the weather service says.
         *
         * Open-Meteo's `is_day` knows about real sunrise where the user actually is; the
         * clock does not. When they disagree we keep the clock's *flavour* but move it to
         * the correct side of the horizon, so a 5pm winter reading that is genuinely dark
         * becomes NIGHT instead of a bright DUSK.
         */
        fun reconcile(clock: TimeOfDay, isDay: Boolean): TimeOfDay = when {
            isDay && clock == NIGHT -> DAWN
            !isDay && clock == DAY -> DUSK
            !isDay && clock == DAWN -> NIGHT
            else -> clock
        }
    }
}
