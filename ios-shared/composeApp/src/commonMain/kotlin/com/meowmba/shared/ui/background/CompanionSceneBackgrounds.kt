package com.meowmba.shared.ui.background

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale

import kotlin.math.sin
import kotlin.random.Random

/**
 * Original native scenery, with a quiet clearing where the companion sits.
 * Paths, gradients and the small seeded field are cached per painter; moving
 * details consume the existing background phases, never their own coroutine.
 * Normalized architecture fills portrait and landscape without bitmap crops.
 */
internal class CompanionSceneBackgroundPainter {
    private data class Point(val x: Float, val y: Float, val phase: Float, val size: Float)
    private val points = Random(4821).let { random -> List(36) {
        Point(random.nextFloat(), random.nextFloat(), random.nextFloat(), .65f + random.nextFloat() * .7f)
    } }
    private val woodSky = gradient(0xFF0B1720, 0xFF142C30, 0xFF081612)
    private val lagoonSky = gradient(0xFF0C142B, 0xFF24394C, 0xFF0A242B)
    private val water = gradient(0xFF17343D, 0xFF123239, 0xFF071A22)
    private val loftWall = gradient(0xFF22202A, 0xFF28252A, 0xFF151A22)
    private val windowGlass = gradient(0xFF1D334A, 0xFF294657, 0xFF1A2B38)
    private val floor = gradient(0xFF352E2B, 0xFF302526, 0xFF171C23)
    private val desk = gradient(0xFF886346, 0xFF503D32, 0xFF322B29)
    private val moonGlow = RadialGlow.brush(Color(0xFFAFD6DF))
    private val fireflyGlow = RadialGlow.brush(Color(0xFFD8E6A2))
    private val warmGlow = RadialGlow.brush(Color(0xFFF0B76F))
    private val woods = arrayOf(
        shape { moveTo(0f, .70f); cubicTo(.20f, .59f, .32f, .72f, .51f, .67f); cubicTo(.73f, .61f, .83f, .62f, 1f, .69f); ground() },
        shape { moveTo(0f, .81f); cubicTo(.24f, .66f, .31f, .80f, .56f, .76f); cubicTo(.73f, .70f, .84f, .80f, 1f, .74f); ground() },
        shape { moveTo(0f, .92f); cubicTo(.19f, .81f, .40f, .90f, .55f, .88f); cubicTo(.77f, .84f, .90f, .86f, 1f, .91f); ground() },
    )
    private val farTrees = List(24) { i ->
        val x = i / 23f
        val height = .045f + points[i].size * .035f
        tree(x, .705f + sin(i * 1.9f) * .018f, height, .022f + points[i].size * .006f)
    }
    private val nearTrees = listOf(tree(.045f, .88f, .71f, .10f), tree(.15f, .79f, .48f, .058f),
        tree(.965f, .88f, .68f, .095f), tree(.87f, .80f, .47f, .059f))
    private val treeTrunks = shape {
        moveTo(0f, 0f); lineTo(.025f, 0f); cubicTo(.053f, .31f, .025f, .57f, .069f, .91f)
        lineTo(.008f, 1f); lineTo(0f, 1f); close()
        moveTo(1f, 0f); lineTo(.98f, 0f); cubicTo(.954f, .27f, .979f, .58f, .941f, .91f)
        lineTo(.985f, 1f); lineTo(1f, 1f); close()
    }
    private val shoreFar = shape {
        moveTo(0f, .59f); cubicTo(.09f, .56f, .14f, .58f, .20f, .55f)
        cubicTo(.28f, .51f, .38f, .58f, .48f, .56f); cubicTo(.72f, .52f, .79f, .57f, 1f, .52f); ground()
    }
    private val shoreNear = shape {
        moveTo(0f, .82f); cubicTo(.10f, .80f, .13f, .75f, .26f, .73f)
        cubicTo(.17f, .84f, .25f, .85f, .42f, 1f); lineTo(0f, 1f); close()
        moveTo(1f, .74f); cubicTo(.82f, .79f, .94f, .92f, .70f, 1f); lineTo(1f, 1f); close()
    }
    private val beam = shape { moveTo(0f, 0f); lineTo(1f, 0f); lineTo(1f, .055f); lineTo(.10f, .026f); lineTo(0f, .09f); close() }
    private val lampShade = shape {
        moveTo(.115f, .678f); quadraticBezierTo(.159f, .654f, .204f, .679f)
        lineTo(.232f, .751f); quadraticBezierTo(.160f, .768f, .087f, .751f); close()
    }

    fun draw(scope: DrawScope, animation: BackgroundAnimation, clock: Float, slow: Float) = with(scope) {
        val phase = cleanPhase(clock); val quiet = cleanPhase(slow)
        when (animation) {
            BackgroundAnimation.WOODLAND -> woodland(phase, quiet)
            BackgroundAnimation.LAGOON -> lagoon(phase, quiet)
            BackgroundAnimation.RAINY_LOFT -> loft(phase, quiet)
            else -> Unit
        }
    }

    private fun DrawScope.normalized(block: DrawScope.() -> Unit) = scale(size.width, size.height, Offset.Zero, block)

    private fun DrawScope.woodland(clock: Float, slow: Float) {
        normalized { drawRect(woodSky, size = Size(1f, 1f)) }
        moon(.78f, .135f, .040f, .72f)
        stars(clock, Color(0xFFB2CBC8), .40f)
        normalized {
            drawPath(woods[0], Color(0xFF1A3534))
            farTrees.forEachIndexed { i, path -> drawPath(path, if (i % 2 == 0) Color(0xFF16312F) else Color(0xFF142C2D)) }
            drawPath(woods[1], Color(0xFF102A27))
            nearTrees.forEachIndexed { i, path -> drawPath(path, if (i % 2 == 0) Color(0xFF091E20) else Color(0xFF112825)) }
            drawPath(woods[2], Color(0xFF0A1D19)); drawPath(treeTrunks, Color(0xFF071716))
            // A moonlit path recedes to the clearing; low contrast behind the face.
            drawLine(Color(0xFF274037).copy(alpha = .32f), Offset(.57f, .82f), Offset(.69f, 1f), .026f)
            repeat(14) { i ->
                val x = i / 13f
                drawLine(Color(0xFF355044).copy(alpha = .24f), Offset(x, .985f),
                    Offset(x - .014f, .93f + points[i].y * .04f), .0015f, StrokeCap.Round)
            }
        }
        // Near fireflies live along the ground and edges, not across the eyes.
        for (i in 0 until 18) {
            val p = points[i]
            val x = (p.x + sin((clock + p.phase) * TAU) * .009f) * size.width
            val y = (.77f + p.y * .19f + sin((slow + p.phase) * TAU) * .006f) * size.height
            val glow = .24f + .42f * (.5f + .5f * sin((clock * 2f + p.phase) * TAU))
            val radius = size.minDimension * (.006f + p.size * .004f)
            drawRadialGlow(Offset(x, y), radius, fireflyGlow, glow * .43f)
            drawCircle(Color(0xFFDFE8AE), radius * .12f, Offset(x, y), alpha = glow * if (i % 3 == 0) 1f else .65f)
        }
    }

    private fun DrawScope.lagoon(clock: Float, slow: Float) {
        normalized {
            drawRect(lagoonSky, size = Size(1f, 1f))
            drawPath(shoreFar, Color(0xFF18303D))
            drawRect(water, Offset(0f, .605f), Size(1f, .395f))
            drawLine(Color(0xFF8AABB2).copy(alpha = .18f), Offset(0f, .605f), Offset(1f, .605f), .002f)
        }
        moon(.76f, .145f, .048f, .85f)
        stars(clock, Color(0xFFCAD6E4), .5f)
        // Separate soft horizontal ribbons read as water, not a glowing column.
        repeat(19) { i ->
            val depth = i / 18f
            val y = (.638f + depth * depth * .33f) * size.height
            val center = (.76f + sin((slow + depth * .55f) * TAU) * .008f) * size.width
            val half = (.009f + depth * .052f) * size.width
            val shimmer = .65f + .35f * sin((clock * 2f + depth * 1.4f) * TAU)
            drawLine(Color(0xFFACC9CA).copy(alpha = (.15f - depth * .065f) * shimmer),
                Offset(center - half, y), Offset(center + half, y),
                size.minDimension * (.0015f + depth * .002f), StrokeCap.Round)
        }
        normalized {
            repeat(9) { i ->
                val y = .66f + i * .031f
                val shift = sin((clock + i * .18f) * TAU) * .012f
                drawLine(Color(0xFF4A7D80).copy(alpha = .11f), Offset(.06f + shift, y), Offset(.40f + shift, y + .001f), .0015f)
            }
            drawPath(shoreNear, Color(0xFF091B24))
            // Reeds and stones frame the water from below, leaving the horizon open.
            repeat(10) { i ->
                val side = if (i < 5) 1f else -1f
                val x = if (i < 5) .045f + i * .023f else .95f - (i - 5) * .021f
                val tip = .84f - points[i].y * .085f
                val sway = sin((slow + points[i].phase) * TAU) * .004f
                drawLine(Color(0xFF234139), Offset(x, 1f), Offset(x + side * .02f + sway, tip), .003f, StrokeCap.Round)
                drawLine(Color(0xFF2A493D), Offset(x, .94f), Offset(x - side * .025f, .89f), .002f, StrokeCap.Round)
            }
            drawOval(Color(0xFF172C31), Offset(.08f, .968f), Size(.13f, .045f))
            drawOval(Color(0xFF20343A), Offset(.80f, .968f), Size(.075f, .030f))
        }
    }

    private fun DrawScope.loft(clock: Float, slow: Float) {
        normalized {
            drawRect(loftWall, size = Size(1f, 1f))
            drawPath(beam, Color(0xFF171921))
            drawRect(floor, Offset(0f, .81f), Size(1f, .19f))
            drawLine(Color(0xFF645047).copy(alpha = .28f), Offset(0f, .81f), Offset(1f, .81f), .008f)
            // An inset window on the right; no bright crossbar through the avatar.
            drawRoundRect(Color(0xFF131C28), Offset(.698f, .102f), Size(.253f, .558f), CornerRadius(.013f))
            drawRect(windowGlass, Offset(.709f, .119f), Size(.23f, .52f))
            repeat(7) { i ->
                val x = .711f + i * .034f
                val height = .025f + points[i].y * .08f
                drawRect(Color(0xFF1C303D), Offset(x, .634f - height), Size(.027f, height))
                drawRect(Color(0xFFBCAC83).copy(alpha = .17f), Offset(x + .009f, .617f - height), Size(.003f, .004f))
            }
        }
        clipRect(size.width * .710f, size.height * .120f, size.width * .938f, size.height * .636f) {
            points.forEachIndexed { i, p ->
                val cycle = if (i % 2 == 0) 2f else 3f
                val y = .10f + wrap(p.y + clock * cycle) * .57f
                val x = .714f + p.x * .221f
                drawLine(Color(0xFFABC9D2).copy(alpha = .12f + p.phase * .10f),
                    Offset(x * size.width, y * size.height),
                    Offset((x - .003f) * size.width, (y + .023f + p.size * .01f) * size.height),
                    size.minDimension * .0013f, StrokeCap.Round)
            }
        }
        normalized {
            drawRect(Color(0xFF55463C), Offset(.696f, .642f), Size(.260f, .014f))
            drawRect(Color(0xFF1A242D), Offset(.82f, .116f), Size(.005f, .526f))
            drawLine(Color(0xFF617179).copy(alpha = .19f), Offset(.713f, .121f), Offset(.934f, .121f), .002f)
            // Quiet wall shelf and a few original book spines.
            drawRect(Color(0xFF4D4038), Offset(.052f, .292f), Size(.164f, .009f))
            repeat(5) { i ->
                val height = .052f + points[i + 8].y * .035f
                drawRect(bookColors[i], Offset(.065f + i * .025f, .292f - height), Size(.019f, height))
                drawLine(Color(0xFFC2AB82).copy(alpha = .16f), Offset(.069f + i * .025f, .28f), Offset(.08f + i * .025f, .28f), .002f)
            }
        }
        // Cached analytic light pools, no GPU blur or flickering flame.
        val warmth = .20f + .008f * sin(slow * TAU)
        drawRadialGlow(Offset(size.width * .16f, size.height * .755f), size.minDimension * .24f, warmGlow, warmth)
        normalized {
            drawRoundRect(desk, Offset(.058f, .838f), Size(.888f, .036f), CornerRadius(.009f))
            drawRect(Color(0xFF352925), Offset(.092f, .874f), Size(.025f, .126f))
            drawRect(Color(0xFF352925), Offset(.874f, .874f), Size(.025f, .126f))
            drawLine(Color(0xFFBE9465).copy(alpha = .35f), Offset(.08f, .842f), Offset(.924f, .842f), .002f)
            drawLine(Color(0xFF907454), Offset(.16f, .833f), Offset(.16f, .743f), .004f)
            drawOval(Color(0xFF6C5440), Offset(.125f, .827f), Size(.07f, .012f))
            drawPath(lampShade, Color(0xFFD1AA73))
            drawLine(Color(0xFFF6D89B).copy(alpha = .68f), Offset(.093f, .752f), Offset(.225f, .752f), .003f)
            // Reading pile and a mug occupy the tabletop edges, below the face.
            repeat(3) { i ->
                val x = .727f + if (i == 1) .008f else 0f
                val y = .812f - i * .021f
                drawRoundRect(bookColors[(i + 1) % bookColors.size], Offset(x, y), Size(.136f, .019f), CornerRadius(.004f))
                drawRect(Color(0xFFB3A489).copy(alpha = .60f), Offset(x + .007f, y + .005f), Size(.12f, .007f))
            }
            drawRoundRect(Color(0xFF6A7270), Offset(.282f, .786f), Size(.036f, .049f), CornerRadius(.009f))
            drawOval(Color(0xFF8B938B), Offset(.288f, .785f), Size(.024f, .005f))
            drawOval(Color(0xFF7D8379), Offset(.311f, .795f), Size(.017f, .024f), style = Stroke(.003f))
        }
    }

    private fun DrawScope.moon(x: Float, y: Float, radius: Float, alpha: Float) {
        val center = Offset(size.width * x, size.height * y)
        val r = size.minDimension * radius
        drawRadialGlow(center, r * 3.7f, moonGlow, alpha * .13f)
        drawCircle(Color(0xFFD0DEDA), r, center, alpha = alpha)
        drawCircle(Color(0xFFAAC4C8), r * .20f, center + Offset(r * .26f, r * .21f), alpha = alpha * .24f)
        drawCircle(Color(0xFFBACED1), r * .13f, center - Offset(r * .3f, r * .34f), alpha = alpha * .25f)
    }

    private fun DrawScope.stars(clock: Float, color: Color, alpha: Float) {
        for (i in 0 until 22) {
            val p = points[i]
            val center = Offset(p.x * size.width, (.045f + p.y * .18f) * size.height)
            val twinkle = .65f + .35f * sin((clock + p.phase) * TAU)
            drawCircle(color, size.minDimension * .0012f * p.size, center, alpha = alpha * twinkle)
        }
    }

    companion object {
        private const val TAU = 6.2831855f
        private val bookColors = listOf(Color(0xFF455D60), Color(0xFF715644), Color(0xFF485166), Color(0xFF6C6050), Color(0xFF5B6961))
        private fun gradient(vararg colors: Long) = Brush.verticalGradient(colors.map { Color(it) }, 0f, 1f)
        private fun shape(block: Path.() -> Unit) = Path().apply(block)
        private fun Path.ground() { lineTo(1f, 1f); lineTo(0f, 1f); close() }
        private fun wrap(value: Float) = value - kotlin.math.floor(value)
        private fun cleanPhase(value: Float) = if (value.isFinite()) wrap(value) else 0f
        private fun tree(x: Float, ground: Float, height: Float, width: Float) = shape {
            moveTo(x, ground - height)
            for (i in 1..6) {
                val t = i / 6f
                lineTo(x + width * t * .54f, ground - height * (1f - t * .90f))
                lineTo(x + width * t * .31f, ground - height * (1f - t))
            }
            lineTo(x + width * .08f, ground); lineTo(x + width * .08f, ground + height * .08f)
            lineTo(x - width * .08f, ground + height * .08f); lineTo(x - width * .08f, ground)
            for (i in 6 downTo 1) {
                val t = i / 6f
                lineTo(x - width * t * .31f, ground - height * (1f - t))
                lineTo(x - width * t * .54f, ground - height * (1f - t * .90f))
            }
            close()
        }
    }
}

internal fun isCompanionSceneBackground(animation: BackgroundAnimation): Boolean = when (animation) {
    BackgroundAnimation.WOODLAND, BackgroundAnimation.LAGOON, BackgroundAnimation.RAINY_LOFT -> true
    else -> false
}
