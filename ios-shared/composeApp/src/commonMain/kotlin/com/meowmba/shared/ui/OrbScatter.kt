package com.meowmba.shared.ui

/** Quick outward impulse, then a smooth return without a cut at rest. */
internal fun orbScatterAmount(ageSeconds: Float): Float {
    if (!ageSeconds.isFinite() || ageSeconds <= 0f || ageSeconds >= 3f) return 0f
    val t = if (ageSeconds < 0.22f) ageSeconds / 0.22f else (3f - ageSeconds) / 2.78f
    return t * t * (3f - 2f * t)
}
