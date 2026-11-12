package com.meowmba.shared.ui

import com.meowmba.shared.ui.background.rememberSceneClock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.meowmba.shared.weather.MoonPhase
import com.meowmba.shared.weather.TimeOfDay
import com.meowmba.shared.weather.WeatherCondition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Atmospheric weather drawn behind the companion.
 *
 * The first version of this looked cheap for concrete reasons, all of which are addressed
 * here rather than papered over:
 *
 *  - **Real blur.** Three stacked canvases at 26dp / 10dp / 0dp. minSdk is 31, so
 *    [Modifier.blur] is a GPU RenderEffect. Soft falloff is most of what makes weather
 *    look like weather; the previous version had none and every edge was hard and aliased.
 *  - **Depth.** Far elements are large, slow, dim and heavily blurred; near ones are
 *    smaller, faster, brighter and sharp. One flat layer at one speed reads as a
 *    screensaver.
 *  - **Soft particles.** Radial-gradient sprites and gradient-stroked streaks with round
 *    caps, instead of hard-edged circles and 1.8px lines.
 *  - **Real shapes.** Clouds are multi-lobe [Path]s, not three overlapping ovals.
 *  - **No blobs in the air.** Fog and dust are *bands*, never ovals. Drawing haze as a
 *    radial-gradient oval reads as a circle no matter how soft the falloff is, and once
 *    you see the circles you cannot unsee them. Both are now edgeless full-width sheets
 *    whose horizontal texture comes from a mirrored, drifting gradient — there is no shape
 *    boundary anywhere on screen to notice.
 *  - **Wind.** A bounded lateral offset pushes particles sideways, so motion is not a dead
 *    straight line. It is added, never multiplied into the accumulating position — doing
 *    that made the rain travel UPWARDS whenever the gust fell faster than the clock rose.
 *  - **Time of day.** The sky is tinted by [TimeOfDay], not by a day/night boolean, and the
 *    sun sits low and warm at dawn and dusk. Ambient mode leaves this running all day, so
 *    "it is 6pm" has to be visible or the scene stops matching the window.
 */
@Composable
fun WeatherScene(
    condition: WeatherCondition,
    isDay: Boolean,
    modifier: Modifier = Modifier,
    /** Defaults to the real clock, reconciled against what the weather service reported. */
    timeOfDay: TimeOfDay = rememberTimeOfDay(isDay),
    /**
     * Global strength, 0..1. Full when the scene is the *background*; turned down when it
     * is layered in FRONT of the companion, where a full-strength fog turns the cat into a
     * grey smudge. Applied as one layer alpha rather than threaded through every colour, so
     * the palettes below can be written for the case where you can actually see them.
     */
    intensity: Float = 1f,
    /** Tonight's real moon phase (0 new, 0.5 full). */
    moonPhase: Float = rememberMoonPhase(),
    paused: Boolean = false,
) {
    // Weather is ambient drift on multi-second cycles: 15 FPS, not 31.
    val sceneClock = rememberSceneClock(paused, interacting = false)
    val clock by remember(sceneClock) { derivedStateOf { sceneClock.phase(CYCLE_MS) } }
    // Independent slower clock for gusts and lightning, so neither locks to the particle
    // period and repeats visibly.
    val slow by remember(sceneClock) { derivedStateOf { sceneClock.phase(SLOW_CYCLE_MS) } }

    val field = remember(condition) { Field(condition) }

    Box(modifier = modifier.alpha(intensity.coerceIn(0f, 1f))) {
        // Atmosphere wash: unifies the palette so particles sit IN a sky rather than on
        // top of a black rectangle.
        Canvas(Modifier.fillMaxSize()) { drawAtmosphere(condition, timeOfDay, slow) }

        // FAR — heavy blur, big, slow, dim.
        Canvas(Modifier.fillMaxSize().blur(FAR_BLUR)) {
            drawLayer(condition, timeOfDay, field, clock, slow, Depth.FAR, moonPhase)
        }
        // MID — some blur.
        Canvas(Modifier.fillMaxSize().blur(MID_BLUR)) {
            drawLayer(condition, timeOfDay, field, clock, slow, Depth.MID, moonPhase)
        }
        // NEAR — sharp, fast, brightest. Sharpness only reads as sharp against blurred
        // layers behind it.
        Canvas(Modifier.fillMaxSize()) {
            drawLayer(condition, timeOfDay, field, clock, slow, Depth.NEAR, moonPhase)
        }
        // Flash sits above everything: lightning lights the whole scene, not one layer.
        if (condition == WeatherCondition.THUNDERSTORM) {
            Canvas(Modifier.fillMaxSize()) { drawFlash(slow) }
        }
    }
}

/**
 * The current [TimeOfDay] straight off the clock, re-checked every ten minutes.
 *
 * Cheap enough to leave running for hours (ambient mode does exactly that) and fine-grained
 * enough that the sky has always changed by the time you look up again.
 */
@Composable
fun rememberClockTimeOfDay(): TimeOfDay {
    var value by remember { mutableStateOf(TimeOfDay.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            value = TimeOfDay.now()
            kotlinx.coroutines.delay(TIME_OF_DAY_POLL_MS)
        }
    }
    return value
}

/** The clock, corrected by what the weather service says about the horizon. */
@Composable
fun rememberTimeOfDay(isDay: Boolean): TimeOfDay =
    TimeOfDay.reconcile(rememberClockTimeOfDay(), isDay)

private enum class Depth(val scale: Float, val speed: Float, val alpha: Float) {
    FAR(1.9f, 0.45f, 0.30f),
    MID(1.25f, 0.85f, 0.55f),
    NEAR(0.8f, 1.5f, 0.95f),
}

// ── Particle field ───────────────────────────────────────────────────────────────

/** Seeded so the same condition always looks the same; a reshuffling scene reads as a bug. */
private class Field(condition: WeatherCondition) {
    private val rng = Random(condition.ordinal * 7919 + 13)
    val drops = List(150) { P(rng) }
    val flakes = List(110) { P(rng) }
    val motes = List(200) { P(rng) }
    val clouds = List(9) { P(rng) }
    val stars = List(90) { P(rng) }

    fun forDepth(list: List<P>, d: Depth): List<P> = when (d) {
        Depth.FAR -> list.filterIndexed { i, _ -> i % 3 == 0 }
        Depth.MID -> list.filterIndexed { i, _ -> i % 3 == 1 }
        Depth.NEAR -> list.filterIndexed { i, _ -> i % 3 == 2 }
    }
}

private class P(rng: Random) {
    val x = rng.nextFloat()
    val y = rng.nextFloat()
    val phase = rng.nextFloat()
    val speed = 0.65f + rng.nextFloat() * 0.8f
    val scale = 0.55f + rng.nextFloat() * 0.9f
    val tilt = -0.5f + rng.nextFloat()
}

private fun wrap(v: Float) = v - kotlin.math.floor(v)

/**
 * Bounded wind wobble in -1..1, to be ADDED as a small offset — never multiplied into a
 * term that accumulates with the clock.
 *
 * The first version returned a factor around 1.0 and multiplied it into `clock * speed`.
 * Because the factor itself oscillates, the product is not monotonic: when the gust falls
 * faster than the clock rises, the position DECREASES and the rain visibly travels
 * upwards. Gusts belong on velocity or on a bounded lateral offset, not on integrated
 * position.
 */
private fun wind(slow: Float, phase: Float) =
    0.7f * sin((slow + phase) * 2f * PI.toFloat()) +
        0.3f * sin((slow * 3.1f + phase) * 2f * PI.toFloat())

// ── Dispatch ─────────────────────────────────────────────────────────────────────

private fun DrawScope.drawLayer(
    condition: WeatherCondition,
    tod: TimeOfDay,
    f: Field,
    clock: Float,
    slow: Float,
    d: Depth,
    moon: Float,
) {
    when (condition) {
        WeatherCondition.CLEAR ->
            if (tod == TimeOfDay.NIGHT) night(f, clock, d, moon) else sun(clock, slow, d, tod)

        WeatherCondition.PARTLY_CLOUDY -> {
            if (tod == TimeOfDay.NIGHT) night(f, clock, d, moon) else sun(clock, slow, d, tod)
            clouds(f, clock, slow, d, tint = cloudTint(cloudWhite, tod), density = 0.55f)
        }

        WeatherCondition.CLOUDY ->
            clouds(f, clock, slow, d, tint = cloudTint(cloudGrey, tod), density = 1f)

        WeatherCondition.FOG -> fog(clock, slow, d, tod)

        WeatherCondition.DRIZZLE -> {
            clouds(f, clock, slow, d, tint = cloudTint(cloudGrey, tod), density = 0.7f)
            rain(f, clock, slow, d, length = 0.030f, width = 1.4f, alpha = 0.45f)
        }

        WeatherCondition.RAIN -> {
            clouds(f, clock, slow, d, tint = cloudTint(cloudStorm, tod), density = 1f)
            rain(f, clock, slow, d, length = 0.075f, width = 2.2f, alpha = 0.70f)
        }

        WeatherCondition.SNOW -> {
            clouds(f, clock, slow, d, tint = cloudTint(cloudWhite, tod), density = 0.6f)
            snow(f, clock, slow, d)
        }

        WeatherCondition.THUNDERSTORM -> {
            clouds(f, clock, slow, d, tint = cloudTint(cloudStorm, tod), density = 1.2f)
            rain(f, clock, slow, d, length = 0.090f, width = 2.4f, alpha = 0.70f)
            if (d == Depth.MID) bolt(slow)
        }

        WeatherCondition.DUST -> dust(f, clock, slow, d)

        WeatherCondition.UNKNOWN -> Unit
    }
}

// ── Palette ──────────────────────────────────────────────────────────────────────

private val cloudWhite = Color(0xFFEDF3FA)
private val cloudGrey = Color(0xFFA8B4C4)
private val cloudStorm = Color(0xFF6C7787)

/**
 * Clouds take the colour of the light hitting them: gold at dawn, rose at dusk, blue-black
 * at night. Overcast at 7pm and overcast at noon are the same grey without this, which is
 * the thing that made the old scene feel like it ran on a timer instead of a clock.
 */
private fun cloudTint(base: Color, tod: TimeOfDay): Color = when (tod) {
    TimeOfDay.DAWN -> base.blend(Color(0xFFFFC79A), 0.35f)
    TimeOfDay.DAY -> base
    TimeOfDay.DUSK -> base.blend(Color(0xFFFF9E7A), 0.38f)
    TimeOfDay.NIGHT -> base.blend(Color(0xFF2A3550), 0.55f)
}

private fun Color.blend(other: Color, t: Float) = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = alpha,
)

/**
 * Sky wash behind everything, breathing slightly.
 *
 * Without this the particles sat on flat black and looked like sprites on a background;
 * with it they sit inside a sky. Two passes: the condition decides the base, then the time
 * of day tints it — so rain at dusk is still obviously rain, and still obviously dusk.
 */
private fun DrawScope.drawAtmosphere(condition: WeatherCondition, tod: TimeOfDay, slow: Float) {
    val breathe = 0.9f + 0.1f * sin(slow * 2f * PI.toFloat())
    // Written for full strength (background use). The overlay case scales the whole scene
    // down through the layer alpha, so these do not have to be timid.
    val (top, bottom) = when (condition) {
        WeatherCondition.DUST -> Color(0x73C08A3E) to Color(0x2E6B4A22)
        WeatherCondition.THUNDERSTORM -> Color(0x6B3B3054) to Color(0x1F161226)
        WeatherCondition.RAIN, WeatherCondition.DRIZZLE ->
            Color(0x662E4459) to Color(0x1F121C26)
        WeatherCondition.SNOW -> Color(0x668FA9BF) to Color(0x282B3A47)
        // Fog is white haze, not blue-grey murk: the wash has to be near-white or the bands
        // on top of it read as smoke.
        WeatherCondition.FOG -> Color(0x66E9EEF3) to Color(0x33C4CED8)
        WeatherCondition.CLOUDY -> Color(0x5C6B788A) to Color(0x1F191F26)
        else -> when (tod) {
            TimeOfDay.NIGHT -> Color(0x4D16233D) to Color(0x0D0A0F1A)
            // Daytime clear sky. The old value was so faint that "day" and "overcast dusk"
            // were the same near-black wash.
            else -> Color(0x7A3D86C7) to Color(0x1F123049)
        }
    }
    drawRect(
        brush = Brush.verticalGradient(
            listOf(top.copy(alpha = top.alpha * breathe), bottom),
        ),
    )

    // Time-of-day tint. Fog stays untinted — the whole point of it is that it is white.
    if (condition == WeatherCondition.FOG) return
    val (tintTop, tintBottom) = when (tod) {
        TimeOfDay.DAWN -> Color(0x33FFB877) to Color(0x1AFF8FA3)
        TimeOfDay.DAY -> return
        TimeOfDay.DUSK -> Color(0x3DFF8A54) to Color(0x2E6B3A6E)
        TimeOfDay.NIGHT -> Color(0x33101B33) to Color(0x4D05070E)
    }
    drawRect(brush = Brush.verticalGradient(listOf(tintTop, tintBottom)))
}

// ── Effects ──────────────────────────────────────────────────────────────────────

/**
 * Sun position and colour by time of day.
 *
 * A low, red, huge sun is most of what "evening" looks like; keeping the noon sun pinned in
 * the top-right corner and just dimming it made dusk look like an overcast noon.
 */
private fun sunPlacement(tod: TimeOfDay): Triple<Float, Float, List<Color>> = when (tod) {
    TimeOfDay.DAWN -> Triple(
        0.24f, 0.30f,
        listOf(Color(0xFFFFF0D2), Color(0xFFFFC48C), Color(0xFFFF9E7A)),
    )
    TimeOfDay.DUSK -> Triple(
        0.76f, 0.36f,
        listOf(Color(0xFFFFE7C0), Color(0xFFFFA35C), Color(0xFFFF6B4A)),
    )
    else -> Triple(
        0.78f, 0.15f,
        listOf(Color(0xFFFFF6DC), Color(0xFFFFE3A3), Color(0xFFFFD98A)),
    )
}

private fun DrawScope.sun(clock: Float, slow: Float, d: Depth, tod: TimeOfDay) {
    val (cx, cy, ramp) = sunPlacement(tod)
    val centre = Offset(size.width * cx, size.height * cy)
    // Low sun reads as bigger: the horizon comparison is what sells it.
    val swell = if (tod == TimeOfDay.DAY) 1f else 1.25f
    val r = size.minDimension * 0.26f * d.scale * swell
    val pulse = 0.94f + 0.06f * sin(slow * 2f * PI.toFloat())

    when (d) {
        // Wide bloom, heavily blurred: this is the layer that makes it feel like light.
        Depth.FAR -> drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    ramp[1].copy(alpha = 0.35f),
                    ramp[2].copy(alpha = 0.12f),
                    ramp[2].copy(alpha = 0f),
                ),
                center = centre, radius = r * 1.5f * pulse,
            ),
            radius = r * 1.5f * pulse, center = centre,
        )
        // Volumetric rays.
        Depth.MID -> rotate(clock * 360f, pivot = centre) {
            repeat(20) { i ->
                val a = (i / 20f) * 2f * PI.toFloat()
                val len = r * (0.85f + 0.25f * sin(clock * 6f * PI.toFloat() + i))
                drawLine(
                    brush = Brush.linearGradient(
                        listOf(ramp[1].copy(alpha = 0.30f), ramp[1].copy(alpha = 0f)),
                        start = centre + Offset(cos(a) * r * 0.35f, sin(a) * r * 0.35f),
                        end = centre + Offset(cos(a) * len, sin(a) * len),
                    ),
                    start = centre + Offset(cos(a) * r * 0.35f, sin(a) * r * 0.35f),
                    end = centre + Offset(cos(a) * len, sin(a) * len),
                    strokeWidth = 7f, cap = StrokeCap.Round,
                )
            }
        }
        // Core, with a soft edge rather than a hard disc.
        Depth.NEAR -> drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    ramp[0].copy(alpha = 0.80f),
                    ramp[1].copy(alpha = 0.60f),
                    ramp[2].copy(alpha = 0f),
                ),
                center = centre, radius = r * 0.55f * pulse,
            ),
            radius = r * 0.55f * pulse, center = centre,
        )
    }
}

/**
 * Night sky: stars everywhere, and the moon — at tonight's actual phase — on the near layer.
 *
 * The lit shape is built the way the sky builds it: a full disc, minus a shadow bounded by
 * the dark limb (half the circle) and the terminator (half an ellipse whose width is
 * `|cos|` of the phase angle). That single ellipse is what makes a crescent a crescent and
 * a gibbous a gibbous — at the quarters its width goes to zero and the terminator is
 * correctly a straight line.
 */
private fun DrawScope.night(f: Field, clock: Float, d: Depth, moon: Float) {
    stars(f, clock, d)
    if (d != Depth.NEAR) return
    val centre = Offset(size.width * 0.74f, size.height * 0.16f)
    val r = size.minDimension * 0.09f
    val lit = MoonPhase.illumination(moon)

    // Halo tracks how much moon there is to glow. A new moon lighting up the sky would be
    // the most obviously wrong thing on screen.
    if (lit > 0.02f) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x40E8F1FF).copy(alpha = 0.25f * lit), Color(0x00E8F1FF)),
                center = centre, radius = r * 2.6f,
            ),
            radius = r * 2.6f, center = centre,
        )
    }

    // Earthshine: the unlit part is not invisible, it is very dark grey.
    drawCircle(color = Color(0x1AB8C6DE), radius = r, center = centre)

    if (lit <= 0.005f) return
    clipPath(moonLitPath(centre, r, moon)) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFF3F7FF), Color(0xFFD8E2F2)),
                center = centre - Offset(r * 0.25f, r * 0.25f), radius = r * 1.4f,
            ),
            radius = r, center = centre,
        )
    }
}

/**
 * The lit region of the moon at [phase] (0 new, 0.5 full).
 *
 * Traced as one closed path: the lit limb (a semicircle) followed by the terminator (a
 * semi-ellipse) back to the start. Which semicircle, and which way the ellipse bulges,
 * are the only two things that change between a crescent and a gibbous.
 *
 * Angles follow the canvas convention — 0° at 3 o'clock, positive clockwise — so 270° is
 * the top of the disc and 90° the bottom.
 */
private fun moonLitPath(centre: Offset, r: Float, phase: Float): Path {
    val waxing = MoonPhase.isWaxing(phase)
    val bulge = MoonPhase.terminatorBulge(phase)
    val discRect = androidx.compose.ui.geometry.Rect(centre, r)
    val termHalfWidth = (kotlin.math.abs(bulge) * r).coerceAtLeast(0.01f)
    val termRect = androidx.compose.ui.geometry.Rect(
        left = centre.x - termHalfWidth,
        top = centre.y - r,
        right = centre.x + termHalfWidth,
        bottom = centre.y + r,
    )

    return Path().apply {
        // Lit limb: the right half of the disc when waxing, the left half when waning.
        // Waxing runs top → bottom, waning bottom → top.
        arcTo(discRect, if (waxing) -90f else 90f, 180f, true)
        // Terminator, closing the shape back to where the limb started. A positive bulge
        // (before the quarter) puts the ellipse's belly on the +x side, a negative one on
        // the -x side; combined with which limb is lit, that is the whole phase.
        arcTo(
            rect = termRect,
            startAngleDegrees = if (waxing) 90f else 270f,
            sweepAngleDegrees = if (bulge > 0f) -180f else 180f,
            forceMoveTo = false,
        )
        close()
    }
}

/** Tonight's phase, refreshed hourly — the moon does not move fast enough to poll harder. */
@Composable
fun rememberMoonPhase(): Float {
    var value by remember { mutableStateOf(MoonPhase.fraction(com.meowmba.shared.core.PlatformTime.currentMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            value = MoonPhase.fraction(com.meowmba.shared.core.PlatformTime.currentMillis())
            kotlinx.coroutines.delay(MOON_POLL_MS)
        }
    }
    return value
}

private fun DrawScope.stars(f: Field, clock: Float, d: Depth) {
    f.forDepth(f.stars, d).forEach { p ->
        val tw = 0.25f + 0.75f * (0.5f + 0.5f * sin((clock + p.phase) * 4f * PI.toFloat()))
        val r = (1.2f + 2.2f * p.scale) * (2f - d.scale)
        val c = Offset(p.x * size.width, p.y * size.height * 0.72f)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f * tw * d.alpha),
                    Color.White.copy(alpha = 0f),
                ),
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }
}

/** Multi-lobe path. A cloud silhouette is what three ovals were failing to be. */
private fun cloudPath(x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
    val lobes = listOf(
        Triple(0.00f, 0.42f, 0.30f),
        Triple(0.18f, 0.16f, 0.40f),
        Triple(0.44f, 0.06f, 0.46f),
        Triple(0.68f, 0.20f, 0.36f),
        Triple(0.84f, 0.44f, 0.26f),
    )
    lobes.forEach { (lx, ly, lr) ->
        addOval(
            androidx.compose.ui.geometry.Rect(
                offset = Offset(x + w * lx, y + h * ly),
                size = Size(w * lr * 1.35f, h * lr * 2.0f),
            )
        )
    }
    // Flat base: clouds have a floor, and without it they look like popcorn.
    addOval(
        androidx.compose.ui.geometry.Rect(
            offset = Offset(x + w * 0.05f, y + h * 0.46f),
            size = Size(w * 0.9f, h * 0.62f),
        )
    )
}

private fun DrawScope.clouds(
    f: Field, clock: Float, slow: Float, d: Depth, tint: Color, density: Float,
) {
    val list = f.forDepth(f.clouds, d)
    list.forEachIndexed { i, p ->
        if (i.toFloat() / list.size > density.coerceAtMost(1f)) return@forEachIndexed
        val drift = clock * 0.10f * p.speed * d.speed
        val x = (wrap(p.x + drift) * 1.5f - 0.25f) * size.width +
            wind(slow, p.phase) * size.width * 0.015f
        val y = size.height * (0.02f + 0.22f * p.y)
        val w = size.width * (0.42f + 0.28f * p.scale) * d.scale * 0.75f
        val h = w * 0.42f
        val a = (0.34f * d.alpha * (0.75f + 0.25f * p.scale)).coerceAtMost(0.5f)
        drawPath(
            path = cloudPath(x, y, w, h),
            brush = Brush.verticalGradient(
                listOf(tint.copy(alpha = a), tint.copy(alpha = a * 0.45f)),
                startY = y, endY = y + h * 1.1f,
            ),
        )
    }
}

private fun DrawScope.rain(
    f: Field, clock: Float, slow: Float, d: Depth,
    length: Float, width: Float, alpha: Float,
) {
    val w = wind(slow, 0.2f)
    f.forDepth(f.drops, d).forEach { p ->
        // Monotonic: no wind term here, or the drops reverse direction.
        val fall = clock * 2.4f * p.speed * d.speed
        val y = wrap(p.y + fall) * (size.height * 1.2f) - size.height * 0.1f
        // Wind is a bounded lateral OFFSET, so gusting never rewinds the fall.
        val x = wrap(p.x + clock * 0.05f) * size.width + w * size.width * 0.02f
        val len = size.height * length * p.scale * d.scale
        val lean = len * (0.26f + p.tilt * 0.10f + w * 0.12f)
        val start = Offset(x, y)
        val end = Offset(x - lean, y + len)
        // Gradient along the streak + round cap: a raindrop is a smear of light, not a
        // rectangle.
        drawLine(
            brush = Brush.linearGradient(
                listOf(
                    Color(0x00CFE4F5),
                    Color(0xFFCFE4F5).copy(alpha = alpha * d.alpha),
                    Color(0x00CFE4F5),
                ),
                start = start, end = end,
            ),
            start = start, end = end,
            strokeWidth = width * d.scale * p.scale,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.snow(f: Field, clock: Float, slow: Float, d: Depth) {
    val w = wind(slow, 0.6f)
    f.forDepth(f.flakes, d).forEach { p ->
        val y = wrap(p.y + clock * 0.6f * p.speed * d.speed) * size.height
        val sway = (sin((clock + p.phase) * 3f * PI.toFloat()) + w * 0.6f) *
            size.width * 0.04f
        val x = wrap(p.x) * size.width + sway
        val r = (2.2f + 2.6f * p.scale) * d.scale
        val c = Offset(x, y)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    Color.White.copy(alpha = 0.9f * d.alpha),
                    Color.White.copy(alpha = 0.35f * d.alpha),
                    Color.White.copy(alpha = 0f),
                ),
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
    }
}

/**
 * An edgeless horizontal haze sheet.
 *
 * This is the replacement for the radial-gradient ovals that fog and dust used to be built
 * from. The problem with an oval is not softness, it is *silhouette*: a blurred circle is
 * still unmistakably a circle, and three of them drifting past reads as bubbles rather than
 * as air. A band has no silhouette to read —
 *
 *  - it spans the full width, so no left or right edge is ever on screen;
 *  - top and bottom fade out through a vertical gradient, so there is no horizontal edge;
 *  - the sense of movement comes from a *mirrored, tiled* gradient whose stops slide
 *    sideways, which gives drifting internal texture with no boundary anywhere.
 */
private fun DrawScope.hazeBand(
    top: Float,
    height: Float,
    colour: Color,
    alpha: Float,
    drift: Float,
    tileWidth: Float,
    /**
     * Whether to add the drifting texture pass. Only true on blurred layers: the texture
     * rect is modulated along X but not along Y, so on a sharp canvas its top and bottom
     * show up as two faint horizontal seams across the screen. The blur erases them.
     */
    texture: Boolean,
) {
    if (alpha <= 0.001f || height <= 0f) return
    // Body: soft top and bottom, densest slightly below the middle where haze settles.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                colour.copy(alpha = 0f),
                colour.copy(alpha = alpha * 0.85f),
                colour.copy(alpha = alpha),
                colour.copy(alpha = 0f),
            ),
            startY = top, endY = top + height,
        ),
        topLeft = Offset(0f, top),
        size = Size(size.width, height),
    )
    if (!texture) return
    // Texture: the same band again, thinner and modulated along X by a mirrored tile that
    // slides. Mirroring is what removes the seam a repeating tile would show.
    val phase = drift * tileWidth
    drawRect(
        brush = Brush.linearGradient(
            listOf(
                colour.copy(alpha = 0f),
                colour.copy(alpha = alpha * 0.55f),
                colour.copy(alpha = 0f),
            ),
            start = Offset(phase, 0f),
            end = Offset(phase + tileWidth, 0f),
            tileMode = TileMode.Mirror,
        ),
        topLeft = Offset(0f, top + height * 0.18f),
        size = Size(size.width, height * 0.64f),
    )
}

/**
 * Fog: white haze, nothing else.
 *
 * No ovals, no particles, no blue-grey murk — five slow white bands at different heights
 * and speeds, over a near-white wash. Depth does the rest: the far bands are wide and
 * heavily blurred, the near band is thin and sharp enough to feel like it is passing in
 * front of the companion.
 */
private fun DrawScope.fog(clock: Float, slow: Float, d: Depth, tod: TimeOfDay) {
    // Night fog is dimmer but still white — a grey fog looks like the screen is broken.
    val colour = when (tod) {
        TimeOfDay.NIGHT -> Color(0xFFDDE5EE)
        TimeOfDay.DAWN, TimeOfDay.DUSK -> Color(0xFFF7F1EC)
        TimeOfDay.DAY -> Color.White
    }
    val dim = if (tod == TimeOfDay.NIGHT) 0.62f else 1f
    val gust = wind(slow, 0.8f)

    // Overall milkiness. Fog's defining feature is that everything is washed out, and a
    // band-only version leaves gaps of clear sky between the bands.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                colour.copy(alpha = 0.10f * d.alpha * dim),
                colour.copy(alpha = 0.20f * d.alpha * dim),
                colour.copy(alpha = 0.13f * d.alpha * dim),
            ),
        ),
    )

    repeat(5) { i ->
        val t = i / 5f
        val speed = 0.035f + i * 0.022f
        val drift = wrap(clock * speed * d.speed + i * 0.21f) + gust * 0.03f
        val top = size.height * (-0.05f + 0.235f * i)
        val height = size.height * (0.34f - 0.03f * i) * d.scale
        val alpha = (0.20f - 0.022f * i) * d.alpha * dim
        hazeBand(
            top = top,
            height = height,
            colour = colour,
            alpha = alpha,
            drift = drift,
            tileWidth = size.width * (0.9f + 0.35f * t),
            texture = d != Depth.NEAR,
        )
    }
}

/**
 * Dust: low tan sheets plus fine grit blowing sideways.
 *
 * The big radial ovals are gone for the same reason as in [fog] — they read as brown
 * circles crossing the screen. The wall of dust is now bands weighted toward the bottom of
 * the frame (that is where a haboob actually sits), and the motes are short horizontal
 * streaks rather than dots, because grit moving that fast smears.
 */
private fun DrawScope.dust(f: Field, clock: Float, slow: Float, d: Depth) {
    val gust = wind(slow, 0.35f)
    val tan = Color(0xFFD2A75C)

    // Sheets: dense and low, thinning upward.
    repeat(4) { i ->
        val drift = wrap(clock * (0.10f + i * 0.05f) * d.speed + i * 0.27f) + gust * 0.05f
        val top = size.height * (0.02f + 0.235f * i)
        val height = size.height * (0.30f + 0.06f * i) * d.scale
        val alpha = (0.10f + 0.045f * i) * d.alpha
        hazeBand(
            top = top,
            height = height,
            colour = tan,
            alpha = alpha,
            drift = drift,
            tileWidth = size.width * (0.55f + 0.20f * i),
            texture = d != Depth.NEAR,
        )
    }

    // Grit.
    f.forDepth(f.motes, d).forEach { p ->
        val x = wrap(p.x + clock * 1.5f * p.speed * d.speed) * size.width +
            gust * size.width * 0.015f
        val y = (p.y + 0.02f * sin((clock + p.phase) * 2f * PI.toFloat())) * size.height
        // Streak, not a dot: length tracks speed so the fast near grains smear most.
        val len = size.width * (0.012f + 0.030f * p.speed) * d.scale
        val start = Offset(x, y)
        val end = Offset(x + len, y + len * p.tilt * 0.18f)
        drawLine(
            brush = Brush.linearGradient(
                listOf(
                    Color(0x00E8CB96),
                    Color(0xFFE8CB96).copy(alpha = 0.55f * d.alpha),
                    Color(0x00E8CB96),
                ),
                start = start, end = end,
            ),
            start = start, end = end,
            strokeWidth = (1.1f + 1.4f * p.scale) * d.scale,
            cap = StrokeCap.Round,
        )
    }
}

/** Branching bolt, drawn only while a flash is active. */
private fun DrawScope.bolt(slow: Float) {
    val intensity = flash(slow)
    if (intensity < 0.35f) return
    val originX = size.width * 0.62f
    val path = Path().apply {
        moveTo(originX, size.height * 0.10f)
        lineTo(originX - size.width * 0.05f, size.height * 0.24f)
        lineTo(originX + size.width * 0.03f, size.height * 0.26f)
        lineTo(originX - size.width * 0.07f, size.height * 0.44f)
        lineTo(originX + size.width * 0.01f, size.height * 0.42f)
        lineTo(originX - size.width * 0.02f, size.height * 0.58f)
    }
    // Glow pass then core: a bolt without a glow looks like a scribble.
    drawPath(path, color = Color(0xFFBFA8FF).copy(alpha = 0.35f * intensity), style =
        androidx.compose.ui.graphics.drawscope.Stroke(width = 16f, cap = StrokeCap.Round))
    drawPath(path, color = Color.White.copy(alpha = 0.9f * intensity), style =
        androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round))
}

private fun DrawScope.drawFlash(slow: Float) {
    val i = flash(slow)
    if (i <= 0.01f) return
    // Brightest at the top, where the cloud is: a uniform white wash looks like a bug.
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.30f * i),
                Color.White.copy(alpha = 0.04f * i),
            ),
        ),
    )
}

/** Two strikes per slow cycle with fast decay, at irregular points so it is not rhythmic. */
private fun flash(slow: Float): Float {
    fun at(t: Float, w: Float): Float {
        val d = kotlin.math.abs(slow - t)
        return if (d > w) 0f else (1f - d / w).let { it * it }
    }
    return (at(0.17f, 0.030f) + at(0.21f, 0.016f) + at(0.68f, 0.026f)).coerceAtMost(1f)
}

/**
 * Strength for the scene when it is drawn IN FRONT of the companion.
 *
 * Per-condition because they are not equally opaque: fog and dust cover the whole frame by
 * definition, so they need holding back further than rain, which is mostly gaps.
 */
fun overlayIntensity(condition: WeatherCondition): Float = when (condition) {
    WeatherCondition.FOG, WeatherCondition.DUST -> 0.45f
    WeatherCondition.SNOW, WeatherCondition.THUNDERSTORM -> 0.60f
    else -> 0.68f
}

private const val CYCLE_MS = 14_000
private const val SLOW_CYCLE_MS = 11_000
private const val TIME_OF_DAY_POLL_MS = 10 * 60 * 1000L
private const val MOON_POLL_MS = 60 * 60 * 1000L
private val FAR_BLUR = 26.dp
private val MID_BLUR = 10.dp
