package com.meowmba.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import com.meowmba.shared.companion.AvatarPose
import com.meowmba.shared.companion.MouthMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun toRadians(deg: Float): Double = deg * PI / 180.0

/**
 * Procedural Compose Canvas renderer for the Memba cat face.
 * Ported from the Android app's avatar renderer concept: every measurement
 * derives from `s = min(width, height)` so the face scales to any screen,
 * and all expression state comes from the portable [AvatarPose].
 */
@Composable
fun CatAvatar(
    pose: AvatarPose,
    modifier: Modifier = Modifier,
    onZoneTap: (CatZone) -> Unit = {}
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pose) {
                detectTapGestures { offset ->
                    onZoneTap(zoneAt(size.width.toFloat(), size.height.toFloat(), offset))
                }
            }
    ) {
        drawFace(pose)
    }
}

enum class CatZone { LEFT_EAR, RIGHT_EAR, LEFT_EYE, RIGHT_EYE, MOUTH, FOREHEAD, BACKGROUND }

private fun zoneAt(w: Float, h: Float, tap: Offset): CatZone {
    val s = min(w, h)
    val cx = w / 2f
    val cy = h / 2f
    val dx = tap.x - cx
    val dy = tap.y - cy
    return when {
        dy < -s * 0.28f && dx < -s * 0.18f -> CatZone.LEFT_EAR
        dy < -s * 0.28f && dx > s * 0.18f -> CatZone.RIGHT_EAR
        dy < -s * 0.05f && dx < -s * 0.08f -> CatZone.LEFT_EYE
        dy < -s * 0.05f && dx > s * 0.08f -> CatZone.RIGHT_EYE
        kotlin.math.abs(dy) < s * 0.12f -> CatZone.MOUTH
        dy < 0f -> CatZone.FOREHEAD
        else -> CatZone.BACKGROUND
    }
}

private val InkDark = Color(0xFF1F2430)
private val InkLight = Color(0xFFF2F5FA)

private fun DrawScope.drawFace(pose: AvatarPose) {
    val w = size.width
    val h = size.height
    val s = min(w, h)
    val cx = w / 2f
    val cy = h / 2f
    val ink = InkDark
    val faceAlpha = if (pose.dimmed) 0.45f else 1f

    // Halo
    val glow = Color(pose.glowColorHex.toULong().toLong() or 0x33000000L)
    val glowAlpha = if (pose.glowPulse) 0.5f else 0.3f
    drawCircle(color = glow.copy(alpha = glowAlpha * faceAlpha), radius = s * 0.46f, center = Offset(cx, cy))

    rotate(degrees = pose.headTiltDeg, pivot = Offset(cx, cy)) {
        val bodyScale = 1f + pose.bodyBounce * 0.04f
        val faceR = s * 0.30f * bodyScale
        val faceCx = cx + pose.leanX * s * 0.04f
        val faceCy = cy + (1f - bodyScale) * s * 0.1f

        // Ears (triangles), rotated by droop, scaled by perk
        val droopRad = toRadians(pose.earDroopDeg)
        val earDx = faceR * 0.62f
        val earBaseY = faceCy - faceR * 0.55f
        val earH = faceR * (0.55f * pose.earPerk)
        for (side in listOf(-1, 1)) {
            val baseX = faceCx + side * earDx
            val tipX = baseX + side * (faceR * 0.05f + (sin(droopRad) * side * faceR * 0.18f).toFloat())
            val tipY = earBaseY - (earH * cos(droopRad)).toFloat()
            drawInkTriangle(
                Offset(baseX - side * faceR * 0.22f, earBaseY),
                Offset(baseX + side * faceR * 0.22f, earBaseY),
                Offset(tipX, tipY),
                ink, faceAlpha
            )
            // Ear-tip X privacy mark
            if (pose.micOffMark) {
                val markR = faceR * 0.09f
                drawLine(ink.copy(alpha = faceAlpha), Offset(tipX - markR, tipY + markR - earH * 0.2f), Offset(tipX + markR, tipY + markR * 2 - earH * 0.2f), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
                drawLine(ink.copy(alpha = faceAlpha), Offset(tipX + markR, tipY + markR - earH * 0.2f), Offset(tipX - markR, tipY + markR * 2 - earH * 0.2f), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
            }
        }

        // Head
        drawCircle(color = ink.copy(alpha = faceAlpha), radius = faceR, center = Offset(faceCx, faceCy))

        val eyeDx = faceR * 0.42f
        val eyeY = faceCy - faceR * 0.10f
        val eyeRy = faceR * 0.30f * pose.eyeOpen.coerceAtLeast(0.04f)
        val eyeRx = faceR * 0.24f

        for (side in listOf(-1, 1)) {
            val eyeCx = faceCx + side * eyeDx
            if (pose.happyEyes) {
                // Happy "^^" arcs
                val arcW = faceR * 0.30f
                drawArc(
                    color = Color.White.copy(alpha = faceAlpha),
                    startAngle = 200f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(eyeCx - arcW / 2, eyeY - faceR * 0.10f),
                    size = Size(arcW, faceR * 0.34f),
                    style = Stroke(width = s * 0.016f, cap = StrokeCap.Round)
                )
            } else {
                // Eye outline + slit pupil
                drawOval(
                    color = Color.White.copy(alpha = faceAlpha),
                    topLeft = Offset(eyeCx - eyeRx, eyeY - eyeRy),
                    size = Size(eyeRx * 2, eyeRy * 2),
                    style = Stroke(width = s * 0.010f)
                )
                val pupilRy = eyeRy * 0.92f
                val pupilRx = eyeRx * 0.55f * (pose.pupilSize / 0.28f).coerceIn(0.35f, 2.2f)
                val gazeUp = eyeY - pose.pupilGazeUp * eyeRy * 0.5f
                drawOval(
                    color = Color(0xFF9BE8A8).copy(alpha = faceAlpha),
                    topLeft = Offset(eyeCx - pupilRx, gazeUp - pupilRy),
                    size = Size(pupilRx * 2, pupilRy * 2)
                )
                if (pose.cameraOffMark) {
                    val mr = eyeRx * 0.7f
                    drawLine(ink.copy(alpha = faceAlpha), Offset(eyeCx - mr, gazeUp - mr), Offset(eyeCx + mr, gazeUp + mr), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
                    drawLine(ink.copy(alpha = faceAlpha), Offset(eyeCx + mr, gazeUp - mr), Offset(eyeCx - mr, gazeUp + mr), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
                }
            }
        }

        // Nose + mouth
        val noseY = faceCy + faceR * 0.22f
        drawCircle(ink.copy(alpha = faceAlpha), radius = faceR * 0.045f, center = Offset(faceCx, noseY - faceR * 0.06f))
        when (pose.mouth) {
            MouthMode.IDLE -> {
                // closed "w" mouth
                val mw = faceR * 0.16f
                drawArc(ink.copy(alpha = faceAlpha), 180f, 180f, false, topLeft = Offset(faceCx - mw, noseY), size = Size(mw, mw * 0.8f), style = Stroke(s * 0.010f, cap = StrokeCap.Round))
                drawArc(ink.copy(alpha = faceAlpha), 180f, 180f, false, topLeft = Offset(faceCx, noseY), size = Size(mw, mw * 0.8f), style = Stroke(s * 0.010f, cap = StrokeCap.Round))
            }
            MouthMode.HAPPY -> {
                val mw = faceR * 0.24f
                drawArc(ink.copy(alpha = faceAlpha), 20f, 140f, false, topLeft = Offset(faceCx - mw / 2, noseY - faceR * 0.04f), size = Size(mw, faceR * 0.22f), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
            }
            MouthMode.SPEAKING -> {
                val open = faceR * (0.10f + pose.sparkle * 0.04f)
                drawOval(ink.copy(alpha = faceAlpha), topLeft = Offset(faceCx - faceR * 0.10f, noseY), size = Size(faceR * 0.20f, open))
            }
            MouthMode.MUTED -> {
                val mr = faceR * 0.10f
                drawLine(ink.copy(alpha = faceAlpha), Offset(faceCx - mr, noseY), Offset(faceCx + mr, noseY + mr), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
                drawLine(ink.copy(alpha = faceAlpha), Offset(faceCx + mr, noseY), Offset(faceCx - mr, noseY + mr), strokeWidth = s * 0.012f, cap = StrokeCap.Round)
            }
        }

        // Whiskers
        val whiskerY = noseY + faceR * 0.06f
        val flap = if (pose.whiskerFlutter) 0.5f else 0.15f
        for (side in listOf(-1, 1)) {
            for (i in 0..2) {
                val yOff = (i - 1) * faceR * 0.10f
                val yStart = whiskerY + yOff
                val yEnd = whiskerY + yOff + (if (i == 1) 0f else (if (i == 0) -flap else flap)) * faceR * 0.10f
                drawLine(
                    ink.copy(alpha = 0.85f * faceAlpha),
                    Offset(faceCx + side * faceR * 0.35f, yStart),
                    Offset(faceCx + side * faceR * (0.72f + pose.tailWag * 0.05f), yEnd),
                    strokeWidth = s * 0.006f, cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun DrawScope.drawInkTriangle(a: Offset, b: Offset, c: Offset, color: Color, alpha: Float) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close()
    }
    drawPath(path, color.copy(alpha = alpha))
}
