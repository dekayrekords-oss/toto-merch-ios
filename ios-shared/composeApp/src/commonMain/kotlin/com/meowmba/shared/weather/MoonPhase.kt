package com.meowmba.shared.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Where the moon is in its cycle, right now.
 *
 * Ported from `core/weather/MoonPhase.kt` verbatim — it was already pure
 * Kotlin (`kotlin.math` only), so there is nothing platform-specific to
 * replace. One deliberate change: the `fraction()` default argument used
 * `System.currentTimeMillis()`, which does not exist in common Kotlin, so
 * callers pass the instant explicitly (usually `PlatformTime.currentMillis()`).
 *
 * A mean-synodic calculation: time since a known new moon, modulo the average
 * lunation. Real lunations vary by a few hours either side of the mean because
 * the moon's orbit is elliptical, so this can be off by up to ~half a day near
 * the quarters. For deciding how much of a drawn moon to light, that is
 * invisible — and the alternative (a full ELP/lunar theory port, or a network
 * call) is a lot of machinery to move a terminator by a pixel.
 */
object MoonPhase {

    /** Mean synodic month — new moon to new moon. */
    private const val SYNODIC_DAYS = 29.530588853

    /** Reference new moon: 2000-01-06 18:14 UTC, the standard epoch for this calculation. */
    private const val REFERENCE_NEW_MOON_MS = 947_182_440_000L

    private const val DAY_MS = 86_400_000.0

    /**
     * Age of the moon as a fraction of one cycle.
     *
     * `0.0` new, `0.25` first quarter, `0.5` full, `0.75` last quarter.
     */
    fun fraction(nowMs: Long): Float {
        val days = (nowMs - REFERENCE_NEW_MOON_MS) / DAY_MS
        val cycles = days / SYNODIC_DAYS
        return (cycles - kotlin.math.floor(cycles)).toFloat()
    }

    /** Lit portion of the disc, 0..1. Follows the terminator, not the phase, hence the cos. */
    fun illumination(fraction: Float): Float =
        ((1.0 - cos(2.0 * PI * fraction)) / 2.0).toFloat()

    /** True while the lit limb is growing — which side of the disc is lit. */
    fun isWaxing(fraction: Float): Boolean = fraction < 0.5f

    fun label(fraction: Float): String = when {
        fraction < 0.03f || fraction >= 0.97f -> "New moon"
        fraction < 0.22f -> "Waxing crescent"
        fraction < 0.28f -> "First quarter"
        fraction < 0.47f -> "Waxing gibbous"
        fraction < 0.53f -> "Full moon"
        fraction < 0.72f -> "Waning gibbous"
        fraction < 0.78f -> "Last quarter"
        else -> "Waning crescent"
    }

    fun emoji(fraction: Float): String = when {
        fraction < 0.03f || fraction >= 0.97f -> "🌑"
        fraction < 0.22f -> "🌒"
        fraction < 0.28f -> "🌓"
        fraction < 0.47f -> "🌔"
        fraction < 0.53f -> "🌕"
        fraction < 0.72f -> "🌖"
        fraction < 0.78f -> "🌗"
        else -> "🌘"
    }

    /**
     * Half-width of the terminator ellipse as a signed fraction of the disc radius.
     *
     * Sign says which way the terminator bulges: away from the lit limb before the quarter
     * (crescent) and toward it after (gibbous). Magnitude is `|cos|`, which is what makes
     * the terminator flatten to a straight line exactly at the quarters.
     */
    fun terminatorBulge(fraction: Float): Float = cos(2.0 * PI * fraction).toFloat()

    /** How far the moon is from new, 0..1 — used to fade the glow out around new moon. */
    fun brightness(fraction: Float): Float = abs(illumination(fraction))
}
