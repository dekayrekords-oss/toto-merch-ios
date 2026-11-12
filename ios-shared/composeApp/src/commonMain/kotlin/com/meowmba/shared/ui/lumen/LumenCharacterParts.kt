package com.meowmba.shared.ui.lumen

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

/** Original floating vector anatomy. All parts follow the shared damped pose; none owns
 * a timer, touch target or privacy state. The supplied pictures guide silhouette
 * and expression, and are not bundled as static faces or opaque heads. */
internal class LumenCharacterParts {
    private val shape = Path()
    private val detail = Path()

    fun backdrop(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame, tint: Color) = with(scope) {
        translate(center.x, center.y) { scale(unit, unit, Offset.Zero) {
            val rabbit = f.form.rabbitFur
            if (rabbit > .005f) {
                val fur = lerp(Color(0xFFE8DDD2), tint, .12f)
                for (side in intArrayOf(-1, 1)) {
                    scale(side.toFloat(), 1f, Offset.Zero) {
                        scale(1f, .1f + .9f * rabbit, Offset(.21f, -.20f)) {
                        rotate(4f - f.earLift * .35f + side * f.earLag * .55f, Offset(.21f, -.20f)) {
                            shape.reset(); shape.moveTo(.13f, -.19f)
                            shape.cubicTo(.09f, -.35f, .16f, -.65f, .26f, -.62f)
                            shape.cubicTo(.36f, -.57f, .35f, -.35f, .28f, -.18f); shape.close()
                            plush(fur, rabbit, Offset(.20f, -.47f), .32f)
                            detail.reset(); detail.moveTo(.19f, -.24f)
                            detail.cubicTo(.16f, -.39f, .20f, -.56f, .25f, -.56f)
                            detail.cubicTo(.30f, -.51f, .28f, -.35f, .24f, -.24f); detail.close()
                            drawPath(detail, Brush.linearGradient(listOf(Color(0xFFF4B5B5).copy(alpha = rabbit),
                                Color(0xFFBA7987).copy(alpha = rabbit)), Offset(.20f, -.53f), Offset(.25f, -.22f)))
                            drawLine(Color.White.copy(alpha = rabbit * .35f), Offset(.22f, -.49f),
                                Offset(.205f, -.32f), .008f, StrokeCap.Round)
                        }
                        }
                    }
                }
            }
            val pig = f.form.pigSkin
            if (pig > .005f) {
                val pink = lerp(Color(0xFFF3AAA9), tint, .09f)
                for (side in intArrayOf(-1, 1)) scale(side.toFloat(), 1f, Offset.Zero) {
                    scale(.6f + .4f * pig, .2f + .8f * pig, Offset(.25f, -.18f)) {
                    rotate(f.earLag * side * .4f - f.earLift * .25f, Offset(.25f, -.18f)) {
                        shape.reset(); shape.moveTo(.18f, -.22f)
                        shape.cubicTo(.23f, -.38f, .40f, -.36f, .45f, -.32f)
                        shape.cubicTo(.43f, -.22f, .36f, -.12f, .30f, -.145f)
                        shape.quadraticBezierTo(.26f, -.235f, .18f, -.22f); shape.close()
                        plush(pink, pig, Offset(.29f, -.28f), .20f)
                        detail.reset(); detail.moveTo(.26f, -.255f)
                        detail.quadraticBezierTo(.32f, -.31f, .408f, -.30f)
                        detail.quadraticBezierTo(.36f, -.245f, .32f, -.185f); detail.close()
                        drawPath(detail, Color(0xFFD87589).copy(alpha = pig * .75f))
                    }
                    }
                }
                for (side in intArrayOf(-1, 1)) softOval(Color(0xFFF16F8B), pig * .25f,
                    Offset(side * .29f, .12f), Size(.10f, .065f))
            }
            val robot = f.form.robotShell
            if (robot > .005f) {
                val metal = lerp(Color(0xFF76AEBE), tint, .30f)
                for (side in intArrayOf(-1, 1)) {
                    drawRoundRect(Color(0xFF233C4B).copy(alpha = robot), Offset(side * (.36f + .045f * robot) - .047f, -.09f),
                        Size(.094f, .18f), CornerRadius(.037f))
                    drawRoundRect(metal.copy(alpha = robot), Offset(side * (.37f + .045f * robot) - .022f, -.058f),
                        Size(.044f, .116f), CornerRadius(.018f))
                }
                val antenna = Offset(.09f + f.earLag * .0007f * robot, -.25f - .175f * robot)
                drawLine(Color(0xFF456A7A).copy(alpha = robot), Offset(.06f, -.25f), antenna, .013f, StrokeCap.Round)
                softOval(Color(0xFFFFCB77), robot, antenna, Size(.051f, .051f))
                // Detached brow bridge and side receivers suggest a robot;
                // there is deliberately no head shell behind the lenses.
                drawLine(metal.copy(alpha = robot * .65f), Offset(-.04f, -.24f), Offset(.07f, -.24f), .010f, StrokeCap.Round)
            }
            val panda = f.form.pandaFur
            if (panda > .005f) {
                for (side in intArrayOf(-1, 1)) {
                    val ear = Offset(side * .285f, -.15f - .115f * panda - f.earLift * .001f * panda)
                    softOval(Color(0xFF303640), panda, ear, Size(.205f, .13f + .075f * panda))
                    softOval(Color(0xFFB58B92), panda * .8f, ear + Offset(0f, .014f), Size(.108f, .07f + .046f * panda))
                }
            }
        } }
    }

    fun eyeSurround(scope: DrawScope, side: Int, unit: Float, f: LumenFrame, tint: Color) = with(scope) {
        scale(unit, unit, Offset.Zero) {
            if (f.form.pandaFur > .005f) rotate(-side * 14f, Offset.Zero) {
                softOval(Color(0xFF30343E), f.form.pandaFur, Offset(0f, .018f), Size(.353f, .435f))
            }
            val robot = f.form.robotShell
            if (robot > .005f) {
                drawCircle(Color(0xFF233F4F).copy(alpha = robot), .184f)
                drawCircle(Brush.linearGradient(listOf(lerp(tint, Color.White, .6f).copy(alpha = robot),
                    Color(0xFF36637C).copy(alpha = robot)), Offset(-.13f, -.13f), Offset(.13f, .13f)),
                    .172f, style = Stroke(.018f))
                drawCircle(Color(0xFF0C2635).copy(alpha = robot), .159f)
            }
            val browAlpha = maxOf(f.form.rabbitFur, f.form.pigSkin, f.form.alienSkin, f.form.pandaFur)
            if (browAlpha > .005f) {
                val y = -.225f - f.innerLift * .055f
                detail.reset(); detail.moveTo(-.06f, y + side * f.innerLift * .025f)
                detail.quadraticBezierTo(0f, y - .037f - f.concern * .014f, .06f, y - side * f.innerLift * .025f)
                drawPath(detail, Color(0xFF53484C).copy(alpha = browAlpha * .67f), style = Stroke(.010f, cap = StrokeCap.Round))
            }
        }
    }

    fun muzzle(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame, tint: Color) = with(scope) {
        translate(center.x + f.gazeX * unit * .007f, center.y) { scale(unit, unit, Offset.Zero) {
            val furry = (f.form.rabbitFur + f.form.pandaFur).coerceIn(0f, 1f)
            if (furry > .005f) {
                val cream = lerp(Color(0xFFF9F3E8), tint, .055f)
                for (side in intArrayOf(-1, 1)) softOval(cream, furry, Offset(side * .064f, .175f), Size(.18f, .105f))
                val noseColor = lerp(Color(0xFFE892A7), Color(0xFF242F3C), f.form.pandaFur)
                shape.reset(); shape.moveTo(-.05f, .132f)
                shape.cubicTo(-.06f, .10f, .06f, .10f, .05f, .132f)
                shape.quadraticBezierTo(.015f, .18f, 0f, .166f)
                shape.quadraticBezierTo(-.025f, .17f, -.05f, .132f); shape.close()
                plush(noseColor, furry, Offset(-.02f, .115f), .09f)
                smile(f, furry, .218f, .070f)
                if (f.form.rabbitFur > .005f) {
                    drawRoundRect(Color(0xFFFFFAED).copy(alpha = f.form.rabbitFur), Offset(-.025f, .226f),
                        Size(.05f, .024f + f.mouthOpen * .020f), CornerRadius(.006f))
                    drawLine(Color(0xFFC4B9AD).copy(alpha = f.form.rabbitFur), Offset(0f, .23f),
                        Offset(0f, .248f + f.mouthOpen * .02f), .002f)
                    for (side in intArrayOf(-1, 1)) for (i in 0..2) {
                        detail.reset(); detail.moveTo(side * .123f, .165f + i * .015f)
                        detail.quadraticBezierTo(side * .21f, .16f + i * .025f,
                            side * (.31f - i * .015f), .143f + i * .039f + f.earLag * .0003f)
                        drawPath(detail, Color(0xFFB1A29A).copy(alpha = f.form.rabbitFur * .7f), style = Stroke(.0025f))
                    }
                }
            }
            val pig = f.form.pigSkin
            if (pig > .005f) {
                smile(f, pig, .233f, .086f)
                softOval(Color(0xFFD87992), pig, Offset(0f, .153f), Size(.234f, .151f))
                softOval(Color(0xFFF19CAC), pig, Offset(0f, .142f), Size(.208f, .126f))
                for (side in intArrayOf(-1, 1)) rotate(side * -12f, Offset(side * .045f, .15f)) {
                    softOval(Color(0xFF783B53), pig, Offset(side * .045f, .15f), Size(.036f, .057f))
                }
                drawOval(Color.White.copy(alpha = pig * .31f), Offset(-.06f, .092f), Size(.070f, .014f))
            }
            val alien = f.form.alienSkin
            if (alien > .005f) {
                for (side in intArrayOf(-1, 1)) drawOval(Color(0xFF305B50).copy(alpha = alien),
                    Offset(side * .019f - .008f, .157f), Size(.016f, .011f))
                smile(f, alien, .227f, .052f)
            }
            val robot = f.form.robotShell
            if (robot > .005f) {
                val mouthHeight = .025f + f.mouthOpen * .048f
                drawRoundRect(Color(0xFF173444).copy(alpha = robot), Offset(-.10f, .187f), Size(.20f, mouthHeight), CornerRadius(.018f))
                for (i in 0..4) drawRoundRect(lerp(tint, Color(0xFFC0F4F0), .65f).copy(alpha = robot * .88f),
                    Offset(-.075f + i * .032f, .194f + kotlin.math.abs(i - 2) * f.smile * .002f),
                    Size(.019f, (mouthHeight - .014f).coerceAtLeast(.008f)), CornerRadius(.004f))
            }
        } }
    }

    private fun DrawScope.plush(color: Color, alpha: Float, light: Offset = Offset(-.15f, -.17f), radius: Float = .64f) {
        drawPath(shape, Brush.radialGradient(listOf(lerp(color, Color.White, .33f).copy(alpha = alpha),
            color.copy(alpha = alpha), lerp(color, Color(0xFF383340), .40f).copy(alpha = alpha)), light, radius))
        drawPath(shape, lerp(color, Color.Black, .5f).copy(alpha = alpha * .3f), style = Stroke(.003f))
    }

    private fun DrawScope.softOval(color: Color, alpha: Float, center: Offset, size: Size) {
        drawOval(Brush.radialGradient(listOf(lerp(color, Color.White, .18f).copy(alpha = alpha),
            color.copy(alpha = alpha), lerp(color, Color.Black, .28f).copy(alpha = alpha)),
            center - Offset(size.width * .15f, size.height * .19f), maxOf(size.width, size.height) * .72f),
            center - Offset(size.width / 2, size.height / 2), size)
    }

    private fun DrawScope.smile(f: LumenFrame, alpha: Float, y: Float, width: Float) {
        val opening = f.mouthOpen * .045f
        if (opening > .001f) softOval(Color(0xFF563747), alpha, Offset(0f, y + .02f), Size(width * 1.25f, opening + .025f))
        detail.reset(); detail.moveTo(-width, y - f.smile * .017f)
        detail.cubicTo(-width * .4f, y + .043f + opening, width * .4f, y + .043f + opening,
            width, y - f.smile * .017f + f.concern * .02f)
        drawPath(detail, Color(0xFF55414A).copy(alpha = alpha * .85f), style = Stroke(.0045f, cap = StrokeCap.Round))
    }
}
