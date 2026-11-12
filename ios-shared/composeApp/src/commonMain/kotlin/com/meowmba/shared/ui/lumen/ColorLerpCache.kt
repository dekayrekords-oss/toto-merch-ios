package com.meowmba.shared.ui.lumen

import androidx.compose.ui.graphics.Color

/**
 * Exact memo of Compose's color [androidx.compose.ui.graphics.lerp].
 *
 * That lerp converts both ends to Oklab and back on every call. The Lumen part
 * painters blend the same user color with the same constants on every frame; a
 * September 22 trace of the idle companion put ~9% of the main thread in those
 * conversions. A hit returns the very value an earlier call computed for the
 * same three inputs, so every drawn pixel is unchanged.
 *
 * Direct-mapped and bounded. One instance per painter, used from that painter's
 * draw calls on the main thread, exactly like the painters' reusable Paths.
 */
internal class ColorLerpCache(capacity: Int = 64) {
    private val size = 1 shl (31 - capacity.coerceIn(2, 4096).countLeadingZeroBits())
    private val mask = size - 1
    private val starts = LongArray(size)
    private val stops = LongArray(size)
    private val fractions = IntArray(size)
    private val results = LongArray(size)
    private val filled = BooleanArray(size)

    fun lerp(start: Color, stop: Color, fraction: Float): Color {
        val a = start.value.toLong()
        val b = stop.value.toLong()
        val f = fraction.toRawBits()
        var h = a * MIX xor b
        h = (h xor (h ushr 32)) * MIX + f
        val i = (h xor (h ushr 29)).toInt() and mask
        if (filled[i] && starts[i] == a && stops[i] == b && fractions[i] == f) return Color(results[i].toULong())
        val blended = androidx.compose.ui.graphics.lerp(start, stop, fraction)
        starts[i] = a
        stops[i] = b
        fractions[i] = f
        results[i] = blended.value.toLong()
        filled[i] = true
        return blended
    }

    private companion object {
        const val MIX = -7046029254386353131L
    }
}
