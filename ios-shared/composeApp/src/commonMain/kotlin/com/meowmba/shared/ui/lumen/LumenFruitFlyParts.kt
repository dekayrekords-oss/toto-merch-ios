package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Floating fruit-fly features, never a head or body. Short oval antennae with
 * fine arista bristles distinguish this design from an ant's long elbowed stalks.
 * Everything samples LumenFrame: no new worker, sensor, animation clock or LLM call.
 */
internal class LumenFruitFlyParts {
    private val wing = Path()
    private val line = Path()
    private val mouth = Path()
    // Stable low-density facets; reuse geometry rather than generating random
    // texture each frame. The existing lens aperture clips these during a blink.
    private val facet = Path().apply {
        repeat(6) { i ->
            val a = i * kotlin.math.PI / 3
            val x = cos(a).toFloat(); val y = sin(a).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    // Exactly the DrawScope drawPath(color, style = Stroke(.055f)) defaults.
    // The sampled idle trace spent most compound-eye time in repeated Compose
    // context/paint accessors. This paint belongs only to the facet pass.
    private val facetPaint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeWidth = .055f
        strokeMiterLimit = Stroke.DefaultMiter
        strokeCap = Stroke.DefaultCap
        strokeJoin = Stroke.DefaultJoin
        blendMode = DrawScope.DefaultBlendMode
        filterQuality = DrawScope.DefaultFilterQuality
        isAntiAlias = true
    }

    fun backdrop(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame) = with(scope) {
        val a = f.form.fruitFly
        if (a <= .005f) return@with
        translate(center.x, center.y) { scale(unit, unit, Offset.Zero) {
            for (side in intArrayOf(-1, 1)) scale(side.toFloat(), 1f, Offset.Zero) {
                // One detached wing on each side, behind the eyes. Follow the
                // shared ear/body lag softly instead of buzzing at render rate.
                // The viewer reads the asymmetric hinge angle as a small turn
                // toward them. WingBeat comes from LumenRig's active clock.
                val flap = (f.wingBeat * 2f - 1f)
                rotate(5f + f.earLag * side * .35f + flap * (13f + abs(f.perspective) * 8f), Offset(.30f, .06f)) {
                    wing.reset(); wing.moveTo(.32f, .06f)
                    wing.cubicTo(.35f, -.08f, .52f, -.19f, .56f, -.13f)
                    wing.cubicTo(.62f, -.02f, .49f, .13f, .35f, .14f)
                    wing.quadraticBezierTo(.32f, .13f, .32f, .06f); wing.close()
                    drawPath(wing, Brush.linearGradient(listOf(Color(0xFFBDEBF1).copy(alpha = a * (.34f + f.wingBeat * .20f)),
                        Color(0xFF76BCCB).copy(alpha = a * .09f)), Offset(.48f, -.13f), Offset(.34f, .14f)))
                    drawPath(wing, Color(0xFFBCE8EE).copy(alpha = a * .50f), style = Stroke(.0025f))
                    for (i in 0..2) {
                        line.reset(); line.moveTo(.34f, .095f)
                        line.quadraticBezierTo(.43f, .045f - i * .035f, .53f + i * .012f, .02f - i * .062f)
                        drawPath(line, Color(0xFFC9EBEA).copy(alpha = a * .28f), style = Stroke(.0018f))
                    }
                }
                rotate(side * f.earLag * .30f - f.earLift * .20f, Offset(.095f, -.235f)) {
                    drawOval(Brush.linearGradient(listOf(Color(0xFFD4D9AA).copy(alpha = a),
                        Color(0xFF718770).copy(alpha = a))), Offset(.060f, -.321f), Size(.058f, .075f))
                    line.reset(); line.moveTo(.098f, -.307f)
                    line.quadraticBezierTo(.116f, -.362f, .149f, -.386f)
                    drawPath(line, Color(0xFFBCC9A3).copy(alpha = a), style = Stroke(.0035f, cap = StrokeCap.Round))
                    for (i in 0..3) {
                        val root = Offset(.115f + i * .009f, -.341f - i * .011f)
                        drawLine(Color(0xFFBCC9A3).copy(alpha = a * .70f), root,
                            root + Offset(.017f, .002f), .0015f, StrokeCap.Round)
                    }
                }
            }
        } }
    }

    fun compoundEye(scope: DrawScope, rx: Float, ry: Float, f: LumenFrame, color: Color) = with(scope) {
        val a = f.form.fruitFly
        val sideShade = (f.perspective * .12f).coerceIn(-.12f, .12f)
        drawRect(Brush.radialGradient(listOf(lerp(color, Color.White, .22f + sideShade), color,
            lerp(color, Color(0xFF290D1B), .72f)), Offset(-rx * .24f, -ry * .30f), ry * 1.8f),
            Offset(-rx, -ry * 1.5f), Size(rx * 2f, ry * 3f), alpha = a)
        val radius = rx * .105f
        val canvas = drawContext.canvas
        facetPaint.color = Color(0xFFFFBAAA).copy(alpha = a * .17f)
        for (row in -7..7) for (column in -7..7) {
            val x = column * radius * 1.5f
            val y = (row + if (column % 2 == 0) 0f else .5f) * radius * 1.732f
            if (x * x / (rx * rx) + y * y / (ry * ry * 1.5f) < 1.35f) {
                // Preserve DrawScope.translate { scale(..., Offset.Zero) { ... } }
                // operation order, including translation outside save/restore.
                // Reordering/combining cells can change fractional sampling or
                // the overlap of translucent facet edges.
                canvas.translate(x, y)
                try {
                    canvas.save()
                    try {
                        canvas.translate(0f, 0f)
                        canvas.scale(radius, radius)
                        canvas.translate(-0f, -0f)
                        canvas.drawPath(facet, facetPaint)
                    } finally {
                        canvas.restore()
                    }
                } finally {
                    canvas.translate(-x, -y)
                }
            }
        }
    }

    fun mouth(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame) = with(scope) {
        val a = f.form.fruitFly
        if (a <= .005f) return@with
        translate(center.x + f.gazeX * unit * .007f, center.y) { scale(unit, unit, Offset.Zero) {
            // A single detached soft smile. No mandibles, muzzle, snout, nose,
            // teeth or tube. Speech opens this same contour, avoiding two mouths.
            val half = .057f + f.smile * .010f
            val y = .244f
            val curve = .018f + f.smile * .025f - f.concern * .028f
            val opening = f.mouthOpen.coerceIn(0f, 1f) * .057f
            mouth.reset(); mouth.moveTo(-half, y)
            mouth.cubicTo(-half * .45f, y + curve, half * .45f, y + curve, half, y)
            if (opening > .002f) {
                mouth.cubicTo(half * .60f, y + curve + opening, -half * .60f, y + curve + opening, -half, y)
                mouth.close()
                drawPath(mouth, Color(0xFF352332).copy(alpha = a))
            }
            drawPath(mouth, Color(0xFFB8CBA9).copy(alpha = a * .95f), style = Stroke(.0055f, cap = StrokeCap.Round))
        } }
    }
}
