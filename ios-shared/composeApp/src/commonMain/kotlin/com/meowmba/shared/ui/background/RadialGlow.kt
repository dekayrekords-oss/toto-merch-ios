package com.meowmba.shared.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/** Analytic gradient, not a bitmap: retains full resolution at every scale.
 * Changing position/radius/opacity no longer allocates a new native shader.
 * Brush cache is bounded and UI-thread confined, like the DrawScopes using it.
 */
internal object RadialGlow {
    private val brushes = linkedMapOf<Color, Brush>()
    fun brush(color: Color): Brush {
        val key = color.copy(alpha = 1f)
        val brush = brushes.remove(key) ?: Brush.radialGradient(
            listOf(key, key.copy(alpha = 0f)), center = Offset.Zero, radius = 1f)
        brushes[key] = brush
        if (brushes.size > 16) brushes.remove(brushes.keys.first())
        return brush
    }
}

internal fun DrawScope.drawRadialGlow(
    center: Offset, radius: Float, brush: Brush, alpha: Float,
) {
    if (radius <= 0f || alpha <= 0f) return
    withTransform({
        translate(center.x, center.y)
        scale(radius, radius, Offset.Zero)
    }) {
        drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = alpha.coerceIn(0f, 1f))
    }
}
