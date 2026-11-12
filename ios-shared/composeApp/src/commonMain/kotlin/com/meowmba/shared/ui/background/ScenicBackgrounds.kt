package com.meowmba.shared.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer

import kotlin.math.floor
import kotlin.math.sin

internal fun isScenicBackground(animation: BackgroundAnimation): Boolean = when (animation) {
    BackgroundAnimation.SAFFRON_DUNES, BackgroundAnimation.ALPINE_CABLEWAY,
    BackgroundAnimation.CORAL_TWILIGHT, BackgroundAnimation.LANTERN_COURTYARD,
    BackgroundAnimation.CRYSTAL_CAVERN -> true
    else -> false
}

/**
 * No new clock, bitmap, blur, or offscreen render target. The stationary artwork's
 * display list is retained independently: only the detail layer observes time.
 * Frozen picker suppliers never start a clock. AppBackground still owns dimming,
 * while AnimatedBackground owns the existing lifecycle/thermal/pause policy.
 */
@Composable
internal fun ScenicBackground(animation: BackgroundAnimation, modifier: Modifier = Modifier,
    clock: () -> Float, slow: () -> Float) {
    val painter = remember(animation) { ScenicBackgroundPainter(animation) }
    Box(modifier) {
        Canvas(Modifier.matchParentSize().graphicsLayer()) { painter.drawStill(this) }
        Canvas(Modifier.matchParentSize().graphicsLayer()) { painter.drawMotion(this, clock(), slow()) }
    }
}

/**
 * Original normalized vector scenery, with a low-contrast clearing for the face.
 * Paths, brushes, strokes and the 24-element deterministic field are made once.
 * Motion is analytic and bounded (at most 64 primitives), not a particle simulation;
 * there are no per-frame collections, geometry builds, random calls or I/O.
 */
internal class ScenicBackgroundPainter(val animation: BackgroundAnimation) {
    init { require(isScenicBackground(animation)) }

    private val art = scenery(animation)
    private val sky = when (animation) {
        BackgroundAnimation.SAFFRON_DUNES -> gradient(0xFF302234, 0xFF65434A, 0xFF302731)
        BackgroundAnimation.ALPINE_CABLEWAY -> gradient(0xFF142A3E, 0xFF385366, 0xFF152D38)
        BackgroundAnimation.CORAL_TWILIGHT -> gradient(0xFF123B49, 0xFF092B3C, 0xFF061824)
        BackgroundAnimation.LANTERN_COURTYARD -> gradient(0xFF1D223A, 0xFF303044, 0xFF141C2B)
        else -> gradient(0xFF111629, 0xFF292443, 0xFF121F31)
    }
    private val glow = RadialGlow.brush(when (animation) {
        BackgroundAnimation.SAFFRON_DUNES -> Color(0xFFECAF73)
        BackgroundAnimation.ALPINE_CABLEWAY -> Color(0xFFBFD9DF)
        BackgroundAnimation.CORAL_TWILIGHT -> Color(0xFF83DAD0)
        BackgroundAnimation.LANTERN_COURTYARD -> Color(0xFFFFC076)
        else -> Color(0xFFABB5FF)
    })
    private val bubbleStroke = Stroke(.0018f)
    private val field = Array(24) { i ->
        Speck(wrap(i * .618034f + .13f), wrap(i * .414214f + .29f), wrap(i * .732051f), .65f + wrap(i * .271828f) * .7f)
    }
    private val fishBody = path {
        moveTo(-.020f, 0f); cubicTo(-.009f, -.010f, .012f, -.012f, .023f, 0f)
        cubicTo(.012f, .012f, -.009f, .010f, -.020f, 0f); close()
        moveTo(-.016f, 0f); lineTo(-.029f, -.009f); lineTo(-.027f, .009f); close()
    }
    private val rays = arrayOf(
        polygon(.04f, 0f, .10f, 0f, .43f, .77f, .31f, .77f),
        polygon(.18f, 0f, .21f, 0f, .60f, .70f, .51f, .70f),
        polygon(.69f, 0f, .72f, 0f, 1.06f, .74f, .94f, .74f),
    )

    fun drawStill(scope: DrawScope) = with(scope) {
        if (!validSize()) return@with
        clipRect {
            normalized {
                drawRect(sky, size = Size(1f, 1f))
                for (layer in art) drawPath(layer.path, layer.color)
            }
            // Round celestial bodies stay round on narrow covers and unfolded screens.
            when (animation) {
                BackgroundAnimation.SAFFRON_DUNES -> celestial(.78f, .19f, .072f, Color(0xFFE5AA79), .15f)
                BackgroundAnimation.ALPINE_CABLEWAY -> celestial(.20f, .145f, .031f, Color(0xFFC5DADD), .10f)
                BackgroundAnimation.LANTERN_COURTYARD -> celestial(.76f, .15f, .025f, Color(0xFFB8C1D6), .06f)
                else -> Unit
            }
        }
    }

    fun drawMotion(scope: DrawScope, clock: Float, slow: Float) = with(scope) {
        if (!validSize()) return@with
        val phase = cleanPhase(clock)
        val quiet = cleanPhase(slow)
        clipRect {
            when (animation) {
                BackgroundAnimation.SAFFRON_DUNES -> dunes(phase, quiet)
                BackgroundAnimation.ALPINE_CABLEWAY -> alpine(phase, quiet)
                BackgroundAnimation.CORAL_TWILIGHT -> coral(phase, quiet)
                BackgroundAnimation.LANTERN_COURTYARD -> courtyard(phase, quiet)
                BackgroundAnimation.CRYSTAL_CAVERN -> cavern(phase, quiet)
                else -> Unit
            }
        }
    }

    /** Native render tests use exactly the same two draw paths as the retained layers. */
    fun draw(scope: DrawScope, clock: Float, slow: Float) {
        drawStill(scope)
        drawMotion(scope, clock, slow)
    }

    private fun DrawScope.validSize() = size.width.isFinite() && size.height.isFinite() && size.width > 0f && size.height > 0f
    private inline fun DrawScope.normalized(block: DrawScope.() -> Unit) = scale(size.width, size.height, Offset.Zero, block)

    private fun DrawScope.celestial(x: Float, y: Float, radius: Float, color: Color, halo: Float) {
        val center = Offset(size.width * x, size.height * y)
        val r = size.minDimension * radius
        drawRadialGlow(center, r * 3f, glow, halo)
        drawCircle(color, r, center)
        drawCircle(Color.White, r * .97f, center, style = Stroke(size.minDimension * .001f), alpha = .12f)
    }

    private fun DrawScope.dunes(clock: Float, slow: Float) = normalized {
        // Low, airy sand wisps follow the dune crests; no particle crosses the face.
        repeat(12) { i ->
            val p = field[i]
            val progress = wrap(clock + p.phase)
            val x = -.08f + progress * 1.16f
            val y = .76f + p.y * .20f + sin((slow + p.phase) * TAU) * .007f
            val alpha = sin(progress * PI) * .17f
            drawLine(Color(0xFFECC397), Offset(x, y), Offset(x + .024f * p.scale, y - .003f), .0011f, StrokeCap.Round, alpha = alpha)
            drawLine(Color(0xFFD5A078), Offset(x - .012f, y + .007f), Offset(x + .011f, y + .005f), .0007f, alpha = alpha * .6f)
        }
        // A pair of distant birds rides a closed, very slow arc in the upper sky.
        repeat(2) { i ->
            val x = .23f + .07f * sin((slow + i * .04f) * TAU) + i * .035f
            val y = .23f + i * .021f + .008f * sin(clock * TAU)
            val wing = .003f + .002f * sin((clock * 2f + i * .2f) * TAU)
            drawLine(Color(0xFF302733), Offset(x - .008f, y - wing), Offset(x, y), .0015f, StrokeCap.Round)
            drawLine(Color(0xFF302733), Offset(x, y), Offset(x + .008f, y - wing), .0015f, StrokeCap.Round)
        }
    }

    private fun DrawScope.alpine(clock: Float, slow: Float) = normalized {
        // Two cabins glide on the authored cable. Their wrap happens beyond either edge.
        repeat(2) { i ->
            val x = -.10f + wrap(clock + i * .5f) * 1.20f
            val cableY = .205f + x * .10f
            val sway = sin((slow + i * .5f) * TAU) * .0015f
            drawLine(Color(0xFFACBBC2), Offset(x, cableY), Offset(x + sway, cableY + .028f), .002f)
            drawRoundRect(Color(0xFFB56658), Offset(x - .021f + sway, cableY + .024f), Size(.042f, .030f), CornerRadius(.005f))
            drawRoundRect(Color(0xFF253F50), Offset(x - .016f + sway, cableY + .029f), Size(.032f, .013f), CornerRadius(.002f))
            drawLine(Color(0xFFD2AAA0), Offset(x + sway, cableY + .029f), Offset(x + sway, cableY + .042f), .0015f)
            drawLine(Color(0xFFDAA594), Offset(x - .014f + sway, cableY + .049f), Offset(x + .014f + sway, cableY + .049f), .0013f)
        }
        repeat(10) { i ->
            val p = field[i]
            val x = if (i % 2 == 0) .04f + p.x * .16f else .80f + p.x * .16f
            val progress = wrap(clock + p.phase)
            drawCircle(Color(0xFFCADDE0), .0014f * p.scale, Offset(x + sin((slow + p.phase) * TAU) * .007f, .71f + progress * .27f),
                alpha = sin(progress * PI) * .30f)
        }
    }

    private fun DrawScope.coral(clock: Float, slow: Float) = normalized {
        repeat(3) { i -> drawPath(rays[i], Color(0xFF8ED5C9), alpha = .018f + .008f * sin((slow + i * .2f) * TAU)) }
        repeat(5) { i ->
            val p = field[i]
            val x = -.08f + wrap(clock + p.phase) * 1.16f
            val y = .17f + i * .024f + sin((slow + p.phase) * TAU) * .008f
            translate(x, y) {
                scale(p.scale, p.scale, Offset.Zero) {
                    drawPath(fishBody, if (i % 2 == 0) Color(0xFF93A99A) else Color(0xFF8BA4B9), alpha = .65f)
                    drawLine(Color(0xFFD1DCC1), Offset(-.006f, -.001f), Offset(.010f, -.001f), .0012f, alpha = .45f)
                }
            }
        }
        repeat(12) { i ->
            val p = field[i]
            val progress = wrap(clock + p.phase)
            val x = if (i % 2 == 0) .065f + p.x * .12f else .84f + p.x * .10f
            val y = .94f - progress * .61f
            val alpha = sin(progress * PI) * .38f
            val center = Offset(x + sin((slow + p.phase) * TAU) * .006f, y)
            drawCircle(Color(0xFF83C9D0), .005f * p.scale, center, alpha = alpha, style = bubbleStroke)
            drawCircle(Color(0xFFE1F3DE), .0012f, center - Offset(.0015f, .0015f), alpha = alpha * .8f)
        }
    }

    private fun DrawScope.courtyard(clock: Float, slow: Float) {
        repeat(6) { i ->
            val x = .085f + i * .166f
            val y = lanternY(i)
            val breath = .92f + .08f * sin((slow + i * .13f) * TAU)
            drawRadialGlow(Offset(x * size.width, y * size.height), size.minDimension * .085f, glow, .16f * breath)
        }
        normalized {
            repeat(6) { i ->
                val x = .085f + i * .166f
                val y = lanternY(i)
                val sway = sin((clock + i * .13f) * TAU) * .002f
                drawLine(Color(0xFFAD7258), Offset(x, y + .021f), Offset(x + sway, y + .038f), .0017f, StrokeCap.Round)
                drawLine(Color(0xFFDBA36D), Offset(x - .010f, y), Offset(x + .010f, y), .001f, alpha = .35f + .1f * sin(slow * TAU))
            }
            repeat(8) { i ->
                val p = field[i]
                val x = if (i % 2 == 0) .075f + p.x * .08f else .845f + p.x * .08f
                val y = .73f + p.y * .20f
                val flutter = sin((clock * 2f + p.phase) * TAU)
                val center = Offset(x + sin((slow + p.phase) * TAU) * .008f, y + flutter * .005f)
                drawCircle(Color(0xFFDABC81), .0017f, center, alpha = .27f + .1f * flutter)
                drawLine(Color(0xFFBAA279), center - Offset(.002f, .001f), center + Offset(.002f, .001f), .001f, alpha = .25f)
            }
        }
    }

    private fun DrawScope.cavern(clock: Float, slow: Float) = normalized {
        repeat(8) { i ->
            val p = field[i]
            val x = if (i < 4) .05f + p.x * .16f else .79f + p.x * .17f
            val y = .60f + p.y * .28f
            val glint = .12f + .36f * (.5f + .5f * sin((clock + p.phase) * TAU))
            drawLine(Color(0xFFC7C6FC), Offset(x - .005f, y), Offset(x + .005f, y), .001f, alpha = glint)
            drawLine(Color(0xFFC7C6FC), Offset(x, y - .004f), Offset(x, y + .004f), .001f, alpha = glint)
            drawCircle(Color(0xFFE0DAFF), .0014f, Offset(x, y), alpha = glint)
        }
        repeat(12) { i ->
            val p = field[i]
            val y = .865f + i * .010f
            val shift = sin((slow + p.phase) * TAU) * .009f
            val x = .25f + p.x * .50f + shift
            drawLine(Color(0xFF9B9CD7), Offset(x, y), Offset(x + .025f + p.scale * .025f, y), .0014f, StrokeCap.Round,
                alpha = .07f + .06f * (.5f + .5f * sin((clock + p.phase) * TAU)))
        }
    }

    private data class Speck(val x: Float, val y: Float, val phase: Float, val scale: Float)
    private data class Layer(val path: Path, val color: Color)

    companion object {
        private const val TAU = 6.2831855f
        private const val PI = 3.1415927f
        private fun wrap(value: Float) = value - floor(value)
        private fun cleanPhase(value: Float) = if (value.isFinite()) wrap(value) else 0f
        private fun lanternY(i: Int) = .12f + sin((i + .5f) / 6f * PI) * .07f
        private fun gradient(vararg colors: Long) = Brush.verticalGradient(colors.map { Color(it) }, 0f, 1f)
        private fun path(block: Path.() -> Unit) = Path().apply(block)
        private fun polygon(vararg xy: Float) = path {
            moveTo(xy[0], xy[1])
            var i = 2
            while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }
            close()
        }

        /** All topology is authored once, not rebuilt as the clock advances. */
        private fun scenery(animation: BackgroundAnimation): List<Layer> = buildList {
            fun shape(color: Long, block: Path.() -> Unit) { add(Layer(path(block), Color(color))) }
            fun poly(color: Long, vararg xy: Float) { add(Layer(polygon(*xy), Color(color))) }
            fun rect(color: Long, x: Float, y: Float, w: Float, h: Float) = poly(color, x, y, x + w, y, x + w, y + h, x, y + h)
            when (animation) {
                BackgroundAnimation.SAFFRON_DUNES -> {
                    shape(0xFF77505A) {
                        moveTo(0f, .55f); cubicTo(.25f, .44f, .37f, .57f, .57f, .55f)
                        cubicTo(.77f, .53f, .87f, .45f, 1f, .50f); lineTo(1f, 1f); lineTo(0f, 1f); close()
                    }
                    shape(0xFF9F6757) {
                        moveTo(0f, .73f); cubicTo(.13f, .70f, .29f, .57f, .45f, .62f)
                        cubicTo(.65f, .67f, .76f, .71f, 1f, .63f); lineTo(1f, 1f); lineTo(0f, 1f); close()
                    }
                    shape(0xFFB98060) {
                        moveTo(0f, .73f); cubicTo(.18f, .68f, .27f, .59f, .44f, .62f)
                        cubicTo(.29f, .64f, .26f, .73f, .21f, .82f); lineTo(0f, .91f); close()
                    }
                    shape(0xFF68434B) {
                        moveTo(0f, .89f); cubicTo(.29f, .91f, .60f, .70f, .76f, .77f)
                        cubicTo(.88f, .83f, .95f, .83f, 1f, .79f); lineTo(1f, 1f); lineTo(0f, 1f); close()
                    }
                    shape(0xFF8C5A50) {
                        moveTo(.05f, .91f); cubicTo(.30f, .88f, .58f, .73f, .76f, .77f)
                        cubicTo(.59f, .78f, .51f, .88f, .47f, 1f); lineTo(0f, 1f); close()
                    }
                    repeat(7) { i ->
                        val y = .915f + i * .012f
                        shape(0x337E5049) { moveTo(0f, y); cubicTo(.15f, y - .015f, .28f, y - .06f, .37f, y - .08f)
                            cubicTo(.27f, y - .045f, .14f, y - .009f, 0f, y + .002f); close() }
                    }
                    poly(0xFF392D38, .89f, 1f, .902f, .79f, .91f, .79f, .914f, 1f)
                    poly(0xFF392D38, .902f, .90f, .867f, .868f, .863f, .82f, .873f, .82f, .879f, .857f, .904f, .875f)
                    poly(0xFF392D38, .91f, .85f, .937f, .827f, .94f, .774f, .949f, .774f, .95f, .843f, .91f, .871f)
                }
                BackgroundAnimation.ALPINE_CABLEWAY -> {
                    poly(0xFF4F6879, 0f, .60f, .13f, .29f, .25f, .47f, .41f, .28f, .65f, .60f, .84f, .32f, 1f, .49f, 1f, 1f, 0f, 1f)
                    poly(0xFFB1C2C7, .067f, .44f, .13f, .29f, .22f, .426f, .158f, .397f, .132f, .416f, .113f, .380f)
                    poly(0xFFA1B7C1, .32f, .39f, .41f, .28f, .505f, .408f, .443f, .383f, .41f, .361f, .386f, .397f)
                    poly(0xFF93AEBC, .763f, .436f, .84f, .32f, .933f, .428f, .867f, .397f, .83f, .408f, .816f, .380f)
                    poly(0xFF314D61, 0f, .75f, .19f, .48f, .43f, .70f, .64f, .55f, .88f, .71f, 1f, .53f, 1f, 1f, 0f, 1f)
                    shape(0xFF244452) { moveTo(0f, .82f); cubicTo(.30f, .64f, .42f, .79f, .59f, .75f)
                        cubicTo(.79f, .69f, .83f, .80f, 1f, .67f); lineTo(1f, 1f); lineTo(0f, 1f); close() }
                    shape(0xFF44717A) { moveTo(.50f, .78f); cubicTo(.63f, .85f, .31f, .90f, .38f, 1f)
                        lineTo(.64f, 1f); cubicTo(.44f, .91f, .71f, .84f, .51f, .78f); close() }
                    repeat(18) { i ->
                        val x = if (i < 9) i * .030f - .015f else .78f + (i - 9) * .032f
                        val y = .83f + (i % 3) * .047f
                        val h = .075f + (i % 4) * .021f
                        poly(0xFF153641, x, y - h, x + .025f, y - h * .43f, x + .015f, y - h * .43f,
                            x + .037f, y, x - .037f, y, x - .015f, y - h * .43f, x - .025f, y - h * .43f)
                    }
                    poly(0xFF8196A4, 0f, .204f, 1f, .304f, 1f, .306f, 0f, .206f)
                    rect(0xFF314955, .912f, .280f, .007f, .135f)
                    rect(0xFF314955, .883f, .292f, .065f, .006f)
                }
                BackgroundAnimation.CORAL_TWILIGHT -> {
                    shape(0xFF103C48) { moveTo(0f, .85f); cubicTo(.2f, .78f, .32f, .90f, .49f, .88f)
                        cubicTo(.68f, .86f, .85f, .78f, 1f, .82f); lineTo(1f, 1f); lineTo(0f, 1f); close() }
                    shape(0xFF0A2533) { moveTo(0f, .94f); cubicTo(.23f, .87f, .51f, .98f, .68f, .91f)
                        cubicTo(.84f, .85f, .95f, .91f, 1f, .89f); lineTo(1f, 1f); lineTo(0f, 1f); close() }
                    repeat(8) { i ->
                        val x = if (i < 4) .025f + i * .045f else .84f + (i - 4) * .048f
                        val h = .13f + (i % 3) * .045f
                        shape(if (i % 2 == 0) 0xFF286566 else 0xFF1B4F58) {
                            moveTo(x, 1f); cubicTo(x - .025f, .92f, x + .03f, 1f - h, x, .92f - h)
                            cubicTo(x + .063f, 1f - h, x - .004f, .93f, x + .015f, 1f); close()
                        }
                    }
                    repeat(6) { i ->
                        val x = if (i < 3) .07f + i * .053f else .80f + (i - 3) * .067f
                        val y = .90f + (i % 2) * .045f
                        val tint = if (i % 2 == 0) 0xFF8B657D else 0xFF557F83
                        rect(tint, x, y - .09f, .009f, .12f)
                        poly(tint, x, y - .035f, x - .032f, y - .068f, x - .033f, y - .115f, x - .025f, y - .118f,
                            x - .023f, y - .076f, x + .006f, y - .050f)
                        poly(tint, x + .006f, y - .056f, x + .029f, y - .078f, x + .029f, y - .130f, x + .036f, y - .13f,
                            x + .039f, y - .071f, x + .008f, y - .035f)
                    }
                    repeat(9) { i ->
                        val x = .13f + i * .084f
                        val y = .973f + (i % 2) * .009f
                        poly(0xFF355761, x - .013f, y, x, y - .012f, x + .019f, y - .004f, x + .025f, y + .008f, x - .013f, y + .008f)
                    }
                }
                BackgroundAnimation.LANTERN_COURTYARD -> {
                    rect(0xFF252D3D, 0f, .62f, 1f, .38f)
                    rect(0xFF1F2937, .12f, .65f, .76f, .008f)
                    shape(0xFF222536) { moveTo(0f, .17f); cubicTo(.09f, .20f, .19f, .18f, .27f, .125f)
                        lineTo(.22f, .222f); lineTo(0f, .25f); close() }
                    shape(0xFF222536) { moveTo(1f, .17f); cubicTo(.91f, .20f, .81f, .18f, .73f, .125f)
                        lineTo(.78f, .222f); lineTo(1f, .25f); close() }
                    rect(0xFF3F3540, .055f, .23f, .022f, .58f)
                    rect(0xFF3F3540, .923f, .23f, .022f, .58f)
                    rect(0xFF665052, .071f, .245f, .004f, .53f)
                    rect(0xFF665052, .925f, .245f, .004f, .53f)
                    shape(0xFF161F2C) { moveTo(0f, .84f); lineTo(1f, .84f); lineTo(1f, 1f); lineTo(0f, 1f); close() }
                    repeat(5) { i ->
                        val y = .855f + i * i * .007f
                        rect(0xFF303646, .12f, y, .76f, .002f)
                        poly(0xFF303646, .5f + (i - 2) * .074f, .84f, .502f + (i - 2) * .074f, .84f,
                            .5f + (i - 2) * .20f, 1f, .497f + (i - 2) * .20f, 1f)
                    }
                    repeat(6) { i ->
                        val x = .085f + i * .166f
                        val y = lanternY(i)
                        rect(0xFF544656, x, 0f, .0015f, y - .025f)
                        shape(0xFFBA735A) { moveTo(x - .016f, y - .021f); cubicTo(x - .027f, y - .012f, x - .027f, y + .012f, x - .016f, y + .021f)
                            lineTo(x + .016f, y + .021f); cubicTo(x + .027f, y + .012f, x + .027f, y - .012f, x + .016f, y - .021f); close() }
                        rect(0xFFDBA16A, x - .008f, y - .020f, .016f, .040f)
                        rect(0xFF674957, x - .017f, y - .023f, .034f, .004f)
                        rect(0xFF674957, x - .017f, y + .019f, .034f, .004f)
                    }
                    repeat(2) { side ->
                        val x = if (side == 0) .12f else .88f
                        rect(0xFF414052, x - .018f, .764f, .036f, .061f)
                        rect(0xFFAF865F, x - .011f, .744f, .022f, .026f)
                        poly(0xFF565063, x - .044f, .747f, x, .711f, x + .044f, .747f)
                        rect(0xFF565063, x - .040f, .818f, .080f, .013f)
                        repeat(3) { leaf ->
                            val dx = leaf * .019f - .02f
                            poly(0xFF344A47, x + dx, .907f, x + dx - .015f, .823f - leaf * .01f, x + dx + .009f, .849f)
                        }
                    }
                }
                BackgroundAnimation.CRYSTAL_CAVERN -> {
                    poly(0xFF24283E, 0f, 0f, 1f, 0f, 1f, .19f, .87f, .10f, .76f, .21f, .59f, .085f, .44f, .15f, .24f, .08f, .13f, .24f, 0f, .16f)
                    poly(0xFF191E30, 0f, 0f, .085f, 0f, .12f, .40f, .066f, .60f, .16f, .81f, .11f, 1f, 0f, 1f)
                    poly(0xFF1A2034, 1f, 0f, .94f, 0f, .89f, .37f, .956f, .61f, .84f, .82f, .88f, 1f, 1f, 1f)
                    shape(0xFF172D40) { moveTo(.10f, .88f); cubicTo(.34f, .82f, .69f, .84f, .91f, .87f)
                        lineTo(1f, 1f); lineTo(0f, 1f); close() }
                    repeat(10) { i ->
                        val x = if (i < 5) .025f + i * .042f else .79f + (i - 5) * .046f
                        val base = .92f + (i % 3) * .027f
                        val h = .12f + (i % 4) * .043f
                        val w = .022f + (i % 2) * .008f
                        poly(if (i % 2 == 0) 0xFF685C8E else 0xFF3D697C,
                            x - w, base, x - w, base - h * .68f, x, base - h, x + w, base - h * .70f, x + w, base)
                        poly(if (i % 2 == 0) 0xFF9683B4 else 0xFF69A0AC,
                            x - w, base - h * .68f, x, base - h, x + .003f, base - h * .64f, x + .003f, base, x - w, base)
                        poly(0x55403972, x + .003f, base - h * .64f, x + w, base - h * .70f, x + w, base, x + .003f, base)
                    }
                    repeat(5) { i ->
                        val x = .18f + i * .16f
                        val h = .05f + (i % 3) * .022f
                        poly(0xFF3E4161, x - .021f, .085f, x + .028f, .085f, x + .007f, .085f + h)
                        poly(0xFF59607E, x - .021f, .085f, x - .003f, .085f, x + .007f, .085f + h)
                    }
                }
                else -> Unit
            }
        }
    }
}
