package com.meowmba.shared.core.flow

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Meow Flow colour themes (owner decision D7). Values are the approved tokens in
 * docs/design/README.md. ARGB ints keep this JVM-testable; the UI wraps them in Color.
 */
data class FlowPalette(
    val paper: Int,
    val ink: Int,
    val ink2: Int,
    /** Decorative only (separators, leader lines). Never for text the user must read. */
    val ink3: Int,
    val rule: Int,
    val accent: Int,
    val onAccent: Int,
)

data class FlowTheme(val key: String, val label: String, val day: List<Int>, val night: List<Int>)

object FlowThemes {
    const val DEFAULT_KEY = "paper"
    const val DARK_ON_ACCENT = 0xFF161513.toInt()
    const val LIGHT_ON_ACCENT = 0xFFFFF8F0.toInt()

    private fun c(hex: Long) = hex.toInt()

    // Order per list: paper, ink, ink2, ink3, rule, accent.
    val all: List<FlowTheme> = listOf(
        FlowTheme("paper", "Paper",
            listOf(c(0xFFF2EEE5), c(0xFF1C1B19), c(0xFF56524A), c(0xFF8C877C), c(0xFFD5CEBF), c(0xFFC23B16)),
            listOf(c(0xFF1E1C19), c(0xFFEDE6D6), c(0xFFB7AF9F), c(0xFF8A8376), c(0xFF3A362F), c(0xFFE8794F))),
        FlowTheme("linen", "Linen",
            listOf(c(0xFFF4F1EA), c(0xFF16233B), c(0xFF4A5568), c(0xFF8791A3), c(0xFFD9D4C8), c(0xFF1F4FBF)),
            listOf(c(0xFF131A26), c(0xFFE6E9F0), c(0xFFAEB6C6), c(0xFF7D879A), c(0xFF2B3445), c(0xFF86A6FF))),
        FlowTheme("stone", "Stone",
            listOf(c(0xFFEEEFEA), c(0xFF1B211C), c(0xFF4E574F), c(0xFF8A928A), c(0xFFD3D7CF), c(0xFF2F7A3E)),
            listOf(c(0xFF171B18), c(0xFFE4E9E2), c(0xFFAAB3AA), c(0xFF7E877E), c(0xFF2F3630), c(0xFF7CCB8A))),
        FlowTheme("sand", "Sand",
            listOf(c(0xFFF3ECE2), c(0xFF231A20), c(0xFF5A4C55), c(0xFF948690), c(0xFFDCD0C4), c(0xFF8A2E5C)),
            listOf(c(0xFF1D171B), c(0xFFEFE5EA), c(0xFFBBAAB4), c(0xFF8C7C86), c(0xFF3A2F36), c(0xFFE08AB4))),
        FlowTheme("white", "White",
            listOf(c(0xFFFAFAF7), c(0xFF111111), c(0xFF4D4D4D), c(0xFF8A8A8A), c(0xFFE2E2DC), c(0xFFB54708)),
            listOf(c(0xFF141414), c(0xFFF0F0EC), c(0xFFB5B5B0), c(0xFF80807C), c(0xFF2E2E2C), c(0xFFF2A93B))),
        FlowTheme("graphite", "Graphite",
            listOf(c(0xFF1B1B1A), c(0xFFECECEA), c(0xFFB3B3AF), c(0xFF80807C), c(0xFF343432), c(0xFFF2A93B)),
            listOf(c(0xFF121211), c(0xFFE6E6E3), c(0xFFABABA7), c(0xFF7A7A76), c(0xFF2C2C2A), c(0xFFF2A93B))),
    )

    fun has(key: String) = all.any { it.key == key }

    fun theme(key: String): FlowTheme = all.firstOrNull { it.key == key } ?: all.first()

    /** Resolve the palette for a theme, day/night look and optional user accent. */
    fun palette(key: String, night: Boolean, accentOverride: Int? = null): FlowPalette {
        val v = theme(key).let { if (night) it.night else it.day }
        val accent = accentOverride ?: v[5]
        return FlowPalette(v[0], v[1], v[2], v[3], v[4], accent, onAccentFor(accent))
    }

    /** Whichever of dark/light text has the higher contrast (never a fixed luminance cut-off). */
    fun onAccentFor(accent: Int): Int =
        if (contrast(DARK_ON_ACCENT, accent) >= contrast(LIGHT_ON_ACCENT, accent)) DARK_ON_ACCENT else LIGHT_ON_ACCENT

    fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(argb shr 16 and 0xFF) + 0.7152 * ch(argb shr 8 and 0xFF) + 0.0722 * ch(argb and 0xFF)
    }

    /** WCAG contrast ratio, 1.0 … 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Evening look resolution: AUTO follows the work state (dark after work). */
    fun isNight(look: EveningLook, work: WorkState): Boolean = when (look) {
        EveningLook.NIGHT -> true
        EveningLook.DAY -> false
        EveningLook.AUTO -> work == WorkState.OFF
    }
}
