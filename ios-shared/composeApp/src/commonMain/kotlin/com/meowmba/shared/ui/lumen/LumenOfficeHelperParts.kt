package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.cos
import kotlin.math.sin

/**
 * Office-helper silhouettes in the shared Lumen root's normalized coordinates.
 * The paperclip is one continuous piece of wire; the wizard is detached hat,
 * hair and beard around the existing eyes, without a filled-in head. This painter
 * owns no clock, eye animation, sensor, privacy mark or interaction state.
 */
internal class LumenOfficeHelperParts {
    private val wire = Path()
    private val shape = Path()
    private val detail = Path()
    private val star = Path().apply {
        repeat(10) { index ->
            val angle = -kotlin.math.PI / 2 + index * kotlin.math.PI / 5
            val radius = if (index % 2 == 0) 1f else .43f
            val x = cos(angle).toFloat() * radius
            val y = sin(angle).toFloat() * radius
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    private val outline = Stroke(.009f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val wireOutline = Stroke(.034f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val wireBody = Stroke(.024f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val wireHighlight = Stroke(.0065f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val fine = Stroke(.004f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun backdrop(scope: DrawScope, center: Offset, unit: Float, frame: LumenFrame) = with(scope) {
        if (!unit.isFinite() || unit <= 0f) return@with
        val clip = frame.form.clippy.weight()
        val wizard = frame.form.wizard.weight()
        if (maxOf(clip, wizard) <= .005f) return@with
        translate(center.x, center.y) {
            scale(unit, unit, Offset.Zero) {
                // Match the root's proportional costume allowance throughout
                // a morph. Opacity and geometry follow the same spring weight;
                // a faint new hat never appears at its full height in a small stage.
                if (clip > .005f) scale(.5f + .5f * clip, .5f + .5f * clip, Offset.Zero) {
                    paperclip(frame, clip)
                }
                if (wizard > .005f) scale(.5f + .5f * wizard, .5f + .5f * wizard, Offset.Zero) {
                    beard(frame, wizard)
                    hat(frame, wizard)
                    wand(frame, wizard)
                }
            }
        }
    }

    fun foreground(scope: DrawScope, center: Offset, unit: Float, frame: LumenFrame) = with(scope) {
        if (!unit.isFinite() || unit <= 0f) return@with
        val clip = frame.form.clippy.weight()
        val wizard = frame.form.wizard.weight()
        if (maxOf(clip, wizard) <= .005f) return@with
        translate(center.x, center.y) {
            scale(unit, unit, Offset.Zero) {
                if (clip > .005f) scale(.5f + .5f * clip, .5f + .5f * clip, Offset.Zero) {
                    brows(frame, clip, bushy = false)
                    speechMouth(frame, clip, .205f, .068f)
                }
                if (wizard > .005f) scale(.5f + .5f * wizard, .5f + .5f * wizard, Offset.Zero) {
                    brows(frame, wizard, bushy = true)
                    speechMouth(frame, wizard, .240f, .064f)
                    moustache(frame, wizard)
                }
            }
        }
    }

    private fun DrawScope.paperclip(f: LumenFrame, alpha: Float) {
        // Curves flex with the established head/breath springs. Parallel stems
        // and a second, shorter return preserve a paperclip rather than a ring.
        val bend = f.headX.signed() * .018f + f.earLag.finite().coerceIn(-12f, 12f) * .0007f
        val breath = f.breath.signed() * .006f
        val friendly = f.warmth.weight() * .008f
        wire.reset()
        wire.moveTo(-.215f + bend, -.065f)
        wire.cubicTo(-.232f + bend, -.272f, -.151f + bend, -.349f, .011f + bend, -.328f)
        wire.cubicTo(.168f + bend, -.312f, .187f + bend, -.219f, .181f, -.097f)
        wire.lineTo(.181f - bend * .35f, .270f + breath)
        wire.cubicTo(.181f - bend * .35f, .448f + breath, -.241f - bend * .25f, .480f - friendly,
            -.245f - bend * .25f, .286f + breath)
        wire.lineTo(-.248f + bend * .4f, .055f)
        wire.cubicTo(-.250f + bend * .4f, -.076f, -.077f + bend, -.090f, -.070f + bend, .045f)
        wire.lineTo(-.057f - bend * .3f, .255f + breath)
        wire.cubicTo(-.053f - bend * .3f, .315f + breath, .079f - bend * .3f, .306f + breath,
            .077f - bend * .3f, .247f + breath)
        wire.lineTo(.071f + bend * .25f, .076f)
        drawPath(wire, INK.copy(alpha = alpha), style = wireOutline)
        drawPath(wire, SILVER.copy(alpha = alpha), style = wireBody)
        // Narrow off-center light keeps the thick cartoon outline and makes
        // the silver readable against both dark and very bright backgrounds.
        translate(-.004f, -.003f) {
            drawPath(wire, Color(0xFFFFFBEE).copy(alpha = alpha * .92f), style = wireHighlight)
        }
        detail.reset()
        detail.moveTo(-.224f, .293f + breath)
        detail.cubicTo(-.220f, .384f, -.084f, .416f, .025f, .382f)
        drawPath(detail, Color(0xFF7A8395).copy(alpha = alpha * .80f), style = fine)
    }

    private fun DrawScope.hat(f: LumenFrame, alpha: Float) {
        val bend = f.earLag.finite().coerceIn(-12f, 12f) * .001f + f.headX.signed() * .010f
        val lift = f.breath.signed() * .003f
        translate(0f, lift) {
            shape.reset()
            shape.moveTo(-.251f, -.218f)
            shape.cubicTo(-.213f, -.298f, -.178f, -.389f, -.121f + bend, -.456f)
            shape.cubicTo(-.076f + bend, -.520f, .033f + bend, -.523f, .120f + bend, -.508f)
            shape.cubicTo(.185f + bend, -.496f, .226f + bend, -.472f, .245f + bend, -.435f)
            shape.cubicTo(.173f + bend, -.459f, .124f + bend, -.432f, .115f + bend, -.387f)
            shape.cubicTo(.129f, -.330f, .197f, -.265f, .251f, -.218f)
            shape.close()
            filled(HAT_BLUE, alpha)

            // A single cel-shadow describes the folded cone without filling
            // the space around or between the shared eyes.
            detail.reset()
            detail.moveTo(.091f + bend, -.493f)
            detail.cubicTo(.013f + bend, -.435f, .023f + bend, -.360f, .060f, -.287f)
            detail.lineTo(.137f, -.223f)
            detail.lineTo(.240f, -.223f)
            detail.cubicTo(.177f, -.285f, .126f, -.337f, .115f + bend, -.387f)
            detail.cubicTo(.125f + bend, -.429f, .171f + bend, -.455f, .230f + bend, -.442f)
            detail.cubicTo(.186f + bend, -.483f, .141f + bend, -.495f, .091f + bend, -.493f)
            detail.close()
            drawPath(detail, HAT_SHADOW.copy(alpha = alpha))
            detail.reset()
            detail.moveTo(-.207f, -.289f)
            detail.cubicTo(-.173f, -.380f, -.119f + bend, -.456f, -.054f + bend, -.483f)
            drawPath(detail, Color(0xFF9AA9F3).copy(alpha = alpha * .80f), style = Stroke(.009f, cap = StrokeCap.Round))

            hatStar(Offset(-.119f + bend * .25f, -.342f), .025f, alpha)
            hatStar(Offset(.048f + bend * .35f, -.302f), .020f, alpha)
            hatStar(Offset(-.021f + bend * .6f, -.448f), .015f, alpha)
            // The deep oval brim makes the cone read as a worn wizard hat.
            shape.reset()
            shape.moveTo(-.321f, -.211f)
            shape.cubicTo(-.254f, -.252f, .219f, -.255f, .321f, -.210f)
            shape.cubicTo(.333f, -.181f, .148f, -.172f, .015f, -.184f)
            shape.cubicTo(-.160f, -.173f, -.329f, -.175f, -.321f, -.211f)
            shape.close()
            filled(HAT_SHADOW, alpha)
            detail.reset()
            detail.moveTo(-.292f, -.214f)
            detail.cubicTo(-.148f, -.245f, .179f, -.240f, .291f, -.210f)
            drawPath(detail, Color(0xFFB1A7EF).copy(alpha = alpha), style = Stroke(.012f, cap = StrokeCap.Round))
            detail.reset()
            detail.moveTo(-.221f, -.241f)
            detail.quadraticBezierTo(.005f, -.270f, .223f, -.239f)
            drawPath(detail, Color(0xFFCEA5EB).copy(alpha = alpha), style = Stroke(.019f, cap = StrokeCap.Round))
            drawPath(detail, INK.copy(alpha = alpha * .7f), style = Stroke(.003f, cap = StrokeCap.Round))
        }
    }

    private fun DrawScope.beard(f: LumenFrame, alpha: Float) {
        val sway = f.headX.signed() * -.013f + f.earLag.finite().coerceIn(-12f, 12f) * .00065f
        val breath = f.breath.signed() * .004f
        shape.reset()
        shape.moveTo(-.229f, .127f)
        shape.cubicTo(-.275f, .218f, -.212f, .347f, -.162f + sway, .390f)
        shape.quadraticBezierTo(-.161f + sway, .373f, -.151f + sway, .367f)
        shape.cubicTo(-.136f + sway, .426f, -.080f + sway, .454f, -.025f + sway, .473f + breath)
        shape.quadraticBezierTo(.018f + sway, .445f, .044f + sway, .423f)
        shape.quadraticBezierTo(.049f + sway, .443f, .043f + sway, .457f)
        shape.cubicTo(.136f + sway, .402f, .195f, .315f, .206f, .262f)
        shape.quadraticBezierTo(.230f, .230f, .245f, .172f)
        shape.quadraticBezierTo(.214f, .117f, .182f, .130f)
        shape.cubicTo(.111f, .155f, .081f, .199f, 0f, .218f)
        shape.cubicTo(-.085f, .199f, -.120f, .151f, -.229f, .127f)
        shape.close()
        filled(HAIR, alpha)

        detail.reset()
        detail.moveTo(.181f, .179f)
        detail.cubicTo(.162f, .301f, .099f + sway, .394f, -.025f + sway, .473f + breath)
        detail.cubicTo(.020f + sway, .411f, .034f + sway, .342f, .031f, .298f)
        detail.cubicTo(.085f, .303f, .121f, .215f, .181f, .179f)
        detail.close()
        drawPath(detail, HAIR_SHADE.copy(alpha = alpha))
        // A few tapered curls keep the beard graphic and readable at small sizes.
        detail.reset()
        detail.moveTo(-.190f, .212f)
        detail.cubicTo(-.179f, .288f, -.141f + sway, .358f, -.098f + sway, .389f)
        detail.moveTo(-.104f, .279f)
        detail.cubicTo(-.105f, .341f, -.068f + sway, .398f, -.045f + sway, .422f)
        detail.moveTo(.159f, .241f)
        detail.cubicTo(.135f, .326f, .100f + sway, .362f, .078f + sway, .379f)
        drawPath(detail, Color(0xFF9FA4C0).copy(alpha = alpha * .80f), style = fine)
    }

    private fun DrawScope.brows(f: LumenFrame, alpha: Float, bushy: Boolean) {
        for (side in -1..1 step 2) {
            val open = (if (side < 0) f.leftOpen else f.rightOpen).weight()
            val lift = f.innerLift.finite().coerceIn(-.5f, .55f)
            val y = (if (bushy) -.178f else -.217f) - lift * .037f + (1f - open) * .012f
            val asymmetric = side * f.earAsymmetry.finite().coerceIn(-8f, 8f) * .001f
            translate(side * .211f, y + asymmetric) {
                if (bushy) {
                    scale(side.toFloat(), 1f, Offset.Zero) {
                        shape.reset(); shape.moveTo(-.093f, .017f)
                        shape.cubicTo(-.073f, -.027f, -.004f, -.040f, .059f, -.026f)
                        shape.quadraticBezierTo(.088f, -.023f, .112f, .001f)
                        shape.lineTo(.079f, -.002f)
                        shape.quadraticBezierTo(.088f, .012f, .092f, .021f)
                        shape.cubicTo(.021f, -.006f, -.027f, -.004f, -.093f, .017f)
                        shape.close(); filled(HAIR, alpha)
                        detail.reset(); detail.moveTo(-.052f, -.005f)
                        detail.quadraticBezierTo(.002f, -.018f, .047f, -.009f)
                        drawPath(detail, Color.White.copy(alpha = alpha), style = fine)
                    }
                } else {
                    detail.reset(); detail.moveTo(-.065f, .007f - side * lift * .022f)
                    detail.quadraticBezierTo(.005f, -.037f - f.concern.weight() * .009f,
                        .071f, .003f + side * lift * .022f)
                    drawPath(detail, INK.copy(alpha = alpha), style = Stroke(.017f, cap = StrokeCap.Round))
                }
            }
        }
    }

    private fun DrawScope.moustache(f: LumenFrame, alpha: Float) {
        val speaking = f.mouthOpen.weight() * .006f
        val curl = f.smile.weight() * .012f - f.concern.weight() * .007f
        val gaze = f.gazeX.signed() * .008f
        translate(gaze, speaking) {
            for (side in -1..1 step 2) scale(side.toFloat(), 1f, Offset.Zero) {
                shape.reset(); shape.moveTo(0f, .208f)
                shape.cubicTo(.045f, .184f, .081f, .199f, .107f, .215f)
                shape.quadraticBezierTo(.154f, .235f - curl, .183f, .210f - curl)
                shape.cubicTo(.180f, .268f - curl, .102f, .276f, .058f, .244f)
                shape.quadraticBezierTo(.022f, .220f, 0f, .227f)
                shape.close(); filled(HAIR, alpha)
                detail.reset(); detail.moveTo(.028f, .214f)
                detail.quadraticBezierTo(.092f, .207f, .141f, .246f - curl)
                drawPath(detail, Color.White.copy(alpha = alpha), style = Stroke(.006f, cap = StrokeCap.Round))
            }
        }
    }

    private fun DrawScope.speechMouth(f: LumenFrame, alpha: Float, y: Float, half: Float) {
        val smile = f.smile.weight()
        val curve = .016f + smile * .023f - f.concern.weight() * .027f
        val opening = f.mouthOpen.weight() * .057f
        translate(f.gazeX.signed() * .008f, 0f) {
            detail.reset(); detail.moveTo(-half, y)
            detail.cubicTo(-half * .4f, y + curve, half * .4f, y + curve, half, y)
            if (opening > .002f) {
                detail.cubicTo(half * .6f, y + curve + opening, -half * .6f, y + curve + opening, -half, y)
                detail.close()
                drawPath(detail, INK.copy(alpha = alpha))
                // One soft tongue highlight follows speech, not a separate mouth clock.
                val lipY = y + curve + opening * .77f
                drawLine(Color(0xFFE890A3).copy(alpha = alpha * .85f), Offset(-half * .29f, lipY),
                    Offset(half * .29f, lipY), opening * .18f, StrokeCap.Round)
            }
            drawPath(detail, INK.copy(alpha = alpha), style = Stroke(.0055f, cap = StrokeCap.Round))
        }
    }

    private fun DrawScope.wand(f: LumenFrame, alpha: Float) {
        val tip = Offset(.435f + f.gazeX.signed() * .008f, .105f + f.headY.signed() * .009f)
        val base = Offset(.362f, .396f + f.breath.signed() * .003f)
        drawLine(INK.copy(alpha = alpha), base, tip, .023f, StrokeCap.Round)
        drawLine(Color(0xFF765588).copy(alpha = alpha), base, tip, .014f, StrokeCap.Round)
        drawLine(Color(0xFFBA94D0).copy(alpha = alpha), base + Offset(-.003f, -.004f),
            tip + Offset(-.003f, -.004f), .004f, StrokeCap.Round)
        hatStar(tip, .030f, alpha)
        // Tiny highlights brighten with existing engagement/affection. They
        // stay still when the shared animation clock is paused.
        val sparkle = alpha * (.35f + f.engagement.weight() * .35f + f.warmth.weight() * .25f)
        drawLine(Color(0xFFFFF4B9).copy(alpha = sparkle), tip + Offset(.034f, -.038f),
            tip + Offset(.040f, -.051f), .003f, StrokeCap.Round)
        drawLine(Color(0xFFFFF4B9).copy(alpha = sparkle), tip + Offset(-.043f, -.035f),
            tip + Offset(-.052f, -.043f), .003f, StrokeCap.Round)
    }

    private fun DrawScope.hatStar(center: Offset, radius: Float, alpha: Float) {
        translate(center.x, center.y) {
            scale(radius, radius, Offset.Zero) {
                drawPath(star, Color(0xFFFFDA76).copy(alpha = alpha))
                drawPath(star, INK.copy(alpha = alpha), style = Stroke(.13f, join = StrokeJoin.Round))
                drawCircle(Color(0xFFFFF4CD).copy(alpha = alpha), .19f, Offset(-.10f, -.14f))
            }
        }
    }

    private fun DrawScope.filled(color: Color, alpha: Float) {
        drawPath(shape, color.copy(alpha = alpha))
        drawPath(shape, INK.copy(alpha = alpha), style = outline)
    }

    private fun Float.finite(): Float = if (isFinite()) this else 0f
    private fun Float.weight(): Float = finite().coerceIn(0f, 1f)
    private fun Float.signed(): Float = finite().coerceIn(-1f, 1f)

    private companion object {
        val INK = Color(0xFF28243C)
        val SILVER = Color(0xFFC1CCD6)
        val HAT_BLUE = Color(0xFF647BCC)
        val HAT_SHADOW = Color(0xFF50427E)
        val HAIR = Color(0xFFF5F1E5)
        val HAIR_SHADE = Color(0xFFC6C7D9)
    }
}
