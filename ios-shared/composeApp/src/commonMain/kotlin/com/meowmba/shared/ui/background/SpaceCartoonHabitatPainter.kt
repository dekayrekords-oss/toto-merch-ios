package com.meowmba.shared.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Crisp, complete fallback while room art decodes. Architecture lives at the sides;
 * no painted inventory competes with the world's movable glass, book and ball.
 * Normalized room coordinates deliberately share the fixed .78 contact plane.
 */
internal class SpaceCartoonHabitatPainter {
    private val path = Path()
    private val ink = 0xFF20343FL
    private val cream = 0xFFF4E9BDL

    fun draw(scope: DrawScope, room: BackgroundAnimation, phase: Float, slow: Float) = with(scope) {
        // Deep window and broad frame establish perspective without a noisy center.
        panel(.20f, .06f, .60f, .54f, 0xFF162B48)
        panel(.23f, .085f, .54f, .49f, if (room == BackgroundAnimation.CARTOON_ALIEN_AQUARIUM) 0xFF247B8C else 0xFF314866)
        line(.23f, .55f, .77f, .55f, 0xFF8EC2B6, .007f)
        orb(.67f, .18f, .043f, .043f, 0xFFDFD2A7)
        repeat(5) { i ->
            val x = .29f + (i * 13 % 43) / 100f
            val y = .13f + (i % 2) * .07f
            line(x - .004f, y, x + .004f, y, cream, .003f)
        }
        line(0f, .72f, 1f, .72f, ink, .009f)
        panel(0f, .73f, 1f, .05f, 0xFF7EAAA4)
        when (room) {
            BackgroundAnimation.CARTOON_PORTAL_ARCADE -> {
                // A portal with crooked ears, plus a cabinet that appears to be watching.
                panel(.015f, .20f, .17f, .48f, 0xFF819DAA)
                orb(.10f, .385f, .074f, .151f, 0xFF142D3F)
                orb(.10f, .385f, .059f, .133f, 0xFF8DD980)
                orb(.10f, .385f, .041f, .101f, 0xFF376F69)
                val wiggle = sin(phase * TAU) * .006f
                orb(.10f + wiggle, .386f, .019f, .062f, 0xFFC5EAAF)
                line(.04f, .20f, .022f, .15f, ink, .014f)
                line(.16f, .20f, .18f, .145f, ink, .014f)
                panel(.82f, .30f, .165f, .39f, 0xFF9B83B5)
                panel(.843f, .333f, .120f, .19f, 0xFF203849)
                orb(.877f, .405f, .017f, .028f, cream)
                orb(.926f, .405f, .017f, .028f, cream)
                orb(.88f, .41f, .006f, .014f, ink); orb(.93f, .41f, .006f, .014f, ink)
                line(.85f, .55f, .954f, .55f, cream, .008f)
                orb(.866f, .58f, .011f, .011f, 0xFFDA817C)
                orb(.933f, .58f, .011f, .011f, 0xFFB5D781)
            }
            BackgroundAnimation.CARTOON_MOON_GARDEN -> {
                orb(.095f, .65f, .075f, .048f, 0xFFB78F9F)
                for (i in 0..2) {
                    val x = .06f + i * .035f
                    val sway = sin(slow * TAU + i) * .015f
                    line(x, .625f, x + sway, .43f - i % 2 * .12f, 0xFF719E7B, .012f)
                    flower(x + sway, .41f - i % 2 * .12f, .038f, i)
                }
                panel(.825f, .605f, .155f, .075f, 0xFFB48D9C)
                path.reset(); path.moveTo(size.width * .84f, size.height * .60f)
                path.cubicTo(size.width * .79f, size.height * .42f, size.width * .98f, size.height * .44f, size.width * .93f, size.height * .27f)
                drawPath(path, Color(ink), style = Stroke(size.minDimension * .022f, cap = StrokeCap.Round))
                drawPath(path, Color(0xFF8EBD89), style = Stroke(size.minDimension * .015f, cap = StrokeCap.Round))
                flower(.935f, .25f, .052f, 1)
                orb(.903f, .41f, .032f, .019f, 0xFFB7DA91)
            }
            BackgroundAnimation.CARTOON_ALIEN_AQUARIUM -> {
                panel(.018f, .23f, .16f, .45f, 0xFF89C4BB)
                panel(.039f, .26f, .118f, .32f, 0xFF226D83)
                repeat(3) { i -> orb(.075f + i % 2 * .05f, .49f - i * .065f + sin(slow * TAU + i) * .013f, .013f, .021f, 0xFFB7E5D0) }
                line(.055f, .63f, .137f, .63f, cream, .009f)
                panel(.82f, .215f, .16f, .47f, 0xFF88B4B8)
                panel(.838f, .24f, .125f, .32f, 0xFF256A87)
                // A fish wearing a diving helmet, built into the scenery.
                orb(.90f, .39f + sin(slow * TAU) * .018f, .042f, .054f, 0xFFDFC58F)
                orb(.90f, .381f + sin(slow * TAU) * .018f, .030f, .033f, 0xFFB3DED1)
                orb(.91f, .38f + sin(slow * TAU) * .018f, .006f, .011f, ink)
                line(.87f, .60f, .93f, .60f, 0xFFDE9E89, .010f)
            }
            BackgroundAnimation.CARTOON_METEOR_GARAGE -> {
                panel(.02f, .21f, .16f, .47f, 0xFFB5968F)
                panel(.04f, .245f, .12f, .25f, 0xFF5C5870)
                repeat(3) { i ->
                    line(.07f + i * .036f, .29f, .075f + i * .036f, .43f, cream, .009f)
                    orb(.069f + i * .036f, .29f, .012f, .018f, 0xFF9FC6BD)
                }
                panel(.035f, .55f, .13f, .08f, 0xFF748D99)
                orb(.10f, .59f, .026f, .023f, 0xFFEAAF7D)
                // A tiny rocket on a lift and a moon rock that has its own parking space.
                panel(.81f, .64f, .175f, .035f, 0xFF728E99)
                line(.85f, .64f, .94f, .58f, ink, .008f)
                line(.94f, .64f, .85f, .58f, ink, .008f)
                orb(.895f, .44f, .052f, .115f, 0xFFE4BC91)
                orb(.90f, .426f, .027f, .037f, 0xFF679DA8)
                orb(.90f, .426f, .015f, .022f, 0xFFABDACC)
                line(.859f, .50f, .831f, .553f, 0xFFB6849E, .018f)
                line(.931f, .50f, .959f, .553f, 0xFFB6849E, .018f)
            }
            else -> Unit
        }
    }

    private fun DrawScope.panel(x: Float, y: Float, w: Float, h: Float, color: Long) {
        drawRect(Color(color), Offset(x * size.width, y * size.height), Size(w * size.width, h * size.height))
        drawRect(Color(ink), Offset(x * size.width, y * size.height), Size(w * size.width, h * size.height), style = Stroke(.005f * size.minDimension))
    }
    private fun DrawScope.orb(x: Float, y: Float, rx: Float, ry: Float, color: Long) {
        val origin = Offset((x - rx) * size.width, (y - ry) * size.height)
        val extent = Size(rx * 2f * size.width, ry * 2f * size.height)
        drawOval(Color(color), origin, extent); drawOval(Color(ink), origin, extent, style = Stroke(.004f * size.minDimension))
    }
    private fun DrawScope.line(x: Float, y: Float, xx: Float, yy: Float, color: Long, width: Float) =
        drawLine(Color(color), Offset(x * size.width, y * size.height), Offset(xx * size.width, yy * size.height), size.minDimension * width, StrokeCap.Round)
    private fun DrawScope.flower(x: Float, y: Float, radius: Float, variation: Int) {
        repeat(5) { i ->
            val angle = i * TAU / 5f
            orb(x + cos(angle) * radius * .65f, y + sin(angle) * radius * .65f, radius * .42f, radius * .42f,
                if (variation % 2 == 0) 0xFFE8B5D0 else 0xFFBAD591)
        }
        orb(x, y, radius * .40f, radius * .40f, cream)
        orb(x - radius * .11f, y - radius * .03f, radius * .038f, radius * .09f, ink)
        orb(x + radius * .11f, y - radius * .03f, radius * .038f, radius * .09f, ink)
    }
    private companion object { const val TAU = (PI * 2).toFloat() }
}
