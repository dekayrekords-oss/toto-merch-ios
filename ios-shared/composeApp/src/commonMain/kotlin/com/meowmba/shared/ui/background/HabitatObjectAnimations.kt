package com.meowmba.shared.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Source-art coordinates, not screen coordinates: windows stay attached through crop/fold.
 * Only these small details move. No image transforms, full-room tint, new clocks or sensors.
 * Paths/materials are retained; every loop closes on the shared 22-second scene phase.
 */
internal class HabitatObjectAnimations {
    private enum class Kind { STARS, BIRDS, RAIN, FIREFLIES, LEAVES, WATER, FLAME, STEAM,
        BUBBLES, FISH, PORTAL, GAME, WASH, METEORS }
    private class Detail(val kind: Kind, val rect: Rect, ellipse: Boolean = false, points: List<Offset>? = null) {
        val clip = Path().apply {
            when {
                points != null -> { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                ellipse -> addOval(rect)
                else -> addRect(rect)
            }
        }
    }
    private fun d(kind: Kind, x: Float, y: Float, w: Float, h: Float, oval: Boolean = false) =
        Detail(kind, Rect(x, y, x + w, y + h), oval)
    private val rooms = mapOf(
        BackgroundAnimation.KITCHEN to listOf(d(Kind.BIRDS, .39f,.18f,.26f,.12f), d(Kind.FIREFLIES,.36f,.30f,.28f,.07f)),
        BackgroundAnimation.LIBRARY to listOf(d(Kind.STARS,.50f,.10f,.20f,.20f), d(Kind.FLAME,.722f,.359f,.026f,.048f)),
        BackgroundAnimation.GREENHOUSE to listOf(d(Kind.FIREFLIES,.43f,.27f,.18f,.13f), d(Kind.FIREFLIES,.24f,.34f,.13f,.16f)),
        BackgroundAnimation.WORKSHOP to listOf(d(Kind.BIRDS,.49f,.17f,.16f,.16f), d(Kind.FIREFLIES,.42f,.36f,.25f,.10f)),
        BackgroundAnimation.POND to listOf(d(Kind.WATER,.41f,.44f,.25f,.16f), d(Kind.FIREFLIES,.35f,.39f,.31f,.13f)),
        BackgroundAnimation.ORCHARD to listOf(d(Kind.LEAVES,.29f,.21f,.15f,.22f), d(Kind.LEAVES,.62f,.20f,.15f,.24f)),
        BackgroundAnimation.CAFE to listOf(d(Kind.RAIN,.28f,.10f,.13f,.24f), d(Kind.RAIN,.43f,.10f,.07f,.24f)),
        BackgroundAnimation.OBSERVATORY to listOf(d(Kind.STARS,.24f,.015f,.41f,.115f), d(Kind.STARS,.41f,.235f,.15f,.075f)),
        BackgroundAnimation.BEACH to listOf(d(Kind.WATER,.45f,.28f,.30f,.12f), d(Kind.BIRDS,.45f,.18f,.29f,.09f)),
        BackgroundAnimation.BEDROOM to listOf(d(Kind.STARS,.335f,.12f,.145f,.13f), d(Kind.FLAME,.70f,.273f,.026f,.044f)),
        BackgroundAnimation.CARTOON_NOOK to listOf(d(Kind.BIRDS,.32f,.16f,.41f,.22f), d(Kind.STARS,.62f,.15f,.10f,.12f)),
        BackgroundAnimation.CARTOON_GALLEY to listOf(d(Kind.STARS,.365f,.15f,.275f,.30f,true)),
        BackgroundAnimation.CARTOON_SNACK_LAB to listOf(d(Kind.BUBBLES,.092f,.453f,.070f,.040f,true),
            d(Kind.STEAM,.17f,.17f,.055f,.09f), d(Kind.WATER,.567f,.349f,.026f,.098f)),
        BackgroundAnimation.CARTOON_LAUNDROMAT to listOf(d(Kind.WASH,.030f,.105f,.093f,.18f,true),
            d(Kind.WASH,.030f,.431f,.093f,.18f,true), d(Kind.WASH,.884f,.108f,.086f,.18f,true),
            d(Kind.WASH,.884f,.443f,.086f,.18f,true), d(Kind.STARS,.427f,.075f,.14f,.12f)),
        BackgroundAnimation.CARTOON_DINER to listOf(d(Kind.BIRDS,.325f,.19f,.45f,.13f),
            d(Kind.STEAM,.137f,.16f,.043f,.07f)),
        BackgroundAnimation.CARTOON_PORTAL_ARCADE to listOf(d(Kind.STARS,.288f,.269f,.362f,.12f),
            d(Kind.STARS,.368f,.001f,.252f,.087f,true),
            Detail(Kind.GAME, Rect(.046f,.243f,.184f,.428f), points = listOf(
                Offset(.046f,.243f),Offset(.156f,.265f),Offset(.184f,.428f),Offset(.064f,.425f))),
            d(Kind.PORTAL,.841f,.223f,.184f,.307f,true)),
        BackgroundAnimation.CARTOON_MOON_GARDEN to listOf(d(Kind.STARS,.33f,.017f,.35f,.31f),
            d(Kind.FIREFLIES,.62f,.33f,.15f,.24f), d(Kind.FIREFLIES,.24f,.38f,.18f,.21f)),
        BackgroundAnimation.CARTOON_ALIEN_AQUARIUM to listOf(d(Kind.FISH,.243f,.246f,.457f,.246f,true),
            d(Kind.BUBBLES,.173f,.195f,.065f,.29f), d(Kind.BUBBLES,.657f,.20f,.065f,.31f)),
        BackgroundAnimation.CARTOON_METEOR_GARAGE to listOf(d(Kind.METEORS,.31f,.15f,.43f,.47f),
            d(Kind.STARS,.28f,.06f,.42f,.12f)),
    )
    private val scratch = Path()
    private val glow = Brush.radialGradient(listOf(Color(0xBBFFF8CA), Color.Transparent), Offset.Zero, 1f)
    private val fishInk = Color(0xFF284865)
    private val fishBody = Brush.radialGradient(listOf(Color(0xFFFFCFCE), Color(0xFFE586AF), Color(0xFF815A99)), Offset(-.25f,-.2f), 1.2f)
    private val fishMint = Brush.radialGradient(listOf(Color(0xFFD8F8B6), Color(0xFF74C9B2), Color(0xFF35788E)), Offset(-.25f,-.2f), 1.2f)

    fun draw(scope: DrawScope, animation: BackgroundAnimation, placement: IllustratedRoomPlacement, phase: Float) = with(scope) {
        val details = rooms[animation] ?: return@with
        val t = if (phase.isFinite()) phase - floor(phase) else 0f
        // Match drawImage's integer placement exactly, including decoded-thumbnail rounding.
        translate(placement.x.roundToInt().toFloat(), placement.y.roundToInt().toFloat()) {
            scale(placement.width.roundToInt().coerceAtLeast(1).toFloat(),
                placement.height.roundToInt().coerceAtLeast(1).toFloat(), Offset.Zero) {
                details.forEachIndexed { index, detail ->
                    clipPath(detail.clip) { paint(detail, t, index) }
                }
            }
        }
    }

    private fun DrawScope.paint(d: Detail, t: Float, seed: Int) {
        val r = d.rect
        val a = (t + seed * .19f) * TAU
        fun at(x: Float, y: Float) = Offset(r.left + x * r.width, r.top + y * r.height)
        when (d.kind) {
            Kind.STARS -> repeat(5) { i ->
                val p = at(.12f + (i * .237f + seed * .13f) % .77f, .15f + (i * .31f) % .68f)
                star(p, .0016f + .0011f * wave(a * 2f + i), .12f + .48f * wave(a + i * 1.7f))
            }
            Kind.BIRDS -> repeat(2) { i ->
                val q = fract(t + i * .45f)
                val p = at(q, .38f + i * .23f + .09f * sin(a * 2f + i))
                val wing = .0015f + .0023f * wave(a * 8f + i)
                scratch.reset(); scratch.moveTo(p.x - .006f,p.y - wing)
                scratch.quadraticTo(p.x - .002f,p.y - .003f,p.x,p.y)
                scratch.quadraticTo(p.x + .002f,p.y - .003f,p.x + .006f,p.y - wing)
                drawPath(scratch, Color(0xFF8BABB7).copy(alpha = fade(q) * .65f), style = Stroke(.0009f, cap = StrokeCap.Round))
            }
            Kind.RAIN -> repeat(7) { i ->
                val q = fract(t * 3f + i * .137f)
                val p = at(.12f + (i * .219f) % .76f, q)
                drawLine(Color(0xFFDAEAF4).copy(alpha = fade(q) * .35f), p, p + Offset(-.0012f,.012f), .0012f, StrokeCap.Round)
            }
            Kind.FIREFLIES -> repeat(3) { i ->
                val p = at(.5f + .31f * sin(a + i * 2.1f), .48f + .28f * cos(a * 2f + i * 1.8f))
                val opacity = .25f + .5f * wave(a * 2f + i)
                translate(p.x,p.y) { scale(.006f,.004f,Offset.Zero) { drawCircle(glow,1f,Offset.Zero,alpha=opacity) } }
                drawCircle(Color(0xFFFFE9A9).copy(alpha = opacity),.0013f,p)
            }
            Kind.LEAVES -> repeat(2) { i ->
                val q = fract(t + i * .48f)
                val p = at(.50f + .22f * sin(q * TAU + i), q)
                rotate(30f + 40f * sin(q * TAU), p) {
                    drawOval(Color(0xFFD7AB58).copy(alpha = fade(q) * .70f), p - Offset(.003f,.006f), Size(.006f,.012f))
                    drawLine(Color(0xFF805E38).copy(alpha = fade(q) * .5f),p - Offset(0f,.005f),p + Offset(0f,.005f),.0006f)
                }
            }
            Kind.WATER -> repeat(5) { i ->
                val q = fract(t * 2f + i * .19f)
                val p = at(.17f + (i * .23f) % .66f, .14f + i * .16f)
                drawOval(Color(0xFFCEEFE5).copy(alpha = fade(q) * .33f),
                    p - Offset(.006f + q * .012f, .0012f + q * .001f),
                    Size(.012f + q * .024f,.0024f + q * .002f), style = Stroke(.00065f))
            }
            Kind.FLAME -> {
                val p = at(.5f + .05f * sin(a * 4f), .54f)
                translate(p.x,p.y) { scale(r.width*.50f,r.height*.48f,Offset.Zero) {
                    drawCircle(glow,1f,Offset.Zero,alpha=.20f+.10f*wave(a*3f))
                } }
                drawOval(Color(0xAAFFDB8B),p-Offset(.002f,.006f),Size(.004f,.009f+.002f*wave(a*4f)))
            }
            Kind.STEAM -> repeat(3) { i ->
                val q = fract(t * 2f + i / 3f)
                val p = at(.5f + .16f * sin(q * TAU + i), 1f - q)
                scratch.reset(); scratch.moveTo(p.x,p.y)
                scratch.cubicTo(p.x-.004f,p.y-.003f,p.x+.004f,p.y-.008f,p.x,p.y-.012f)
                drawPath(scratch,Color.White.copy(alpha=fade(q)*.22f),style=Stroke(.0015f,cap=StrokeCap.Round))
            }
            Kind.BUBBLES -> repeat(6) { i ->
                val q = fract(t * 2f + i / 6f)
                val p = at(.5f + .22f * sin(a + i * 2.4f), 1f - q)
                val radius = minOf(.0048f, r.width * .065f) * (.60f + i * .09f)
                val alpha = fade(q) * .68f
                drawCircle(Color(0xFFB6F1EA).copy(alpha=alpha*.13f), radius,p)
                drawCircle(Color(0xFFE0FFF6).copy(alpha=alpha),radius,p,style=Stroke(.00075f))
                drawCircle(Color.White.copy(alpha=alpha),radius*.20f,p+Offset(-radius*.3f,-radius*.35f))
            }
            Kind.FISH -> repeat(3) { i ->
                val q = a + i * 2.1f
                val p = at(.5f + .34f * sin(q), .22f + i * .25f + .07f * sin(q * 2f))
                // A smooth bank at each turn avoids a one-frame left/right sprite flip.
                val facing = cos(q)
                alienFish(p, (.017f + i * .004f), facing, q * 6f, i)
            }
            Kind.PORTAL, Kind.WASH -> {
                val center = r.center
                val portal = d.kind == Kind.PORTAL
                val tint = if (portal) Color(0xFFDCFFA5) else Color(0xFFB6ECFF)
                repeat(3) { i ->
                    val spin = t * 360f * if (portal) 1f else -1f
                    drawArc(tint.copy(alpha=.15f+i*.07f),spin+i*120f,28f,false,
                        Offset(r.left+r.width*.10f,r.top+r.height*.09f),Size(r.width*.8f,r.height*.82f),style=Stroke(.0018f,cap=StrokeCap.Round))
                    val q = a + i * TAU/3f
                    star(center+Offset(cos(q)*r.width*.36f,sin(q)*r.height*.39f),.0024f,.70f)
                }
            }
            Kind.GAME -> {
                // A tiny orbiting player and pellets live INSIDE the painted screen.
                val p = at(.45f+.19f*cos(a),.49f+.19f*sin(a))
                repeat(4) { i -> star(at(.23f+i*.14f,.79f),.0016f,.18f+.45f*wave(a+i*1.5f)) }
                drawArc(Color(0xFFCAEF94),20f+10f*wave(a*8f),300f,true,p-Offset(.006f,.006f),Size(.012f,.012f))
                drawCircle(Color(0xFF215566),.0012f,p+Offset(.001f,-.002f))
                drawOval(Color(0xFF8AF1EE).copy(alpha=.20f+.18f*wave(a)),at(.31f,.36f),Size(r.width*.30f,r.height*.22f),style=Stroke(.001f))
            }
            Kind.METEORS -> repeat(3) { i ->
                val q = fract(t + i*.32f)
                val p = at(.20f+i*.27f+.06f*sin(a+i),q)
                val radius = .006f+i*.002f
                rotate(q*160f,p) {
                    drawOval(Color(0xFF65669A).copy(alpha=fade(q)*.8f),p-Offset(radius,radius*.7f),Size(radius*2f,radius*1.4f))
                    drawOval(Color(0xFFA5A2CE).copy(alpha=fade(q)*.6f),p-Offset(radius*.8f,radius*.55f),Size(radius*.8f,radius*.5f))
                }
            }
        }
    }

    private fun DrawScope.alienFish(p: Offset, radius: Float, facing: Float, finPhase: Float, variant: Int) {
        translate(p.x,p.y) { scale(radius, radius, Offset.Zero) {
            // Bank through a narrow silhouette while preserving the eye and antenna.
            scale(facing,1f,Offset.Zero) {
                val wag = sin(finPhase)*.14f
                scratch.reset();scratch.moveTo(-.65f,0f);scratch.quadraticTo(-1.12f,-.25f,-1.30f,-.49f+wag)
                scratch.quadraticTo(-1.16f,0f,-1.30f,.49f+wag);scratch.quadraticTo(-1.07f,.21f,-.65f,0f)
                drawPath(scratch,Color(0xAA96D9D9));drawPath(scratch,fishInk.copy(alpha=.6f),style=Stroke(.035f))
                drawOval(if(variant==1)fishMint else fishBody,Offset(-.83f,-.47f),Size(1.66f,.94f))
                drawOval(fishInk.copy(alpha=.6f),Offset(-.83f,-.47f),Size(1.66f,.94f),style=Stroke(.045f))
                scratch.reset();scratch.moveTo(-.35f,-.35f);scratch.quadraticTo(-.18f,-.87f,.26f,-.47f)
                drawPath(scratch,Color(0xBBBFDEE9))
                drawLine(fishInk,Offset(.27f,-.3f),Offset(.40f,-.66f),.075f,StrokeCap.Round)
                drawCircle(Color(0xFFFFF7D9),.20f,Offset(.42f,-.68f))
                drawCircle(fishInk,.11f,Offset(.48f,-.67f));drawCircle(Color.White,.038f,Offset(.49f,-.71f))
                drawArc(fishInk,10f,90f,false,Offset(.35f,-.09f),Size(.26f,.23f),style=Stroke(.04f,cap=StrokeCap.Round))
                rotate(-15f+sin(finPhase)*18f,Offset(-.12f,.1f)) {
                    drawOval(Color(0xAAE0F6DB),Offset(-.33f,.06f),Size(.42f,.24f))
                }
                repeat(3) { i -> drawCircle(Color(0x99F7FFCB),.045f,Offset(-.51f+i*.20f,-.12f)) }
            }
        } }
    }

    private fun DrawScope.star(p: Offset, radius: Float, alpha: Float) {
        val c=Color(0xFFFFF4CA).copy(alpha=alpha)
        drawLine(c,p-Offset(radius,0f),p+Offset(radius,0f),.0008f,StrokeCap.Round)
        drawLine(c,p-Offset(0f,radius),p+Offset(0f,radius),.0008f,StrokeCap.Round)
    }
    private fun fract(x: Float) = x-floor(x)
    private fun fade(x: Float) = (sin(x*PI.toFloat()).coerceAtLeast(0f)).let { it*it }
    private fun wave(x: Float) = .5f+.5f*sin(x)
    private companion object { const val TAU = (PI*2).toFloat() }
}
