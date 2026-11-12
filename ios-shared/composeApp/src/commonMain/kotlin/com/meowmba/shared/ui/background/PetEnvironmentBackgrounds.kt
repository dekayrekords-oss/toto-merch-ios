package com.meowmba.shared.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** Shared anchor for the interactive overlay. Scenery never paints its movable props. */
const val PET_ENVIRONMENT_TABLE_Y = .78f

/**
 * Original vector scenery, shared by every avatar. One caller-owned SceneClock drives
 * ambient details; this painter creates no timer, images, random state or network work.
 * Loops have small fixed bounds. Center space and the common tabletop remain stationary,
 * so contact physics does not need to chase a decorative animation.
 */
class PetEnvironmentBackgroundPainter {
    private val path = Path()
    private val atmosphere = PetEnvironmentAtmosphere()
    private val spaceScenery = SpaceCartoonHabitatPainter()

    fun draw(scope: DrawScope, animation: BackgroundAnimation, clock: Float, slow: Float) = with(scope) {
        if (size.width <= 0 || size.height <= 0) return@with
        val t = phase(clock)
        val s = phase(slow)
        val material = atmosphere.material(animation)
        drawRect(material.wall)
        atmosphere.drawLight(this, animation, s)
        when (animation) {
            BackgroundAnimation.CARTOON_PORTAL_ARCADE, BackgroundAnimation.CARTOON_MOON_GARDEN,
            BackgroundAnimation.CARTOON_ALIEN_AQUARIUM, BackgroundAnimation.CARTOON_METEOR_GARAGE ->
                spaceScenery.draw(this, animation, t, s)
            BackgroundAnimation.KITCHEN -> {
                window(.08f, .13f, .26f, .29f, Color(0xFF70958D), t)
                repeat(5) { i -> line(.65f + i * .06f, .16f, .65f + i * .06f, .32f, 0xFF80938B, .004f) }
                rect(.62f, .13f, .33f, .016f, 0xFFB8A288)
                repeat(3) { i -> oval(.64f + i * .10f, .29f, .055f, .043f, 0xFF748781) }
                plant(.13f, .66f, .20f, s)
                // Steam belongs to a fixed kettle at the edge, separate from the water prop.
                oval(.83f, .68f, .10f, .075f, 0xFF96ADA4)
                line(.925f, .715f, .95f, .69f, 0xFF96ADA4, .012f)
                steam(.875f, .665f, t)
            }
            BackgroundAnimation.LIBRARY -> {
                repeat(3) { row ->
                    val y = .16f + row * .17f
                    repeat(7) { i -> rect(.015f + i * .025f, y, .018f, .11f - (i % 3) * .009f, if (i % 2 == 0) 0xFF706378 else 0xFF8C7661) }
                    rect(0f, y + .12f, .20f, .018f, 0xFF49362D)
                    repeat(5) { i -> rect(.85f + i * .03f, y, .022f, .105f, if (i % 2 == 0) 0xFF667C75 else 0xFF8F6265) }
                    rect(.83f, y + .12f, .17f, .018f, 0xFF49362D)
                }
                oval(.08f, .045f, .84f, .075f, 0xFF494052)
                candle(.87f, .70f, t)
            }
            BackgroundAnimation.GREENHOUSE -> {
                repeat(5) { i -> line(i * .25f, 0f, i * .25f, .74f, 0xFF517268, .007f) }
                line(0f, .13f, .5f, .015f, 0xFF6C8C7C, .009f)
                line(.5f, .015f, 1f, .13f, 0xFF6C8C7C, .009f)
                line(0f, .28f, 1f, .28f, 0xFF517268, .006f)
                plant(.11f, .72f, .37f, s); plant(.87f, .72f, .30f, s + .2f)
                repeat(8) { i ->
                    val x = .02f + i * .135f
                    oval(x, .055f + .012f * sin((t + i * .13f) * TAU), .009f, .016f, 0xFF9CBFB1)
                }
            }
            BackgroundAnimation.WORKSHOP -> {
                rect(.03f, .15f, .20f, .40f, 0xFF29313A)
                repeat(4) { x -> repeat(6) { y -> dot(.055f + x * .047f, .18f + y * .059f, .003f, 0xFF657381) } }
                line(.10f, .25f, .10f, .40f, 0xFFAE9474, .014f)
                rect(.065f, .235f, .08f, .025f, 0xFF81919B)
                gear(.87f, .23f, .085f, t, 0xFFAF9670)
                gear(.94f, .36f, .055f, -t, 0xFF738995)
                rect(.81f, .68f, .15f, .07f, 0xFF57697A)
                repeat(4) { i -> dot(.835f + i * .032f, .708f, .007f, if (sin((t + i * .2f) * TAU) > 0f) 0xFFB5C89B else 0xFF5C7167) }
            }
            BackgroundAnimation.POND -> {
                oval(.77f, .12f, .13f, .085f, 0xFFA8B8B3)
                rect(0f, .60f, 1f, .18f, 0xFF244958)
                repeat(7) { i ->
                    val x = .07f + i * .145f
                    line(x, .63f + i % 3 * .047f, x + .065f + .012f * sin((t + i * .14f) * TAU), .63f + i % 3 * .047f, 0xFF63828A, .002f)
                }
                reeds(.08f, s); reeds(.91f, s + .3f)
                oval(.16f, .706f, .09f, .021f, 0xFF527D66)
                petals(.205f, .697f, .016f, 0xFFC08D9C, s)
                fireflies(t)
            }
            BackgroundAnimation.ORCHARD -> {
                tree(.06f, s); tree(.92f, s + .25f)
                oval(.37f, .09f, .15f, .052f, 0xFFAC9B73)
                repeat(5) { i ->
                    val x = .07f + i * .21f
                    val y = .15f + .48f * phase(t + i * .19f)
                    oval(x + .015f * sin((s + i) * TAU), y, .016f, .008f, 0xFFC1A073)
                }
                rect(.01f, .706f, .17f, .05f, 0xFF79563D)
                repeat(4) { i -> dot(.035f + i * .035f, .70f, .015f, 0xFFBC8D64) }
            }
            BackgroundAnimation.CAFE -> {
                window(.035f, .12f, .24f, .43f, Color(0xFF425A70), t)
                repeat(11) { i ->
                    val x = .055f + (i % 5) * .042f
                    val y = .15f + phase(t + i * .137f) * .35f
                    line(x, y, x - .006f, y + .027f, 0xFF8BA5B2, .002f)
                }
                line(.80f, 0f, .80f, .18f, 0xFFB3A087, .004f)
                oval(.72f, .17f, .16f, .066f, 0xFFA58668)
                rect(.79f, .34f, .17f, .21f, 0xFF302F32)
                repeat(4) { i -> line(.81f, .38f + i * .035f, .92f - i % 2 * .025f, .38f + i * .035f, 0xFF958A80, .003f) }
                candle(.87f, .70f, t)
            }
            BackgroundAnimation.OBSERVATORY -> {
                repeat(28) { i ->
                    val x = ((i * 37 % 97) + 1) / 100f
                    val y = .055f + (i * 17 % 37) / 100f
                    if (y < .26f || x < .22f || x > .78f)
                        dot(x, y, .0015f + .001f * (1f + sin((t + i * .13f) * TAU)), 0xFFB5C4DC)
                }
                oval(.30f, .075f, .15f, .07f, 0xFF5B6694)
                line(.76f, .70f, .84f, .58f, 0xFF8193AF, .008f)
                line(.92f, .70f, .84f, .58f, 0xFF8193AF, .008f)
                line(.78f, .60f, .92f, .49f, 0xFFB1A795, .038f)
                line(.90f, .50f, .93f, .48f, 0xFF7385A5, .047f)
                gear(.11f, .20f, .075f, s, 0xFF596B89)
            }
            BackgroundAnimation.BEACH -> {
                oval(.75f, .13f, .13f, .075f, 0xFFE0BD83)
                rect(0f, .47f, 1f, .31f, 0xFF2B6470)
                repeat(6) { i ->
                    val y = .51f + i * .04f
                    wave(y, .005f, t + i * .13f, 0xFF6C9C9E)
                }
                line(.09f, .71f, .04f, .15f, 0xFF766F50, .022f)
                repeat(5) { i -> line(.04f, .15f, -.11f + i * .065f, .20f + .065f * sin(i.toFloat()) + .008f * sin(s * TAU), 0xFF537D61, .023f) }
                oval(.81f, .71f, .095f, .033f, 0xFFAD9C83)
            }
            BackgroundAnimation.CARTOON_NOOK, BackgroundAnimation.CARTOON_GALLEY -> {
                // Quiet loading fallback matches the new art, with the same empty play plane.
                oval(.21f, .06f, .58f, .52f, 0xFF243C44)
                oval(.235f, .085f, .53f, .47f,
                    if (animation == BackgroundAnimation.CARTOON_NOOK) 0xFF84D2DF else 0xFF294E79)
                oval(.57f, .15f, .09f, .07f, 0xFFB4DECC)
                if (animation == BackgroundAnimation.CARTOON_NOOK) {
                    repeat(4) { i -> rect(.02f + i * .035f, .34f, .025f, .15f, 0xFFB99752) }
                    rect(.01f, .49f, .16f, .015f, 0xFF775434)
                    oval(.86f, .49f, .19f, .22f, 0xFFD78846)
                } else {
                    rect(.86f, .22f, .14f, .49f, 0xFF6FB4AC)
                    line(.88f, .40f, .88f, .49f, 0xFF243C44, .007f)
                }
            }
            BackgroundAnimation.CARTOON_SNACK_LAB -> {
                // Complete, sparse loading art: the crowned banana lives at the room edge.
                line(0f, .72f, 1f, .72f, CARTOON_INK, .006f)
                cartoonRect(.015f, .56f, .20f, .16f, 0xFFE6AC7C)
                cartoonRect(.035f, .25f, .145f, .27f, 0xFFD7F0DC)
                cartoonRect(.03f, .225f, .155f, .035f, 0xFF88BAB2)
                path.reset()
                path.moveTo(.08f * size.width, .31f * size.height)
                path.cubicTo(.13f * size.width, .43f * size.height, .16f * size.width, .39f * size.height,
                    .15f * size.width, .32f * size.height)
                drawPath(path, Color(CARTOON_INK), style = Stroke(.040f * size.minDimension, cap = StrokeCap.Round))
                drawPath(path, Color(0xFFF5D76E), style = Stroke(.027f * size.minDimension, cap = StrokeCap.Round))
                path.reset()
                path.moveTo(.06f * size.width, .22f * size.height)
                path.lineTo(.048f * size.width, .16f * size.height)
                path.lineTo(.085f * size.width, .188f * size.height)
                path.lineTo(.11f * size.width, .145f * size.height)
                path.lineTo(.134f * size.width, .188f * size.height)
                path.lineTo(.17f * size.width, .16f * size.height)
                path.lineTo(.157f * size.width, .22f * size.height)
                path.close()
                drawPath(path, Color(0xFFE9B855))
                drawPath(path, Color(CARTOON_INK), style = Stroke(.005f * size.minDimension))
                cartoonRect(.82f, .31f, .18f, .41f, 0xFF78ADA6)
                cartoonRect(.845f, .35f, .13f, .15f, 0xFFD5EAC8)
                line(.85f, .56f, .96f, .56f, CARTOON_INK, .006f)
                line(.84f, .61f, .84f, .66f, CARTOON_INK, .008f)
            }
            BackgroundAnimation.CARTOON_LAUNDROMAT -> {
                // Washer portals frame a quiet wall; the lone sock has earned a trophy.
                line(0f, .72f, 1f, .72f, CARTOON_INK, .006f)
                repeat(2) { i ->
                    val x = .015f + i * .80f
                    cartoonRect(x, .425f, .17f, .295f, if (i == 0) 0xFF96C9B8 else 0xFFC4A5CF)
                    cartoonOval(x + .024f, .51f, .122f, .155f, 0xFFECDEBC)
                    cartoonOval(x + .041f, .533f, .087f, .107f, 0xFF658B9B)
                    dot(x + .035f, .465f, .008f, CARTOON_INK)
                    line(x + .08f, .465f, x + .13f, .465f, CARTOON_INK, .005f)
                }
                cartoonRect(.82f, .295f, .13f, .027f, 0xFFE8BB67)
                line(.885f, .295f, .885f, .258f, CARTOON_INK, .009f)
                path.reset()
                path.moveTo(.83f * size.width, .135f * size.height)
                path.lineTo(.90f * size.width, .135f * size.height)
                path.lineTo(.90f * size.width, .21f * size.height)
                path.cubicTo(.975f * size.width, .21f * size.height, .97f * size.width, .26f * size.height,
                    .89f * size.width, .26f * size.height)
                path.lineTo(.83f * size.width, .232f * size.height)
                path.close()
                drawPath(path, Color(0xFFF3D378))
                drawPath(path, Color(CARTOON_INK), style = Stroke(.005f * size.minDimension))
                line(.834f, .157f, .896f, .157f, CARTOON_INK, .004f)
            }
            BackgroundAnimation.CARTOON_DINER -> {
                line(0f, .71f, 1f, .71f, CARTOON_INK, .006f)
                repeat(2) { i ->
                    val x = .09f + i * .76f
                    line(x + .035f, 0f, x + .035f, .15f, CARTOON_INK, .005f)
                    cartoonOval(x - .015f, .155f, .105f, .066f, 0xFFABCCC0)
                    cartoonOval(x - .035f, .197f, .15f, .043f, 0xFFE9AC6F)
                }
                cartoonRect(.795f, .69f, .205f, .035f, 0xFFB96558)
                repeat(3) { i -> cartoonOval(.835f, .66f - i * .028f, .13f, .033f, 0xFFE1B676) }
                // A boot flowerpot is deliberately separate from the movable tabletop props.
                path.reset()
                path.moveTo(.025f * size.width, .55f * size.height)
                path.lineTo(.105f * size.width, .55f * size.height)
                path.lineTo(.105f * size.width, .638f * size.height)
                path.cubicTo(.19f * size.width, .638f * size.height, .20f * size.width, .71f * size.height,
                    .12f * size.width, .71f * size.height)
                path.lineTo(.025f * size.width, .71f * size.height)
                path.close()
                drawPath(path, Color(0xFFC57562))
                drawPath(path, Color(CARTOON_INK), style = Stroke(.006f * size.minDimension))
                line(.065f, .55f, .065f, .435f, CARTOON_INK, .006f)
                cartoonOval(.025f, .424f, .08f, .052f, 0xFFE9C969)
                dot(.065f, .45f, .012f, CARTOON_INK)
                line(.026f, .685f, .13f, .685f, CARTOON_INK, .005f)
            }
            else -> {
                window(.70f, .13f, .23f, .28f, Color(0xFF404C76), t)
                oval(.835f, .17f, .045f, .030f, 0xFFBEB5BD)
                rect(.03f, .66f, .22f, .10f, 0xFF7D718A)
                oval(.025f, .64f, .14f, .035f, 0xFFA99AAC)
                line(.12f, .60f, .12f, .49f, 0xFFA39287, .006f)
                oval(.06f, .45f, .12f, .064f, 0xFFC0A185)
                repeat(9) { i ->
                    val x = .07f + i * .108f
                    val y = .075f + .035f * sin(i / 8f * PI.toFloat())
                    if (i > 0) line(x - .108f, .075f + .035f * sin((i - 1) / 8f * PI.toFloat()), x, y, 0xFF6E6179, .002f)
                    dot(x, y, .006f + .001f * sin((t + i * .12f) * TAU), 0xFFD0AE86)
                }
            }
        }
        atmosphere.drawDetails(this, animation, t, s)
        // Static material shading preserves the exact contact plane while giving props
        // a substantial surface. Detail motion never crosses into this stage.
        drawRect(material.table(size.height), p(0f, PET_ENVIRONMENT_TABLE_Y), Size(size.width, size.height * .22f))
        line(0f, PET_ENVIRONMENT_TABLE_Y, 1f, PET_ENVIRONMENT_TABLE_Y, 0x44F4DBC1, .002f)
        rect(0f, PET_ENVIRONMENT_TABLE_Y + .012f, 1f, .013f, 0x55301F21)
        repeat(5) { i -> line(0f, .84f + i * .036f, 1f, .84f + i * .036f, 0x22412B24, .0015f) }
        line(.12f, .80f, .08f, 1f, 0x22412B24, .002f)
        line(.88f, .80f, .92f, 1f, 0x22412B24, .002f)
    }

    private fun DrawScope.rect(x: Float, y: Float, w: Float, h: Float, color: Long) = drawRect(Color(color), p(x, y), Size(w * size.width, h * size.height))
    private fun DrawScope.oval(x: Float, y: Float, w: Float, h: Float, color: Long) = drawOval(Color(color), p(x, y), Size(w * size.width, h * size.height))
    private fun DrawScope.dot(x: Float, y: Float, r: Float, color: Long) = drawCircle(Color(color), r * size.minDimension, p(x, y))
    private fun DrawScope.line(x: Float, y: Float, xx: Float, yy: Float, color: Long, width: Float) = drawLine(Color(color), p(x, y), p(xx, yy), width * size.minDimension, StrokeCap.Round)
    private fun DrawScope.p(x: Float, y: Float) = Offset(x * size.width, y * size.height)

    private fun DrawScope.cartoonRect(x: Float, y: Float, w: Float, h: Float, color: Long) {
        rect(x, y, w, h, color)
        drawRect(Color(CARTOON_INK), p(x, y), Size(w * size.width, h * size.height), style = Stroke(.005f * size.minDimension))
    }

    private fun DrawScope.cartoonOval(x: Float, y: Float, w: Float, h: Float, color: Long) {
        oval(x, y, w, h, color)
        drawOval(Color(CARTOON_INK), p(x, y), Size(w * size.width, h * size.height), style = Stroke(.005f * size.minDimension))
    }

    private fun DrawScope.window(x: Float, y: Float, w: Float, h: Float, sky: Color, t: Float) {
        rect(x - .012f, y - .010f, w + .024f, h + .02f, 0xFF897F72)
        drawRect(sky, p(x, y), Size(w * size.width, h * size.height))
        oval(x + .02f + .015f * sin(t * TAU), y + .07f, w * .55f, h * .085f, 0x448DA8A8)
        line(x + w / 2, y, x + w / 2, y + h, 0xFF897F72, .01f)
        line(x, y + h * .52f, x + w, y + h * .52f, 0xFF897F72, .008f)
        rect(x - .023f, y + h, w + .046f, .014f, 0xFFB4A28A)
    }

    private fun DrawScope.plant(x: Float, y: Float, h: Float, s: Float) {
        val sway = .012f * sin(s * TAU)
        line(x, y, x + sway, y - h, 0xFF789475, .006f)
        repeat(5) { i ->
            val yy = y - h * (.2f + i * .16f)
            val side = if (i % 2 == 0) -1 else 1
            oval(x + sway * i / 5 + if (side < 0) -.065f else 0f, yy - .035f, .065f, .034f, if (i % 2 == 0) 0xFF597E60 else 0xFF749369)
        }
        rect(x - .043f, y - .01f, .086f, .06f, 0xFF9A7763)
        rect(x - .05f, y - .012f, .10f, .013f, 0xFFB39479)
    }

    private fun DrawScope.candle(x: Float, y: Float, t: Float) {
        oval(x - .035f, y + .039f, .07f, .012f, 0xFFB39879)
        rect(x - .016f, y - .013f, .032f, .055f, 0xFFCFB28D)
        oval(x - .009f, y - .038f + .002f * sin(t * TAU * 3), .018f, .028f, 0xFFE4BE77)
        // A candle flickers rather than continuously smoking like a boiling kettle.
        dot(x + .002f * sin(t * TAU * 3), y - .024f, .004f, 0xFFFFE4A4)
    }

    private fun DrawScope.steam(x: Float, y: Float, t: Float) {
        repeat(3) { i ->
            val u = phase(t + i / 3f)
            drawCircle(Color(0xFFCCD0BE).copy(alpha = (1 - u) * .22f), (.009f + u * .013f) * size.minDimension, p(x + sin(u * TAU) * .013f, y - u * .09f))
        }
    }

    private fun DrawScope.gear(x: Float, y: Float, r: Float, t: Float, color: Long) {
        drawCircle(Color(color), r * size.minDimension, p(x, y), style = Stroke(.012f * size.minDimension))
        repeat(12) { i ->
            val a = (t + i / 12f) * TAU
            val xx = kotlin.math.cos(a); val yy = sin(a)
            val c = p(x, y); val rr = r * size.minDimension
            drawLine(Color(color), c + Offset(xx, yy) * rr * .78f, c + Offset(xx, yy) * rr * 1.15f, .013f * size.minDimension)
        }
        dot(x, y, .012f, color)
    }

    private fun DrawScope.reeds(x: Float, s: Float) {
        repeat(5) { i ->
            val xx = x + (i - 2) * .016f
            val top = .54f + i % 3 * .032f
            val sway = .012f * sin((s + i * .13f) * TAU)
            line(xx, .77f, xx + sway, top, 0xFF60846D, .004f)
            line(xx + sway, top, xx + sway, top - .035f, 0xFF9C987A, .009f)
        }
    }

    private fun DrawScope.petals(x: Float, y: Float, r: Float, color: Long, s: Float) {
        repeat(5) { i ->
            val a = i / 5f * TAU
            dot(x + kotlin.math.cos(a) * r, y + sin(a) * r * .5f + sin(s * TAU) * .002f, r * .8f, color)
        }
        dot(x, y, r * .45f, 0xFFD2B47A)
    }

    private fun DrawScope.tree(x: Float, s: Float) {
        line(x, .77f, x, .18f, 0xFF786D4C, .037f)
        repeat(4) { i ->
            val yy = .17f + i * .09f
            oval(x - .15f + .007f * sin((s + i * .1f) * TAU), yy, .30f, .13f, if (i % 2 == 0) 0xFF536C46 else 0xFF425F40)
            dot(x + if (i % 2 == 0) .08f else -.035f, yy + .07f, .018f, 0xFFBE936B)
        }
    }

    private fun DrawScope.fireflies(t: Float) {
        repeat(8) { i ->
            val x = if (i % 2 == 0) .1f else .88f
            dot(x + .032f * sin((t + i * .17f) * TAU), .32f + i * .042f, .0025f + .001f * sin((t + i * .1f) * TAU), 0xFFBCCB8C)
        }
    }

    private fun DrawScope.wave(y: Float, height: Float, t: Float, color: Long) {
        path.reset()
        repeat(33) { i ->
            val x = i / 32f
            val yy = y + sin((x * 2 + t) * TAU) * height
            if (i == 0) path.moveTo(x * size.width, yy * size.height) else path.lineTo(x * size.width, yy * size.height)
        }
        drawPath(path, Color(color), style = Stroke(.002f * size.minDimension))
    }

    private fun phase(value: Float): Float = if (value.isFinite()) value - floor(value) else 0f
    private companion object {
        const val TAU = (PI * 2).toFloat()
        const val CARTOON_INK = 0xFF293B42L
    }
}
