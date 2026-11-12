package com.meowmba.shared.ui.vending

import com.meowmba.shared.ui.lumen.LumenFrame
import com.meowmba.shared.ui.lumen.lumenRoll

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/** Draws a dispensed prop in the same unit space as [GeneratedAvatarPainter]. */
class GeneratedPropPainter {
    private val shape = Path()

    fun draw(
        scope: DrawScope,
        form: GeneratedPropForm,
        center: Offset,
        unit: Float,
        alpha: Float = 1f,
    ) = with(scope) {
        if (!unit.isFinite() || unit <= 0f || !center.x.isFinite() || !center.y.isFinite()) return@with
        val a = alpha.coerceIn(0f, 1f)
        if (a < .005f) return@with
        translate(center.x, center.y) {
            val s = unit * .82f * form.attachScale.coerceIn(.3f, 2.5f)
            scale(s, s, Offset.Zero) {
                rotate(form.attachRotation.coerceIn(-180f, 180f), Offset.Zero) {
                    form.parts.forEach { part -> drawPart(part, a) }
                }
            }
        }
    }

    private fun DrawScope.drawPart(p: GeneratedAvatarPart, alpha: Float) {
        translate(p.x, p.y) {
            rotate(p.rotation, Offset.Zero) {
                val topLeft = Offset(-p.width * .5f, -p.height * .5f)
                val size = Size(p.width, p.height)
                val stroke = Stroke(p.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val fill = Color(p.fill).copy(alpha = alpha)
                val outline = Color(p.outline).copy(alpha = alpha)
                when (p.shape) {
                    GeneratedAvatarShape.ELLIPSE -> {
                        drawOval(fill, topLeft, size)
                        drawOval(outline, topLeft, size, style = stroke)
                    }
                    GeneratedAvatarShape.ROUND_RECT -> {
                        val radius = CornerRadius(minOf(p.width, p.height) * .16f)
                        drawRoundRect(fill, topLeft, size, radius)
                        drawRoundRect(outline, topLeft, size, radius, style = stroke)
                    }
                    GeneratedAvatarShape.POLYGON -> {
                        shape.reset()
                        p.points.forEachIndexed { i, point ->
                            if (i == 0) shape.moveTo(point.x * p.width, point.y * p.height)
                            else shape.lineTo(point.x * p.width, point.y * p.height)
                        }
                        shape.close()
                        drawPath(shape, fill)
                        drawPath(shape, outline, style = stroke)
                    }
                }
            }
        }
    }
}
