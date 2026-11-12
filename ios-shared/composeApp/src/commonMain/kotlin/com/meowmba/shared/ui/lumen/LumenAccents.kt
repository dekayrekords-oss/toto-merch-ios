package com.meowmba.shared.ui.lumen

import com.meowmba.shared.companion.AnimFrame
import com.meowmba.shared.companion.CustomAnimationSpec
import com.meowmba.shared.companion.MouthMode

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Smooth, bounded authored accent timing. No random noise, timers or retained overlays. */
internal fun lumenPerformanceEnvelope(progress: Float): Float {
    if (!progress.isFinite()) return 0f
    fun ease(v: Float): Float = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    return ease((progress - 0.12f) / 0.16f) * (1f - ease((progress - 0.62f) / 0.25f))
}

/** Shared production/test adapter for channels the older keyframe format cannot express. */
internal fun lumenPerformanceInput(input: LumenInput, performance: AvatarPerformance?, progress: Float): LumenInput {
    if (performance == null || !progress.isFinite() || progress !in 0f..1f) return input
    val envelope = lumenPerformanceEnvelope(progress)
    val posed = input.copy(
        openness = if (performance.family == "wink") 1f else input.openness,
        winkLeft = if (performance.family == "wink") envelope else 0f,
        presence = if (performance.family in setOf("hide", "peek")) 1f - envelope * 0.97f else 1f,
        lidTilt = when (performance.family) {
            "downcast" -> 11f * envelope
            "squint" -> -12f * envelope
            else -> input.lidTilt
        },
    )
    return LumenScenes.gaze(posed, performance.accent, progress, performance.id)
}

/** Original vector props: not sprites or captures taken from another product. */
internal class LumenAccentPainter {
    private val path = Path()

    fun draw(scope: DrawScope, accent: PerformanceAccent, progress: Float, center: Offset, unit: Float, tint: Color) = with(scope) {
        val envelope = lumenPerformanceEnvelope(progress)
        if (accent == PerformanceAccent.NONE || envelope <= 0.001f) return
        val count = when (accent) {
            PerformanceAccent.SNOW -> 6
            PerformanceAccent.HEARTS, PerformanceAccent.SPARKLES, PerformanceAccent.EMBER -> 3
            PerformanceAccent.SLEEP, PerformanceAccent.MUSIC -> 2
            else -> 1
        }
        for (i in 0 until count) {
            val side = if (i % 2 == 0) 1f else -1f
            val weather = accent == PerformanceAccent.SNOW || accent == PerformanceAccent.EMBER
            val x = if (weather) (i - (count - 1) * 0.5f) * 0.105f else side * (0.34f + (i / 2) * 0.013f)
            val y = if (weather) -0.32f + progress * 0.10f else -0.22f - i * 0.028f - envelope * 0.015f
            val p = center + Offset(x * unit, y * unit)
            val radius = unit * (if (weather) 0.017f else 0.028f) * (0.72f + envelope * 0.28f)
            val color = when (accent) {
                PerformanceAccent.SNOW -> lerp(tint, Color.White, 0.75f)
                PerformanceAccent.EMBER, PerformanceAccent.WARMTH -> lerp(tint, Color(0xFFFFB15E), 0.65f)
                else -> lerp(tint, Color.White, 0.28f)
            }.copy(alpha = envelope * 0.9f)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = envelope * 0.10f), Color.Transparent), p, radius * 2.8f), radius * 2.8f, p)
            translate(p.x, p.y) {
                scale(radius, radius, Offset.Zero) {
                    rotate(sin(progress * PI.toFloat() * 2f + i) * 5f, Offset.Zero) {
                        shape(accent, color)
                    }
                }
            }
        }
    }

    private fun DrawScope.shape(accent: PerformanceAccent, color: Color) {
        path.reset()
        var outline = false
        when (accent) {
            PerformanceAccent.HEARTS -> {
                path.moveTo(0f, 0.86f)
                path.cubicTo(-2f, -0.3f, -0.9f, -1.6f, 0f, -0.63f)
                path.cubicTo(0.9f, -1.6f, 2f, -0.3f, 0f, 0.86f)
                path.close()
            }
            PerformanceAccent.TEAR, PerformanceAccent.EMBER -> {
                path.moveTo(0f, -1.25f)
                path.cubicTo(-0.18f, -0.55f, -0.95f, 0.14f, -0.58f, 0.67f)
                path.cubicTo(-0.12f, 1.3f, 1.08f, 0.7f, 0.55f, -0.12f)
                path.cubicTo(0.3f, -0.55f, 0.18f, -0.65f, 0f, -1.25f)
                path.close()
            }
            PerformanceAccent.STAR, PerformanceAccent.SPARKLES -> {
                val n = if (accent == PerformanceAccent.STAR) 5 else 4
                for (i in 0 until n * 2) {
                    val angle = i * PI.toFloat() / n - PI.toFloat() / 2f
                    val r = if (i % 2 == 0) 1f else 0.35f
                    if (i == 0) path.moveTo(cos(angle) * r, sin(angle) * r)
                    else path.lineTo(cos(angle) * r, sin(angle) * r)
                }
                path.close()
            }
            PerformanceAccent.QUESTION -> {
                outline = true
                path.moveTo(-0.52f, -0.6f)
                path.cubicTo(-0.25f, -1.15f, 0.9f, -0.85f, 0.51f, -0.14f)
                path.cubicTo(0.34f, 0.11f, 0f, 0.04f, 0f, 0.42f)
                drawCircle(color, 0.12f, Offset(0f, 0.86f))
            }
            PerformanceAccent.SLEEP -> {
                outline = true
                path.moveTo(-0.6f, -0.7f); path.lineTo(0.6f, -0.7f)
                path.lineTo(-0.6f, 0.7f); path.lineTo(0.6f, 0.7f)
            }
            PerformanceAccent.MUSIC -> {
                outline = true
                path.moveTo(0.22f, 0.54f); path.lineTo(0.22f, -0.9f)
                path.cubicTo(0.45f, -0.6f, 0.84f, -0.72f, 0.87f, -0.15f)
                drawOval(color, Offset(-0.55f, 0.34f), androidx.compose.ui.geometry.Size(0.82f, 0.53f))
            }
            PerformanceAccent.BUBBLE -> {
                drawCircle(color.copy(alpha = color.alpha * 0.12f), 0.88f)
                drawCircle(color, 0.88f, style = Stroke(0.08f))
                drawArc(color, 210f, 55f, false, Offset(-0.59f, -0.59f), androidx.compose.ui.geometry.Size(1.18f, 1.18f), style = Stroke(0.1f, cap = StrokeCap.Round))
            }
            PerformanceAccent.SNOW -> {
                outline = true
                for (i in 0..2) {
                    val a = i * PI.toFloat() / 3f
                    path.moveTo(-cos(a), -sin(a)); path.lineTo(cos(a), sin(a))
                }
            }
            PerformanceAccent.WIND, PerformanceAccent.WARMTH -> {
                outline = true
                for (i in -1..1) {
                    val y = i * 0.6f
                    path.moveTo(-1f, y)
                    path.cubicTo(-0.25f, y - 0.45f, 0.25f, y + 0.45f, 1f, y)
                }
            }
            PerformanceAccent.LIGHTNING -> {
                path.moveTo(0.1f, -1.25f); path.lineTo(-0.65f, 0.13f); path.lineTo(-0.05f, 0.13f)
                path.lineTo(-0.22f, 1.22f); path.lineTo(0.76f, -0.33f); path.lineTo(0.12f, -0.33f); path.close()
            }
            PerformanceAccent.NONE, PerformanceAccent.SIGNATURE -> Unit
            PerformanceAccent.BUTTERFLY, PerformanceAccent.DETECTIVE, PerformanceAccent.YARN,
            PerformanceAccent.BALL, PerformanceAccent.ACORN, PerformanceAccent.SNACK,
            PerformanceAccent.NEST, PerformanceAccent.RAIN, PerformanceAccent.MOUSE, PerformanceAccent.BOOK,
            PerformanceAccent.HAT_COWBOY, PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET -> Unit // Shared scene painter owns these.
        }
        if (outline) drawPath(path, color, style = Stroke(0.14f, cap = StrokeCap.Round))
        else drawPath(path, Brush.linearGradient(listOf(lerp(color, Color.White, 0.3f).copy(alpha = color.alpha),
            color, lerp(color, Color.Black, 0.24f).copy(alpha = color.alpha)), Offset(-0.7f, -1f), Offset(0.65f, 1f)))
    }
}
