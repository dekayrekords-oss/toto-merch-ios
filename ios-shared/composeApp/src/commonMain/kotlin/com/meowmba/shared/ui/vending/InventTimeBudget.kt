package com.meowmba.shared.ui.vending

/** Fast controls drawing/review complexity, not an unrealistically short free-provider queue. */
internal object InventTimeBudget {
    /** Avatar creation used to retain a 75-second FAST deadline after props
     * gained a realistic remote queue allowance. Keep on-device budgets intact. */
    fun avatar(remote: Boolean, level: InventReasonLevel): Long = if (remote) when (level) {
        InventReasonLevel.FAST -> 180_000L
        InventReasonLevel.BALANCED -> 210_000L
        InventReasonLevel.THOROUGH -> 240_000L
    } else when (level) {
        InventReasonLevel.FAST -> 75_000L
        InventReasonLevel.BALANCED -> 90_000L
        InventReasonLevel.THOROUGH -> 120_000L
    }

    fun prop(remote: Boolean, level: InventReasonLevel): Long = if (remote) when (level) {
        InventReasonLevel.FAST -> 180_000L
        InventReasonLevel.BALANCED -> 210_000L
        InventReasonLevel.THOROUGH -> 240_000L
    } else when (level) {
        InventReasonLevel.FAST -> 60_000L
        InventReasonLevel.BALANCED -> 75_000L
        InventReasonLevel.THOROUGH -> 100_000L
    }
}
