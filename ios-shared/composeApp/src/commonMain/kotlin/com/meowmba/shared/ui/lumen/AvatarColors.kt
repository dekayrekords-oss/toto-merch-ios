package com.meowmba.shared.ui.lumen

/**
 * Curated swatches for the Godot face eye + glow pickers.
 * Values are opaque ARGB packed as [Long] so they round-trip through prefs
 * as hex strings without signed-int overflow.
 */
object AvatarColors {
    const val DEFAULT_EYE: Long = 0xFF4DFF85L   // mint — matches original EyeGreen
    const val DEFAULT_GLOW: Long = 0xFF4DFF85L

    data class Swatch(val argb: Long, val label: String)

    val swatches: List<Swatch> = listOf(
        Swatch(0xFFE8D5B9L, "Pearl"),
        Swatch(0xFF4DFF85L, "Mint"),
        Swatch(0xFF58A6FFL, "Blue"),
        Swatch(0xFF79C0FFL, "Sky"),
        Swatch(0xFFA371F7L, "Violet"),
        Swatch(0xFFFF7B72L, "Coral"),
        Swatch(0xFFFFA657L, "Amber"),
        Swatch(0xFFFFA6C9L, "Pink"),
        Swatch(0xFFE6EDF3L, "White"),
        Swatch(0xFF3FB950L, "Forest"),
        Swatch(0xFF39D0D8L, "Teal"),
    )

    fun parse(hex: String?, fallback: Long): Long {
        if (hex.isNullOrBlank()) return fallback
        val cleaned = hex.trim().removePrefix("0x").removePrefix("0X")
        return try {
            cleaned.toLong(16).let { v ->
                // Ensure alpha is set if user stored RRGGBB only
                if (cleaned.length <= 6) (0xFF000000L or v) else v
            }
        } catch (_: NumberFormatException) {
            fallback
        }
    }

    fun toPref(argb: Long): String =
        (argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')
}
