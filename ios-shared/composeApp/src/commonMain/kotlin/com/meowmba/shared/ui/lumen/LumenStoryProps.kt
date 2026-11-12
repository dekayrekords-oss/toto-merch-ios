package com.meowmba.shared.ui.lumen

import com.meowmba.shared.companion.AnimFrame
import com.meowmba.shared.companion.CustomAnimationSpec
import com.meowmba.shared.companion.MouthMode

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Original short stories on the existing performance clock. Sampling is pure:
 * the same progress drives the prop, contact beat and eye target, including on
 * pause/resume. Mouse play ends with an unharmed escape, never a capture. */
internal object LumenStoryTimeline {
    val accents = setOf(PerformanceAccent.MOUSE, PerformanceAccent.BOOK,
        PerformanceAccent.HAT_COWBOY, PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET)
    val hats = setOf(PerformanceAccent.HAT_COWBOY, PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET)
    data class Mouse(val point: Offset, val facing: Float, val stride: Float, val pounce: Float)
    data class Book(val opening: Float, val pageTurn: Float, val gaze: Offset)
    data class Hat(val anchor: Offset, val tipDegrees: Float, val focus: Offset)

    private fun p(v: Float) = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
    private fun ease(v: Float) = LumenScenes.ease(v)
    private fun slide(v: Float, a: Float, b: Float, from: Offset, to: Offset) =
        from + (to - from) * ease((v - a) / (b - a))
    private fun beat(v: Float, rise: Float, top: Float, hold: Float, end: Float) =
        ease((v - rise) / (top - rise)) * (1f - ease((v - hold) / (end - hold)))

    fun mouse(progress: Float): Mouse {
        val t = p(progress)
        fun position(v: Float): Offset = when {
            v < .18f -> slide(v, 0f, .18f, Offset(-.28f, .264f), Offset(-.12f, .25f))
            v < .35f -> slide(v, .18f, .35f, Offset(-.12f, .25f), Offset(.22f, .254f))
            v < .48f -> slide(v, .35f, .48f, Offset(.22f, .254f), Offset(.055f, .265f))
            v < .57f -> Offset(.055f, .265f)
            v < .75f -> slide(v, .57f, .75f, Offset(.055f, .265f), Offset(.28f, .245f))
            v < .84f -> Offset(.28f, .245f) // Looks back, safe beyond the playful paws.
            else -> slide(v, .84f, 1f, Offset(.28f, .245f), Offset(.33f, .265f))
        }
        val direction = position((t + .004f).coerceAtMost(1f)).x - position((t - .004f).coerceAtLeast(0f)).x
        val moving = (abs(direction) * 200f).coerceIn(0f, 1f)
        val bob = sin(t * 155f) * .004f * moving
        return Mouse(position(t) + Offset(0f, bob),
            if (direction < 0f || t in .76f.. .84f) -1f else 1f,
            sin(t * 155f) * moving, beat(t, .44f, .53f, .58f, .69f))
    }

    fun book(progress: Float): Book {
        val t = p(progress)
        val opening = ease((t - .035f) / .20f)
        val turning = ease((t - .49f) / .20f)
        val gaze = when {
            t < .22f -> Offset(0f, .245f)
            t < .34f -> slide(t, .22f, .34f, Offset(-.135f, .235f), Offset(.135f, .235f))
            t < .38f -> slide(t, .34f, .38f, Offset(.135f, .235f), Offset(-.135f, .258f))
            t < .49f -> slide(t, .38f, .49f, Offset(-.135f, .258f), Offset(.135f, .258f))
            t < .69f -> Offset(cos(turning * PI.toFloat()) * .16f,
                .23f - sin(turning * PI.toFloat()) * .06f)
            t < .82f -> slide(t, .69f, .82f, Offset(-.135f, .239f), Offset(.135f, .239f))
            else -> slide(t, .82f, 1f, Offset(.135f, .239f), Offset(0f, .13f))
        }
        return Book(opening, turning, gaze)
    }

    fun hat(accent: PerformanceAccent, progress: Float): Hat {
        val t = p(progress)
        return when (accent) {
            PerformanceAccent.HAT_COWBOY -> {
                val tip = beat(t, .22f, .34f, .44f, .60f)
                Hat(Offset(0f, -.252f - tip * .013f), -7f * tip,
                    slide(t, .48f, .76f, Offset(.21f, -.22f), Offset(.04f, -.02f)))
            }
            PerformanceAccent.HAT_WIZARD -> Hat(Offset(-.018f, -.232f), 0f,
                Offset(.29f + sin(t * 8f) * .025f, -.285f + cos(t * 6f) * .025f))
            PerformanceAccent.HAT_BERET -> Hat(Offset(.025f, -.23f), 0f,
                when {
                    t < .32f -> Offset(-.235f, .24f)
                    t < .70f -> slide(t, .32f, .7f, Offset(-.235f, .24f), brushTip(t))
                    else -> Offset(.04f, -.015f)
                })
            else -> Hat(Offset.Zero, 0f, Offset.Zero)
        }
    }

    fun brushTip(progress: Float): Offset {
        val t = p(progress)
        return Offset(.360f + sin(t * 10f) * .008f, .08f - beat(t, .28f, .44f, .62f, .79f) * .045f)
    }

    fun target(accent: PerformanceAccent, progress: Float): Offset = when (accent) {
        PerformanceAccent.MOUSE -> mouse(progress).point
        PerformanceAccent.BOOK -> book(progress).gaze
        in hats -> hat(accent, progress).focus
        else -> Offset.Zero
    }
}

/** Drawn in the scene's normalized coordinates. The caller already applied
 * screenRoll; headwear adds only frame.roll so a hat follows the same head as
 * the lenses without doubling phone tilt. Reusable paths avoid per-frame assets. */
internal class LumenStoryPropPainter {
    private val path = Path()

    fun draw(scope: DrawScope, accent: PerformanceAccent, progress: Float,
        glow: Color, feature: Color, frame: LumenFrame, arc: LumenStoryArcs.Pose? = null) = with(scope) {
        if (accent !in LumenStoryTimeline.accents) return
        val alpha = LumenScenes.envelope(progress) * (arc?.propVisibility ?: 1f)
        if (alpha <= .001f) return
        val p = progress.coerceIn(0f, 1f)
        val fur = lerp(feature, Color.White, .25f).copy(alpha = alpha)
        when (accent) {
            PerformanceAccent.MOUSE -> mouse(arc?.mouse ?: LumenStoryTimeline.mouse(p), fur, alpha)
            PerformanceAccent.BOOK -> book(arc?.book ?: LumenStoryTimeline.book(p), fur, alpha)
            in LumenStoryTimeline.hats -> {
                val hat = arc?.hat ?: LumenStoryTimeline.hat(accent, p)
                rotate(frame.roll, Offset.Zero) {
                    translate(hat.anchor.x, hat.anchor.y) {
                        rotate(hat.tipDegrees, Offset.Zero) {
                            when (accent) {
                                PerformanceAccent.HAT_COWBOY -> cowboy(alpha)
                                PerformanceAccent.HAT_WIZARD -> wizard(alpha)
                                PerformanceAccent.HAT_BERET -> beret(alpha)
                                else -> Unit
                            }
                        }
                    }
                }
                if (accent == PerformanceAccent.HAT_WIZARD) {
                    // The eye target and brightest star are exactly the same point.
                    val starPoint = hat.focus
                    val shimmer = .70f + sin(p * 18f) * .20f
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = alpha * .20f), Color.Transparent),
                        starPoint, .072f), .072f, starPoint)
                    star(starPoint, .025f, Color(0xFFFFE5A0).copy(alpha = alpha * shimmer))
                    star(Offset(.36f, -.17f), .010f, Color(0xFFAEC8F5).copy(alpha = alpha * .8f))
                    star(Offset(.18f, -.345f), .011f, Color(0xFFD5C3FF).copy(alpha = alpha * .7f))
                    drawLine(glow.copy(alpha = alpha * .18f), Offset(.18f, -.345f), starPoint, .0017f)
                    drawLine(glow.copy(alpha = alpha * .18f), starPoint, Offset(.36f, -.17f), .0017f)
                }
                if (accent == PerformanceAccent.HAT_BERET) painterTools(p, fur, alpha)
            }
            else -> Unit
        }
    }

    private fun DrawScope.mouse(mouse: LumenStoryTimeline.Mouse, fur: Color, alpha: Float) {
        // Two paws land where the mouse WAS. Its departure begins before these
        // settle, leaving a clear gap instead of depicting contact or injury.
        val reach = mouse.pounce
        if (reach > .001f) for (side in intArrayOf(-1, 1)) {
            paw(Offset(-.075f + side * .05f, .22f + reach * .044f), fur, alpha * reach)
        }
        val at = mouse.point
        drawOval(Color.Black.copy(alpha = alpha * .18f), at + Offset(-.058f, .022f), Size(.116f, .013f))
        translate(at.x, at.y) {
            scale(mouse.facing, 1f, Offset.Zero) {
                val coat = Color(0xFFB4ACBD).copy(alpha = alpha)
                val edge = Color(0xFF514956).copy(alpha = alpha * .78f)
                path.reset(); path.moveTo(-.038f, .012f)
                path.cubicTo(-.087f, -.013f, -.104f, .05f, -.135f, .025f)
                drawPath(path, Color(0xFFE4A9B4).copy(alpha = alpha), style = Stroke(.0042f, cap = StrokeCap.Round))
                for (side in intArrayOf(-1, 1)) drawLine(edge,
                    Offset(side * .026f, .020f), Offset(side * .026f + mouse.stride * .009f, .027f), .004f, StrokeCap.Round)
                drawOval(Brush.linearGradient(listOf(Color(0xFFE2DCE6).copy(alpha = alpha), coat, edge),
                    Offset(-.025f, -.027f), Offset(.032f, .023f)), Offset(-.048f, -.026f), Size(.095f, .054f))
                drawOval(edge.copy(alpha = alpha * .26f), Offset(-.047f, -.026f), Size(.095f, .054f), style = Stroke(.002f))
                drawCircle(coat, .023f, Offset(.019f, -.033f))
                drawCircle(Color(0xFFF0BDCC).copy(alpha = alpha), .016f, Offset(.019f, -.033f))
                drawCircle(Color(0xFFEFE9F2).copy(alpha = alpha * .7f), .007f, Offset(.013f, -.04f))
                drawCircle(Color(0xFF292332).copy(alpha = alpha), .006f, Offset(.040f, -.009f))
                drawCircle(Color.White.copy(alpha = alpha), .0018f, Offset(.042f, -.011f))
                drawCircle(Color(0xFFDF91A8).copy(alpha = alpha), .005f, Offset(.055f, .007f))
                for (i in -1..1) drawLine(edge.copy(alpha = alpha * .58f), Offset(.048f, .011f),
                    Offset(.078f, .011f + i * .010f), .0015f, StrokeCap.Round)
            }
        }
    }

    private fun DrawScope.book(book: LumenStoryTimeline.Book, fur: Color, alpha: Float) {
        val opening = book.opening
        val width = .024f + opening * .18f
        val cover = Color(0xFF365F72).copy(alpha = alpha)
        val page = Color(0xFFFFF0CA).copy(alpha = alpha)
        val ink = Color(0xFF877F73).copy(alpha = alpha * .58f)
        val origin = Offset(0f, .28f)
        translate(origin.x, origin.y) {
            drawOval(Color.Black.copy(alpha = alpha * .18f), Offset(-.24f, .068f), Size(.48f, .023f))
            // Covers form two slightly pitched planes around a fixed spine.
            for (side in intArrayOf(-1, 1)) scale(side.toFloat(), 1f, Offset.Zero) {
                path.reset(); path.moveTo(0f, -.056f); path.lineTo(width, -.08f)
                path.lineTo(width + .01f, .060f); path.lineTo(0f, .085f); path.close()
                drawPath(path, cover)
                drawPath(path, Color(0xFF9DD0C7).copy(alpha = alpha * .65f), style = Stroke(.003f))
                path.reset(); path.moveTo(.004f, -.060f)
                path.cubicTo(width * .40f, -.078f, width * .7f, -.073f, width - .006f, -.078f)
                path.lineTo(width - .004f, .052f)
                path.cubicTo(width * .68f, .049f, width * .35f, .070f, .003f, .075f); path.close()
                drawPath(path, Brush.linearGradient(listOf(Color(0xFFD9C8A7).copy(alpha = alpha), page),
                    Offset.Zero, Offset(width, 0f)))
                if (opening > .15f) for (row in 0..4) {
                    val y = -.040f + row * .019f
                    val length = (if (row % 3 == 0) .73f else .86f) * width
                    drawLine(ink, Offset(width * .13f, y), Offset(length, y - .010f), .002f, StrokeCap.Round)
                }
                drawLine(Color(0xFFC9B895).copy(alpha = alpha * .8f), Offset(.02f, .067f),
                    Offset(width - .004f, .048f), .0018f)
            }
            drawLine(Color(0xFFB79051).copy(alpha = alpha), Offset(0f, -.057f), Offset(0f, .079f), .004f)
            // A physical sheet curls up from the right page, crosses the spine,
            // then lands on the left. Its two faces have different paper tones.
            if (book.pageTurn > 0f && book.pageTurn < 1f) {
                val angle = book.pageTurn * PI.toFloat()
                val tip = cos(angle) * (width - .014f)
                val lift = sin(angle) * .079f
                path.reset(); path.moveTo(.001f, -.060f)
                path.cubicTo(tip * .34f + lift * .24f, -.065f - lift,
                    tip * .78f + lift * .18f, -.074f - lift, tip, -.074f - lift * .35f)
                path.lineTo(tip, .047f - lift * .35f)
                path.cubicTo(tip * .65f + lift * .24f, .042f - lift,
                    tip * .28f + lift * .24f, .058f - lift, .001f, .075f); path.close()
                drawPath(path, if (book.pageTurn < .5f) page else Color(0xFFE4D5AE).copy(alpha = alpha))
                drawPath(path, Color(0xFF9C9078).copy(alpha = alpha * .5f), style = Stroke(.002f))
                drawLine(Color.White.copy(alpha = alpha * .7f), Offset(tip, -.073f - lift * .35f),
                    Offset(tip, .046f - lift * .35f), .0025f)
            }
            path.reset(); path.moveTo(.107f * opening, .058f); path.lineTo(.107f * opening, .106f)
            path.lineTo(.12f * opening, .097f); path.lineTo(.132f * opening, .102f)
            path.lineTo(.132f * opening, .055f); path.close()
            drawPath(path, Color(0xFFE9987F).copy(alpha = alpha))
            for (side in intArrayOf(-1, 1)) paw(Offset(side * (width + .013f), .02f), fur, alpha * opening)
        }
    }

    private fun DrawScope.cowboy(alpha: Float) {
        val leather = Color(0xFFC9965B).copy(alpha = alpha)
        val dark = Color(0xFF704A32).copy(alpha = alpha)
        // Upturned sides, pinched crown, turquoise band and a small brass buckle.
        path.reset(); path.moveTo(-.282f, -.024f)
        path.cubicTo(-.235f, .018f, -.192f, -.002f, -.14f, -.002f)
        path.lineTo(.14f, -.002f)
        path.cubicTo(.20f, .009f, .242f, .012f, .282f, -.03f)
        path.cubicTo(.253f, .059f, -.251f, .060f, -.282f, -.024f); path.close()
        drawPath(path, Brush.linearGradient(listOf(leather, dark), Offset(0f, -.025f), Offset(0f, .046f)))
        drawPath(path, dark, style = Stroke(.003f))
        path.reset(); path.moveTo(-.148f, .006f); path.lineTo(-.124f, -.103f)
        path.cubicTo(-.11f, -.143f, -.055f, -.109f, -.019f, -.117f)
        path.cubicTo(.026f, -.131f, .092f, -.139f, .121f, -.101f)
        path.lineTo(.148f, .006f); path.close()
        drawPath(path, Brush.linearGradient(listOf(Color(0xFFF1C889).copy(alpha = alpha), leather, dark),
            Offset(-.10f, -.13f), Offset(.16f, .026f)))
        drawPath(path, dark.copy(alpha = alpha * .65f), style = Stroke(.003f))
        path.reset(); path.moveTo(-.142f, -.013f); path.quadraticBezierTo(0f, .009f, .142f, -.013f)
        drawPath(path, Color(0xFF287E7D).copy(alpha = alpha), style = Stroke(.018f))
        drawRoundRect(Color(0xFFFFD693).copy(alpha = alpha), Offset(-.015f, -.023f),
            Size(.03f, .021f), CornerRadius(.004f), style = Stroke(.0035f))
        path.reset(); path.moveTo(-.081f, -.106f); path.quadraticBezierTo(-.033f, -.084f, .079f, -.106f)
        drawPath(path, dark.copy(alpha = alpha * .46f), style = Stroke(.004f, cap = StrokeCap.Round))
    }

    private fun DrawScope.wizard(alpha: Float) {
        val blue = Color(0xFF5964A7).copy(alpha = alpha)
        val deep = Color(0xFF30365B).copy(alpha = alpha)
        drawOval(deep, Offset(-.24f, -.017f), Size(.48f, .053f))
        drawOval(Color(0xFF8B90D5).copy(alpha = alpha), Offset(-.236f, -.025f), Size(.472f, .048f))
        path.reset(); path.moveTo(-.151f, .005f)
        path.cubicTo(-.094f, -.04f, -.068f, -.112f, -.026f, -.17f)
        path.cubicTo(.001f, -.199f, .049f, -.143f, .095f, -.155f)
        path.cubicTo(.089f, -.124f, .045f, -.122f, .046f, -.101f)
        path.cubicTo(.063f, -.06f, .092f, -.023f, .153f, .005f); path.close()
        drawPath(path, Brush.linearGradient(listOf(Color(0xFF9494DA).copy(alpha = alpha), blue, deep),
            Offset(-.12f, -.15f), Offset(.15f, .02f)))
        drawPath(path, deep, style = Stroke(.0025f))
        drawArc(Color(0xFFEAC374).copy(alpha = alpha), 8f, 163f, false,
            Offset(-.145f, -.048f), Size(.29f, .052f), style = Stroke(.013f))
        star(Offset(-.03f, -.097f), .021f, Color(0xFFFFE0A2).copy(alpha = alpha))
        star(Offset(.069f, -.038f), .010f, Color(0xFFD3DCFF).copy(alpha = alpha))
        drawCircle(Color(0xFFFFE6AC).copy(alpha = alpha), .0035f, Offset(-.079f, -.035f))
    }

    private fun DrawScope.beret(alpha: Float) {
        val wine = Color(0xFFAE4961).copy(alpha = alpha)
        val edge = Color(0xFF612B41).copy(alpha = alpha)
        path.reset(); path.moveTo(-.226f, -.021f)
        path.cubicTo(-.262f, -.078f, -.144f, -.111f, -.052f, -.108f)
        path.cubicTo(.035f, -.157f, .185f, -.081f, .215f, -.039f)
        path.cubicTo(.241f, .006f, .079f, .023f, -.081f, .005f)
        path.cubicTo(-.157f, .006f, -.204f, -.003f, -.226f, -.021f); path.close()
        drawPath(path, Brush.linearGradient(listOf(Color(0xFFE17B88).copy(alpha = alpha), wine, edge),
            Offset(-.12f, -.11f), Offset(.14f, .021f)))
        drawPath(path, edge.copy(alpha = alpha * .78f), style = Stroke(.003f))
        path.reset(); path.moveTo(-.199f, -.006f); path.quadraticBezierTo(0f, .026f, .189f, -.005f)
        drawPath(path, edge, style = Stroke(.014f, cap = StrokeCap.Round))
        drawLine(edge, Offset(-.022f, -.109f), Offset(-.028f, -.128f), .011f, StrokeCap.Round)
        drawOval(Color(0xFF70B8BD).copy(alpha = alpha * .8f), Offset(.13f, -.057f), Size(.024f, .010f))
        drawOval(Color(0xFFF5D293).copy(alpha = alpha * .85f), Offset(.145f, -.048f), Size(.013f, .008f))
    }

    private fun DrawScope.painterTools(progress: Float, fur: Color, alpha: Float) {
        val at = Offset(-.25f, .247f)
        rotate(-14f, at) {
            drawOval(Color(0xFFD1A875).copy(alpha = alpha), at - Offset(.071f, .045f), Size(.142f, .09f))
            drawOval(Color(0xFF765B40).copy(alpha = alpha * .75f), at - Offset(.071f, .045f),
                Size(.142f, .09f), style = Stroke(.0025f))
            drawOval(Color(0xFF34454B).copy(alpha = alpha), at + Offset(.024f, -.013f), Size(.020f, .030f))
            listOf(Color(0xFFE67970), Color(0xFFFFD470), Color(0xFF6EC6C5), Color(0xFF8E8CCB)).forEachIndexed { i, c ->
                drawCircle(c.copy(alpha = alpha), .010f, at + Offset(-.043f + i * .022f, -.015f - sin(i * 1.1f) * .010f))
            }
            paw(at + Offset(.029f, .033f), fur, alpha)
        }
        val tip = LumenStoryTimeline.brushTip(progress)
        val handle = Offset(.31f, .285f)
        val ferrule = tip + Offset(-.016f, .042f)
        drawLine(Color(0xFFBC805B).copy(alpha = alpha), handle, ferrule, .010f, StrokeCap.Round)
        drawLine(Color(0xFFB7C9D1).copy(alpha = alpha), ferrule, tip + Offset(-.005f, .019f), .014f, StrokeCap.Round)
        path.reset(); path.moveTo(tip.x - .013f, tip.y + .020f)
        path.quadraticBezierTo(tip.x - .012f, tip.y + .006f, tip.x + .007f, tip.y - .006f)
        path.quadraticBezierTo(tip.x + .008f, tip.y + .015f, tip.x + .001f, tip.y + .026f); path.close()
        drawPath(path, Color(0xFF6EC6C5).copy(alpha = alpha))
        paw(handle - Offset(.012f, .014f), fur, alpha)
    }

    private fun DrawScope.paw(at: Offset, fur: Color, alpha: Float) {
        drawOval(Brush.radialGradient(listOf(fur.copy(alpha = alpha),
            lerp(fur, Color(0xFF656E78), .38f).copy(alpha = alpha)), at - Offset(.007f, .006f), .042f),
            at - Offset(.028f, .021f), Size(.056f, .042f))
        for (i in -1..1) drawLine(Color(0xFF68747D).copy(alpha = alpha * .40f),
            at + Offset(i * .009f, -.011f), at + Offset(i * .009f, -.004f), .0017f, StrokeCap.Round)
    }

    private fun DrawScope.star(at: Offset, radius: Float, color: Color) {
        path.reset()
        for (i in 0 until 8) {
            val angle = i * PI.toFloat() / 4f - PI.toFloat() / 2f
            val r = radius * if (i % 2 == 0) 1f else .29f
            val point = at + Offset(cos(angle) * r, sin(angle) * r)
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close(); drawPath(path, color)
    }
}
