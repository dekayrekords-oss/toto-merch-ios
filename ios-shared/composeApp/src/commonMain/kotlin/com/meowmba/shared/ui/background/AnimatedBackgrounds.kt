package com.meowmba.shared.ui.background

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The non-weather animated backgrounds.
 *
 * Same construction rules as [WeatherScene]: layered canvases with real GPU blur on the far
 * layers, seeded particle fields so a scene never reshuffles itself between recompositions,
 * and gradient-stroked shapes rather than hard-edged primitives. They are meant to be
 * looked *past* — every palette here stays dark and low-contrast so the companion's face
 * remains the brightest thing on screen.
 */
@Composable
fun AnimatedBackground(animation: BackgroundAnimation, modifier: Modifier = Modifier, paused: Boolean = false) {
    // Ambient particles and washes run on multi-second cycles; 15 FPS is
    // indistinguishable from 31 and costs less than half the redraws.
    val sceneClock = rememberSceneClock(paused, interacting = false)
    // Read inside Canvas, never in composition. Both layers share one clock.
    val clock by remember(sceneClock) { derivedStateOf { sceneClock.phase(BG_CYCLE_MS) } }
    val slow by remember(sceneClock) { derivedStateOf { sceneClock.phase(BG_SLOW_MS) } }
    if (isScenicBackground(animation)) {
        ScenicBackground(animation, modifier, clock = { clock }, slow = { slow })
        return
    }
    if (animation.isPetEnvironment) {
        IllustratedEnvironmentBackground(animation, modifier, clock = { clock }, slow = { slow })
        return
    }
    if (isCompanionSceneBackground(animation)) {
        val companionScene = remember { CompanionSceneBackgroundPainter() }
        Box(modifier = modifier) {
            Canvas(Modifier.fillMaxSize()) { companionScene.draw(this, animation, clock, slow) }
        }
        return
    }
    val field = remember(animation) { BgField(animation.ordinal) }

    Box(modifier = modifier) {
        when (animation) {
            BackgroundAnimation.AURORA -> {
                Canvas(Modifier.fillMaxSize()) { skyWash(auroraSky) }
                Canvas(Modifier.fillMaxSize()) { gentleParallaxWash(slow) }
                Canvas(Modifier.fillMaxSize()) { stars(field, clock, 0.55f) }
                Canvas(Modifier.fillMaxSize().blur(30.dp)) { aurora(clock, slow, 1.35f, 0.55f) }
                Canvas(Modifier.fillMaxSize().blur(10.dp)) { aurora(clock + 0.37f, slow, 0.9f, 0.85f) }
            }

            BackgroundAnimation.STARFIELD -> {
                Canvas(Modifier.fillMaxSize()) { skyWash(deepSky) }
                Canvas(Modifier.fillMaxSize()) { gentleParallaxWash(slow) }
                Canvas(Modifier.fillMaxSize().blur(14.dp)) { stars(field, clock * 0.6f, 1.4f) }
                Canvas(Modifier.fillMaxSize()) { stars(field, clock, 1f) }
                Canvas(Modifier.fillMaxSize()) { shootingStar(slow) }
            }

            BackgroundAnimation.NEBULA -> {
                Canvas(Modifier.fillMaxSize()) { skyWash(deepSky) }
                Canvas(Modifier.fillMaxSize()) { gentleParallaxWash(slow) }
                Canvas(Modifier.fillMaxSize().blur(40.dp)) { nebula(slow) }
                Canvas(Modifier.fillMaxSize()) { stars(field, clock, 0.9f) }
            }

            BackgroundAnimation.EMBERS -> {
                Canvas(Modifier.fillMaxSize()) { skyWash(emberSky) }
                Canvas(Modifier.fillMaxSize().blur(22.dp)) { embers(field, clock, slow, 1.7f, 0.4f) }
                Canvas(Modifier.fillMaxSize()) { embers(field, clock, slow, 0.9f, 0.95f) }
            }

            else -> Unit // Weather-backed entries are drawn by WeatherScene.
        }
    }
}

private class BgField(seed: Int) {
    private val rng = Random(seed * 6151 + 29)
    val points = List(220) { BgP(rng) }
}

private class BgP(rng: Random) {
    val x = rng.nextFloat()
    val y = rng.nextFloat()
    val phase = rng.nextFloat()
    val speed = 0.4f + rng.nextFloat() * 1.2f
    val scale = 0.4f + rng.nextFloat()
}

private fun wrap01(v: Float) = v - kotlin.math.floor(v)

private val deepSky = listOf(Color(0xFF070B18), Color(0xFF0B1024), Color(0xFF05070F))
private val auroraSky = listOf(Color(0xFF04121A), Color(0xFF071A26), Color(0xFF03080E))
private val emberSky = listOf(Color(0xFF150703), Color(0xFF230C05), Color(0xFF0A0402))

private fun DrawScope.skyWash(ramp: List<Color>) {
    drawRect(brush = Brush.verticalGradient(ramp))
}

private fun DrawScope.stars(f: BgField, clock: Float, gain: Float) {
    val glow = RadialGlow.brush(Color.White)
    f.points.forEach { p ->
        val tw = 0.2f + 0.8f * (0.5f + 0.5f * sin((clock + p.phase) * 4f * PI.toFloat()))
        val r = (0.9f + 2.0f * p.scale) * gain
        // Very slow lateral drift so the field is alive without ever looking like motion.
        val c = Offset(wrap01(p.x + clock * 0.02f * p.speed) * size.width, p.y * size.height)
        drawRadialGlow(c, r, glow, 0.9f * tw * gain)
    }
}

/** One streak per slow cycle, on a diagonal, gone in a fraction of a second. */
private fun DrawScope.shootingStar(slow: Float) {
    val t = (slow - 0.42f) / 0.05f
    if (t < 0f || t > 1f) return
    val fade = sin(t * PI.toFloat())
    val start = Offset(size.width * (0.15f + 0.6f * t), size.height * (0.08f + 0.35f * t))
    val end = start + Offset(-size.width * 0.14f, -size.height * 0.06f)
    drawLine(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.85f * fade), Color.Transparent),
            start = start, end = end,
        ),
        start = start, end = end, strokeWidth = 3f, cap = StrokeCap.Round,
    )
}

/**
 * Aurora curtains: vertical ribbons whose top and bottom edges wave independently.
 *
 * Built as filled paths rather than stacked strokes so the light has a body; the vertical
 * gradient inside each ribbon is what makes it hang from the top of the frame instead of
 * floating in the middle of it.
 */
private fun DrawScope.aurora(clock: Float, slow: Float, width: Float, gain: Float) {
    val ribbons = listOf(
        Triple(0.18f, Color(0xFF3DF5A5), 0.9f),
        Triple(0.42f, Color(0xFF4FC3F7), 1.15f),
        Triple(0.66f, Color(0xFFB388FF), 0.8f),
        Triple(0.86f, Color(0xFF3DF5A5), 0.7f),
    )
    ribbons.forEachIndexed { i, (cx, colour, h) ->
        val w = size.width * 0.22f * width
        val sway = sin((slow + i * 0.23f) * 2f * PI.toFloat()) * size.width * 0.06f
        val x = size.width * cx + sway
        val top = size.height * 0.02f
        val bottom = size.height * (0.30f + 0.28f * h)

        val path = Path().apply {
            moveTo(x - w / 2f, top)
            // Wavy inner edge, sampled down the ribbon.
            var t = 0f
            while (t <= 1f) {
                val y = top + (bottom - top) * t
                val wobble = sin((clock * 2f + t * 3f + i) * PI.toFloat()) * w * 0.22f
                lineTo(x - w / 2f + wobble, y)
                t += 0.1f
            }
            t = 1f
            while (t >= 0f) {
                val y = top + (bottom - top) * t
                val wobble = sin((clock * 2f + t * 3.4f + i + 0.7f) * PI.toFloat()) * w * 0.22f
                lineTo(x + w / 2f + wobble, y)
                t -= 0.1f
            }
            close()
        }
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                listOf(
                    colour.copy(alpha = 0.34f * gain),
                    colour.copy(alpha = 0.16f * gain),
                    Color.Transparent,
                ),
                startY = top, endY = bottom,
            ),
        )
    }
}

/** Slow overlapping colour clouds. Large enough that no single one reads as a shape. */
private fun DrawScope.nebula(slow: Float) {
    val blobs = listOf(
        Triple(0.28f to 0.30f, Color(0xFF7C4DFF), 0.95f),
        Triple(0.72f to 0.22f, Color(0xFF00B8D4), 0.85f),
        Triple(0.55f to 0.62f, Color(0xFFD500F9), 0.75f),
        Triple(0.15f to 0.70f, Color(0xFF1DE9B6), 0.6f),
    )
    blobs.forEachIndexed { i, (pos, colour, scale) ->
        val (px, py) = pos
        val drift = sin((slow + i * 0.31f) * 2f * PI.toFloat())
        val centre = Offset(
            size.width * px + drift * size.width * 0.05f,
            size.height * py + drift * size.height * 0.03f,
        )
        val r = size.maxDimension * 0.55f * scale
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    colour.copy(alpha = 0.20f),
                    colour.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = centre, radius = r,
            ),
            radius = r, center = centre,
        )
    }
}

/** Warm sparks rising and fading, over a dark hearth glow at the bottom of the frame. */
private fun DrawScope.embers(f: BgField, clock: Float, slow: Float, scale: Float, gain: Float) {
    // Hearth glow, so the sparks have somewhere to come from.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color.Transparent, Color(0xFFFF6A00).copy(alpha = 0.10f * gain)),
            startY = size.height * 0.45f, endY = size.height,
        ),
    )
    f.points.forEachIndexed { i, p ->
        if (i % 2 == 0) return@forEachIndexed
        // Rises: subtract so the position climbs monotonically with the clock.
        val rise = wrap01(p.y - clock * 0.5f * p.speed)
        val y = rise * size.height
        val sway = sin((clock * 2f + p.phase * 6f) * PI.toFloat()) * size.width * 0.03f
        val x = p.x * size.width + sway + sin((slow + p.phase) * 2f * PI.toFloat()) * size.width * 0.02f
        // Brightest in the middle of the climb, dead at both ends.
        val life = sin(rise * PI.toFloat())
        val r = (1.4f + 2.2f * p.scale) * scale
        val c = Offset(x, y)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color(0xFFFFD08A).copy(alpha = 0.85f * life * gain),
                    Color(0xFFFF6A00).copy(alpha = 0.30f * life * gain),
                    Color.Transparent,
                ),
                center = c, radius = r * 2.2f,
            ),
            radius = r * 2.2f, center = c,
        )
    }
}

/**
 * A still, cheap thumbnail of an animation for the picker grid.
 *
 * Drawn from the same code at a frozen clock rather than as a bitmap: eleven live canvases
 * in a scrolling grid is a lot of GPU for a menu, and a frozen frame still shows the
 * palette and the shape language, which is what the choice is actually about.
 */
@Composable
fun AnimationThumbnail(animation: BackgroundAnimation, modifier: Modifier = Modifier) {
    if (isScenicBackground(animation)) {
        ScenicBackground(animation, modifier, clock = { .25f }, slow = { .15f })
        return
    }
    if (animation.isPetEnvironment) {
        IllustratedEnvironmentBackground(animation, modifier, clock = { .25f }, slow = { .15f }, thumbnail = true)
        return
    }
    if (isCompanionSceneBackground(animation)) {
        val companionScene = remember { CompanionSceneBackgroundPainter() }
        Canvas(modifier) { companionScene.draw(this, animation, .25f, .15f) }
        return
    }
    val field = remember(animation) { BgField(animation.ordinal) }
    Canvas(modifier) {
        when (animation) {
            BackgroundAnimation.AURORA -> {
                skyWash(auroraSky); stars(field, 0.3f, 0.5f); aurora(0.25f, 0.15f, 1f, 0.9f)
            }
            BackgroundAnimation.STARFIELD -> { skyWash(deepSky); stars(field, 0.3f, 1.1f) }
            BackgroundAnimation.NEBULA -> { skyWash(deepSky); nebula(0.2f); stars(field, 0.3f, 0.8f) }
            BackgroundAnimation.EMBERS -> { skyWash(emberSky); embers(field, 0.3f, 0.1f, 1f, 1f) }
            else -> drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF1B2A3A), Color(0xFF0D1117)),
                ),
            )
        }
    }
}

/** Flat swatch behind a weather thumbnail's emoji, keyed to the condition's palette. */
fun weatherSwatch(id: String): List<Color> = when (id) {
    "clear" -> listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
    "clouds" -> listOf(Color(0xFF64748B), Color(0xFF3F4A5A))
    "rain" -> listOf(Color(0xFF3E5C76), Color(0xFF1F3247))
    "snow" -> listOf(Color(0xFFA5C7DE), Color(0xFF6B8CA6))
    "fog" -> listOf(Color(0xFFE9EEF3), Color(0xFFB9C4CE))
    "dust" -> listOf(Color(0xFF8A6A3B), Color(0xFF4A3720))
    "storm" -> listOf(Color(0xFF4C3B6E), Color(0xFF241B38))
    else -> listOf(Color(0xFF475569), Color(0xFF29323F))
}


/** Ultra-soft luminance wash for ambient backgrounds — battery-friendly, not a second particle system. */
private fun DrawScope.gentleParallaxWash(slow: Float) {
    val a = 0.025f + 0.02f * (0.5f + 0.5f * sin(slow * 2f * PI.toFloat()))
    val dx = sin(slow * 2f * PI.toFloat()) * size.width * 0.02f
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = a), Color.Transparent),
            center = Offset(size.width * 0.5f + dx, size.height * 0.35f),
            radius = size.minDimension * 0.55f,
        )
    )
}

private const val BG_CYCLE_MS = 22_000
private const val BG_SLOW_MS = 17_000
