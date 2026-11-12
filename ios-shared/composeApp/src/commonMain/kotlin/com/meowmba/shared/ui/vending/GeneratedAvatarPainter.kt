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
import kotlin.math.abs
import kotlin.math.sin

/** Reuses Lumen's actual expression frame and active clock, without camera or network ownership. */
class GeneratedAvatarPainter {
    private val shape = Path()
    private val expression = Path()
    private val ink = Color(0xFF182032)

    /** Reuses the avatar clock and primitive painter. The anchor is a contact
     * point on the prop; its category selects a bounded contact on the face. */
    fun drawProp(scope: DrawScope, prop: GeneratedPropForm, frame: LumenFrame,
        timeSec: Float, center: Offset, unit: Float, preview: Boolean = false) = with(scope) {
        if (!unit.isFinite() || unit <= 0f || !center.x.isFinite() || !center.y.isFinite()) return@with
        val alpha = frame.presence.clean().coerceIn(0f, 1f)
        if (alpha < .005f) return@with
        val target = if (preview) Offset.Zero else when (prop.category) {
            GeneratedPropCategory.WEARABLE -> Offset(0f, -.30f)
            GeneratedPropCategory.HELD -> Offset(.28f, .25f)
            GeneratedPropCategory.TOY -> Offset(.26f, .36f)
            GeneratedPropCategory.SCENE -> Offset(-.30f, .28f)
        }
        val roll = if (preview || prop.category == GeneratedPropCategory.SCENE) 0f else lumenRoll(frame)
        val radians = roll * (kotlin.math.PI / 180.0).toFloat()
        val rotatedTarget = Offset(target.x * kotlin.math.cos(radians) - target.y * sin(radians),
            target.x * sin(radians) + target.y * kotlin.math.cos(radians))
        val contact = center + rotatedTarget * unit
        // Includes rotated corners, animated limbs and stroke. Fit around the
        // contact so edge travel cannot send generated geometry offscreen.
        val radius = prop.parts.maxOfOrNull { p ->
            kotlin.math.hypot(p.x - prop.anchorX, p.y - prop.anchorY) +
                kotlin.math.hypot(p.width, p.height) * .5f + p.stroke + .03f
        }?.coerceAtLeast(.1f) ?: return@with
        val available = minOf(contact.x, contact.y, size.width - contact.x, size.height - contact.y).coerceAtLeast(0f)
        val scale = minOf(unit * if (preview) .85f else .65f, available / radius)
        if (scale <= 0f) return@with
        translate(contact.x, contact.y) {
            rotate(roll, Offset.Zero) {
                scale(scale, scale, Offset.Zero) {
                    translate(-prop.anchorX, -prop.anchorY) {
                        prop.parts.forEach { drawPart(it, frame, timeSec.clean(), alpha) }
                    }
                }
            }
        }
    }

    /** Caller shares [center] and [unit] with hit testing and draws system privacy marks afterward. */
    fun draw(scope: DrawScope, form: GeneratedAvatarForm, frame: LumenFrame, timeSec: Float,
        center: Offset, unit: Float) = with(scope) {
        if (!unit.isFinite() || unit <= 0f || !center.x.isFinite() || !center.y.isFinite()) return@with
        val alpha = frame.presence.clean().coerceIn(0f, 1f)
        if (alpha < .005f) return@with
        val time = timeSec.clean()
        translate(center.x, center.y) {
            scale(unit * .82f, unit * .82f, Offset.Zero) {
                rotate((frame.roll.clean() + frame.screenRoll.clean()).coerceIn(-30f, 30f), Offset.Zero) {
                    val squash = frame.squash.clean().coerceIn(-1f, 1f) * .035f + frame.breath.clean().coerceIn(-.1f, .1f) * .12f
                    scale(1f + squash, 1f - squash, Offset.Zero) {
                        form.parts.forEach { part -> drawPart(part, frame, time, alpha) }
                        drawFace(form.face, frame, alpha)
                    }
                }
            }
        }
    }

    private fun DrawScope.drawPart(p: GeneratedAvatarPart, f: LumenFrame, time: Float, alpha: Float) {
        val side = if (p.x < 0f) -1f else 1f
        val attention = f.engagement.clean().coerceIn(0f, 1f)
        val rhythm = sin(time * (1.8f + attention * .8f) + p.phase)
        // This beat already includes Lumen's arousal and neural wing drive.
        // Wing anatomy must not replace it with an unrelated idle oscillator.
        val wing = f.wingBeat.clean().coerceIn(0f, 1f) * 2f - 1f
        val motion = when (p.motion) {
            GeneratedAvatarMotion.WING -> wing * 24f * side
            GeneratedAvatarMotion.ARM -> (rhythm * 7f + f.earLift.clean().coerceIn(-15f, 15f) * .35f) * side
            GeneratedAvatarMotion.LEG -> rhythm * 6f * side
            GeneratedAvatarMotion.ANTENNA -> rhythm * 5f + f.earLag.clean().coerceIn(-12f, 12f) * .4f
            GeneratedAvatarMotion.TAIL -> rhythm * 8f + f.tailSwing.clean().coerceIn(-1f, 1f) * 10f
            else -> 0f
        }
        val float = if (p.motion == GeneratedAvatarMotion.FLOAT) rhythm * .015f else 0f
        val body = if (p.motion == GeneratedAvatarMotion.BODY) f.headX.clean().coerceIn(-1f, 1f) * .006f else 0f
        translate(p.x + body, p.y + float) {
            rotate(p.rotation + motion, Offset.Zero) {
                val flap = if (p.motion == GeneratedAvatarMotion.WING) .78f + abs(wing) * .22f else 1f
                scale(flap, 1f, Offset.Zero) {
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
                            shape.close(); drawPath(shape, fill); drawPath(shape, outline, style = stroke)
                        }
                    }
                }
            }
        }
    }

    private fun DrawScope.drawFace(face: GeneratedAvatarFace, f: LumenFrame, alpha: Float) {
        val perspective = f.headX.clean().coerceIn(-1f, 1f) * .018f + f.perspective.clean().coerceIn(-1f, 1f) * .012f
        val color = ink.copy(alpha = alpha)
        translate(face.x + perspective, face.y + f.headY.clean().coerceIn(-1f, 1f) * .008f) {
            for (side in intArrayOf(-1, 1)) {
                val openness = (if (side < 0) f.leftOpen else f.rightOpen).clean().coerceIn(0f, 1.2f)
                val width = face.eyeWidth * (1f + side * perspective * 2f)
                val height = face.eyeHeight * openness
                val x = side * face.eyeSpacing * .5f
                if (openness < .10f) {
                    drawLine(color, Offset(x - width * .46f, 0f), Offset(x + width * .46f, .004f), .008f, StrokeCap.Round)
                } else {
                    drawOval(Color(0xFFFFFAE6).copy(alpha = alpha), Offset(x - width * .5f, -height * .5f), Size(width, height))
                    val iris = minOf(width * .27f, height * .29f)
                    val px = x + f.gazeX.clean().coerceIn(-1f, 1f) * (width * .5f - iris - .005f).coerceAtLeast(0f)
                    val py = f.gazeY.clean().coerceIn(-1f, 1f) * (height * .5f - iris - .005f).coerceAtLeast(0f)
                    drawCircle(Color(face.iris).copy(alpha = alpha), iris, Offset(px, py))
                    drawCircle(color, iris * (.58f + f.pupil.clean().coerceIn(0f, 1f) * .19f), Offset(px, py))
                    drawCircle(Color.White.copy(alpha = alpha), iris * .18f, Offset(px - iris * .28f, py - iris * .30f))
                    drawOval(color, Offset(x - width * .5f, -height * .5f), Size(width, height), style = Stroke(.008f))
                }
                val concern = f.concern.clean().coerceIn(0f, 1f)
                val warmth = f.warmth.clean().coerceIn(0f, 1f)
                val eyebrowY = -face.eyeHeight * .62f - warmth * .012f
                drawLine(color, Offset(x - width * .39f, eyebrowY - side * concern * .024f),
                    Offset(x + width * .39f, eyebrowY + side * concern * .024f), .009f, StrokeCap.Round)
            }
            val open = f.mouthOpen.clean().coerceIn(0f, 1f)
            val smile = f.smile.clean().coerceIn(-1f, 1f) + f.warmth.clean().coerceIn(0f, 1f) * .25f
            if (open > .04f) {
                val height = face.mouthWidth * (.16f + open * .48f)
                val mouthTop = face.mouthY - height * .5f
                drawOval(color, Offset(-face.mouthWidth * .5f, mouthTop), Size(face.mouthWidth, height))
                drawOval(Color(0xFFF18D9D).copy(alpha = alpha), Offset(-face.mouthWidth * .26f, mouthTop + height * .55f), Size(face.mouthWidth * .52f, height * .29f))
                drawLine(Color(0xFFFFFAE6).copy(alpha = alpha), Offset(-face.mouthWidth * .24f, mouthTop + height * .12f),
                    Offset(face.mouthWidth * .24f, mouthTop + height * .12f), height * .12f, StrokeCap.Round)
            } else {
                expression.reset()
                expression.moveTo(-face.mouthWidth * .5f, face.mouthY)
                expression.quadraticTo(0f, face.mouthY + .014f + smile * .045f, face.mouthWidth * .5f, face.mouthY)
                drawPath(expression, color, style = Stroke(.010f, cap = StrokeCap.Round))
            }
        }
    }

    private fun Float.clean(): Float = if (isFinite()) this else 0f
}
