package com.meowmba.shared.ui.desk

/** Android PowerManager thermal levels, as the Desk uses them (iOS: ProcessInfo.thermalState). */
object DeskThermal { const val NONE = 0; const val LIGHT = 1; const val MODERATE = 2; const val SEVERE = 3 }

/**
 * Android AvatarFrameCadence (own-rule only): bounds decorative redraws to the active rate,
 * halves it when LIGHT, quarters it at MODERATE, stops at SEVERE.
 */
internal class DeskFrameCadence {
    private var previous = Long.MIN_VALUE
    private var admittedIntervalNs = 0L

    fun admit(nowNanos: Long, active: Boolean = true, fpsTarget: Int = DEFAULT_ACTIVE_FPS, thermalStatus: Int = DeskThermal.NONE): Boolean {
        if (thermalStatus >= DeskThermal.SEVERE) return false
        val interval = intervalNs(active, fpsTarget, thermalStatus)
        if (previous != Long.MIN_VALUE && nowNanos - previous in 0 until interval) return false
        previous = nowNanos
        admittedIntervalNs = interval
        return true
    }

    /** Sleep after an admitted frame instead of waking on every refused vsync. */
    fun idleNanosAfterAdmit(): Long {
        val sleep = admittedIntervalNs - 2 * 16_666_667L
        return if (sleep < 16_666_667L) 0L else sleep
    }

    companion object {
        const val DEFAULT_ACTIVE_FPS = 30
        fun intervalNs(active: Boolean, fpsTarget: Int, thermalStatus: Int): Long {
            val target = fpsTarget.coerceIn(15, 60)
            val base = if (active) target.toFloat() else (target / 2f).coerceAtLeast(8f)
            val mul = when { thermalStatus >= DeskThermal.MODERATE -> .25f; thermalStatus >= DeskThermal.LIGHT -> .5f; else -> 1f }
            return (1_000_000_000.0 / (base * mul).coerceAtLeast(1f)).toLong().coerceAtLeast(8_000_000L)
        }
    }
}
