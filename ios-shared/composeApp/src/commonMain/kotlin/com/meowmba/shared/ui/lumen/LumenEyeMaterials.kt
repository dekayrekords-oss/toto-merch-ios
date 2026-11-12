package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Brushes are rebuilt on palette/viewport changes, not for each moving pupil. */
internal class LumenEyeMaterials(val rx: Float, val ry: Float, val color: Color, val glow: Color) {
    // This exact blend is shared by all 48 fibres in both eyes. Recomputing it
    // per line repeats costly Oklab colour conversion without changing a pixel.
    val fibre = lerp(color, Color.White, .42f)
    val halo = Brush.radialGradient(listOf(glow.copy(alpha = .10f), glow.copy(alpha = .025f), Color.Transparent),
        Offset.Zero, ry * 2.5f)
    val sclera = Brush.radialGradient(
        listOf(lerp(Color(0xFFF2F4ED), color, .08f), lerp(Color(0xFFD4E0DF), color, .10f), Color(0xFF344F5B)),
        Offset(-rx * .23f, -ry * .30f), ry * 1.7f)
    val iris = Brush.radialGradient(listOf(lerp(color, Color.White, .40f), color,
        lerp(color, Color(0xFF073346), .55f), Color(0xFF0A2330)), Offset(0f, rx * .20f), rx * .94f)
    val pupil = Brush.radialGradient(listOf(Color(0xFF020A14), Color(0xFF071724), Color(0xFF1E3E49)),
        Offset(-rx * .1f, -ry * .13f), rx * .95f)
    val rim = Brush.linearGradient(listOf(lerp(color, Color.White, .76f).copy(alpha = .55f),
        glow.copy(alpha = .08f), glow.copy(alpha = .32f)), Offset(-rx, -ry), Offset(rx, ry))
    val reflection = Brush.linearGradient(listOf(Color.White.copy(alpha = .77f), Color.White.copy(alpha = .12f), Color.Transparent),
        Offset(-rx * .55f, -ry * .64f), Offset(rx * .22f, -ry * .13f))
    val fillLight = Brush.radialGradient(listOf(color.copy(alpha = .20f), Color.Transparent),
        Offset(rx * .10f, ry * .74f), rx * .9f)
}
