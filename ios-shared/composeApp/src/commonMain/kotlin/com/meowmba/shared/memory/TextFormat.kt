package com.meowmba.shared.memory

/**
 * Common-code text formatting helpers (String.format and Math.* are JVM-only;
 * these pure-Kotlin replacements compile on every target).
 */
internal object TextFormat {

    /** Zero-pad a number to [width] digits (e.g. pad(9, 2) == "09"). */
    fun pad(num: Long, width: Int): String {
        val s = num.toString()
        return if (s.length >= width) s else "0".repeat(width - s.length) + s
    }

    fun pad(num: Int, width: Int): String = pad(num.toLong(), width)

    /** Fixed two-decimal rendering (equivalent of "%.2f" for scoring text). */
    fun fixed2(value: Float): String {
        val n = kotlin.math.round(value * 100f).toLong()
        val sign = if (n < 0) "-" else ""
        val abs = kotlin.math.abs(n)
        return sign + pad(abs / 100, 1) + "." + pad(abs % 100, 2)
    }

    fun floorDiv(x: Long, y: Long): Long {
        var q = x / y
        if ((x % y != 0L) && ((x xor y) < 0L)) q -= 1
        return q
    }

    fun floorMod(x: Long, y: Long): Long = x - floorDiv(x, y) * y

    fun floorDiv(x: Int, y: Int): Int {
        var q = x / y
        if ((x % y != 0) && ((x xor y) < 0)) q -= 1
        return q
    }

    fun floorMod(x: Int, y: Int): Int = x - floorDiv(x, y) * y
}
