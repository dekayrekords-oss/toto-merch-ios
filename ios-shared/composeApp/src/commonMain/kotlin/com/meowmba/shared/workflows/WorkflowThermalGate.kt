package com.meowmba.shared.workflows

import com.meowmba.shared.core.PlatformLock

/**
 * Hysteresis avoids restarting a multi-GB model each time the phone drops
 * from 39.0 to 38.9 C. After a thermal pause it must cool below 37.5 C.
 *
 * Ported from `core/workflows/WorkflowThermalGate.kt`. The only change is
 * `@Synchronized` → the [PlatformLock] seam. The body's trailing `return`
 * becomes the lambda's final expression: `withLock` is a member of an
 * `expect class` and therefore cannot be `inline`, so a non-local `return` is
 * not allowed even as the last statement.
 */
class WorkflowThermalGate(private var cooling: Boolean = false) {

    private val lock = PlatformLock()

    fun allowsRun(
        batteryTenthsC: Int,
        systemModerateOrHotter: Boolean,
        powerSaving: Boolean,
    ): Boolean = lock.withLock {
        if (systemModerateOrHotter || batteryTenthsC >= 390) cooling = true
        if (cooling && !systemModerateOrHotter && batteryTenthsC < 375) cooling = false
        !cooling && !powerSaving
    }
}
