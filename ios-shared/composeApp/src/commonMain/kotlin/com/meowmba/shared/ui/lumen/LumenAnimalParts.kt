package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate

/** Original native anatomy parts. Shared anchors follow the existing face rig. */
class LumenAnimalParts {
    private val path = Path()
    private val detail = Path()
    private val colorBlends = ColorLerpCache()

    /** Compose's color lerp, memoized: these blends repeat identically every frame. */
    private fun lerp(start: Color, stop: Color, fraction: Float): Color = colorBlends.lerp(start, stop, fraction)

    fun tail(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame, color: Color, glow: Color) = with(scope) {
        val bush = f.form.bushyTail
        val ring = f.form.ringTail
        val alpha = (bush + ring).coerceIn(0f, 1f)
        if (alpha < 0.005f) return@with
        val root = center + Offset(unit * 0.20f, unit * 0.21f)
        rotate(f.tailSwing * 18f + f.earLag * 0.20f, root) {
            // A compact curled plume behind the face, not a new permanent body.
            fun p(x: Float, y: Float) = center + Offset(x * unit, y * unit)
            val a = p(0.12f, 0.30f)
            path.reset(); path.moveTo(a.x, a.y)
            path.cubicTo(p(0.36f, 0.39f).x, p(0.36f, 0.39f).y, p(0.46f, 0.22f).x, p(0.46f, 0.22f).y, p(0.39f, 0.01f).x, p(0.39f, 0.01f).y)
            path.cubicTo(p(0.34f, -0.07f).x, p(0.34f, -0.07f).y, p(0.23f, -0.06f).x, p(0.23f, -0.06f).y, p(0.25f, 0.09f).x, p(0.25f, 0.09f).y)
            path.cubicTo(p(0.29f, 0.22f).x, p(0.29f, 0.22f).y, p(0.21f, 0.24f).x, p(0.21f, 0.24f).y, a.x, a.y)
            path.close()
            drawPath(path, glow.copy(alpha = alpha * 0.045f), style = Stroke(unit * 0.020f))
            drawPath(path, Brush.radialGradient(
                0f to lerp(color, Color.White, .36f).copy(alpha = alpha),
                .43f to color.copy(alpha = alpha),
                1f to lerp(color, Color.Black, .56f).copy(alpha = alpha * .93f),
                center = p(.295f, .08f), radius = unit * .30f))
            clipPath(path) {
                if (ring > 0.005f) for (i in 0..4) {
                    val y = 0.015f + i * 0.07f
                    detail.reset(); detail.moveTo(p(.22f, y).x, p(.22f, y).y)
                    detail.cubicTo(p(.31f, y + .045f).x, p(.31f, y + .045f).y,
                        p(.40f, y + .05f).x, p(.40f, y + .05f).y, p(.47f, y + .036f).x, p(.47f, y + .036f).y)
                    drawPath(detail, lerp(color, Color.Black, .76f).copy(alpha = ring * .20f),
                        style = Stroke(unit * .043f, cap = StrokeCap.Round))
                    drawPath(detail, lerp(color, Color.Black, .76f).copy(alpha = ring * .74f),
                        style = Stroke(unit * .029f, cap = StrokeCap.Round))
                }
                if (bush > 0.005f) for (i in 0..6) {
                    val x = 0.29f + i * 0.016f
                    detail.reset(); detail.moveTo(p(0.25f, 0.29f).x, p(0.25f, 0.29f).y)
                    detail.cubicTo(p(x + 0.06f, 0.24f).x, p(x + 0.06f, 0.24f).y,
                        p(x + 0.035f, 0.09f).x, p(x + 0.035f, 0.09f).y, p(x, -0.03f).x, p(x, -0.03f).y)
                    drawPath(detail, lerp(color, Color.White, 0.55f).copy(alpha = bush * 0.24f), style = Stroke(unit * 0.003f))
                }
            }
            drawPath(path, lerp(color, Color.White, .2f).copy(alpha = alpha * .12f), style = Stroke(unit * .003f))
        }
    }

    fun roundEar(scope: DrawScope, side: Int, rx: Float, ry: Float, f: LumenFrame, color: Color, glow: Color) = with(scope) {
        val alpha = f.form.roundEars
        if (alpha < 0.005f) return@with
        val c = Offset(side * rx * 0.57f, -ry * (0.86f + alpha * 0.32f))
        rotate(side * (10f - f.earLift) + f.earLag, c) {
            val w = rx * 1.03f; val h = ry * (0.40f + alpha * 0.30f)
            drawOval(glow.copy(alpha = alpha * 0.10f), c - Offset(w * 0.57f, h * 0.58f), Size(w * 1.14f, h * 1.16f))
            drawOval(Brush.radialGradient(listOf(lerp(color, Color.White, .38f).copy(alpha = alpha),
                color.copy(alpha = alpha), lerp(color, Color.Black, .58f).copy(alpha = alpha)),
                c - Offset(w * .19f, h * .24f), w * .88f),
                c - Offset(w / 2, h / 2), Size(w, h))
            drawOval(Brush.radialGradient(listOf(lerp(color, Color(0xFFE9B4AA), .52f).copy(alpha = alpha * .62f),
                lerp(color, Color.Black, .28f).copy(alpha = alpha * .70f)), c - Offset(w * .08f, h * .04f), w * .37f),
                c - Offset(w * 0.31f, h * 0.30f), Size(w * 0.62f, h * 0.61f))
            if (f.form.earTufts > 0.005f) {
                for (i in 0..2) {
                    val start = c + Offset((i - 1) * rx * 0.13f, -h * 0.28f)
                    val end = start + Offset(side * rx * (0.12f + i * 0.05f), -ry * (0.28f + i * 0.035f))
                    detail.reset(); detail.moveTo(start.x, start.y)
                    detail.quadraticBezierTo(end.x - side * rx * 0.13f, end.y + ry * 0.06f, end.x, end.y)
                    drawPath(detail, color.copy(alpha = f.form.earTufts * 0.85f), style = Stroke(rx * (0.09f - i * 0.015f), cap = StrokeCap.Round))
                }
            }
        }
    }

    fun mask(scope: DrawScope, rx: Float, ry: Float, f: LumenFrame, color: Color) = with(scope) {
        val alpha = f.form.eyeMask
        if (alpha < 0.005f) return@with
        // The mask sits BEHIND the lens: it cannot occlude gaze or eyelids.
        path.reset(); path.moveTo(-rx * 1.48f, -ry * 0.30f)
        path.cubicTo(-rx * 1.28f, -ry * 1.09f, -rx * 0.40f, -ry * 1.30f, rx * 0.20f, -ry * 1.14f)
        path.cubicTo(rx * 0.95f, -ry * 1.02f, rx * 1.46f, -ry * 0.60f, rx * 1.48f, -ry * 0.14f)
        path.cubicTo(rx * 1.19f, ry * 0.36f, rx * 1.05f, ry * 1.20f, rx * 0.12f, ry * 1.15f)
        path.cubicTo(-rx * 0.96f, ry * 1.24f, -rx * 1.05f, ry * 0.48f, -rx * 1.48f, -ry * 0.30f)
        path.close()
        drawPath(path, lerp(color, Color.Black, .72f).copy(alpha = alpha * .08f), style = Stroke(rx * .10f))
        drawPath(path, Brush.radialGradient(listOf(lerp(color, Color.Black, .57f).copy(alpha = alpha * .94f),
            lerp(color, Color.Black, .82f).copy(alpha = alpha * .95f)), Offset(-rx * .4f, -ry * .45f), ry * 2.1f))
    }

    fun muzzle(scope: DrawScope, center: Offset, u: Float, f: LumenFrame, color: Color, glow: Color) = with(scope) {
        val dog = f.form.longMuzzle
        val cheeks = f.form.cheeks
        if (dog < 0.005f && cheeks < 0.005f) return@with
        val c = center + Offset(f.gazeX * u * 0.01f, u * 0.175f)
        if (cheeks > 0.005f) {
            for (side in intArrayOf(-1, 1)) {
                val p = c + Offset(side * u * 0.067f, u * 0.019f)
                drawOval(Brush.radialGradient(listOf(lerp(color, Color.White, 0.35f).copy(alpha = cheeks * 0.68f),
                    color.copy(alpha = cheeks * 0.12f)), p, u * 0.06f),
                    p - Offset(u * 0.060f, u * 0.042f), Size(u * 0.12f, u * 0.084f))
            }
        }
        if (dog > 0.005f) {
            val muzzleColor = lerp(color, Color(0xFFF1F0E9), f.form.shepherdCoat * .88f)
            val nose = c - Offset(0f, u * 0.018f)
            for (side in intArrayOf(-1, 1)) {
                val p = c + Offset(side * u * 0.035f, u * 0.022f)
                drawOval(Brush.radialGradient(listOf(lerp(muzzleColor, Color.White, .35f).copy(alpha = dog * .94f),
                    muzzleColor.copy(alpha = dog * .82f), lerp(muzzleColor, Color.Black, .20f).copy(alpha = dog * .12f)),
                    p - Offset(u * .013f, u * .013f), u * .071f),
                    p - Offset(u * 0.068f, u * 0.043f), Size(u * 0.136f, u * 0.086f))
            }
            fun n(x: Float, y: Float) = nose + Offset(x * u, y * u)
            detail.reset(); detail.moveTo(n(-.04f, -.012f).x, n(-.04f, -.012f).y)
            detail.cubicTo(n(-.038f, -.036f).x, n(-.038f, -.036f).y, n(.037f, -.033f).x, n(.037f, -.033f).y,
                n(.042f, -.012f).x, n(.042f, -.012f).y)
            detail.cubicTo(n(.047f, .009f).x, n(.047f, .009f).y, n(.013f, .03f).x, n(.013f, .03f).y,
                n(0f, .029f).x, n(0f, .029f).y)
            detail.cubicTo(n(-.017f, .03f).x, n(-.017f, .03f).y, n(-.045f, .005f).x, n(-.045f, .005f).y,
                n(-.04f, -.012f).x, n(-.04f, -.012f).y); detail.close()
            drawPath(detail, Brush.radialGradient(listOf(lerp(color, Color.Black, .55f).copy(alpha = dog),
                lerp(color, Color.Black, .90f).copy(alpha = dog)), n(-.015f, -.017f), u * .067f))
            drawOval(Color.White.copy(alpha = dog * .23f), nose - Offset(u * .024f, u * .019f), Size(u * .033f, u * .012f))
            detail.reset(); detail.moveTo(nose.x, nose.y + u * 0.022f)
            detail.quadraticBezierTo(nose.x - u * .004f, nose.y + u * .041f, nose.x, nose.y + u * .052f)
            for (side in intArrayOf(-1, 1)) {
                detail.moveTo(nose.x, nose.y + u * .052f)
                detail.cubicTo(nose.x + side * u * .015f, nose.y + u * (.072f + f.mouthOpen * .006f),
                    nose.x + side * u * .046f, nose.y + u * (.069f - f.smile * .015f),
                    nose.x + side * u * .060f, nose.y + u * (.049f - f.smile * .018f + f.concern * .008f))
            }
            drawPath(detail, lerp(color, Color.Black, .56f).copy(alpha = dog * .83f), style = Stroke(u * .0035f, cap = StrokeCap.Round))
            if (f.mouthOpen > .02f) {
                drawOval(lerp(color, Color.Black, .75f).copy(alpha = dog),
                    nose + Offset(-u * .023f, u * .052f), Size(u * .046f, u * (.009f + f.mouthOpen * .027f)))
                drawOval(lerp(color, Color(0xFFE09CA8), .70f).copy(alpha = dog * .72f),
                    nose + Offset(-u * .016f, u * (.063f + f.mouthOpen * .013f)), Size(u * .032f, u * .008f))
            }
        }
    }
}
