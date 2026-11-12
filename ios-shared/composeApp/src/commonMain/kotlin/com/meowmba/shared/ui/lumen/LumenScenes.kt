package com.meowmba.shared.ui.lumen

import com.meowmba.shared.companion.AnimFrame
import com.meowmba.shared.companion.CustomAnimationSpec
import com.meowmba.shared.companion.MouthMode

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** One timeline supplies prop placement and eye targets, so their contact agrees. */
internal object LumenScenes {
    val accents = setOf(PerformanceAccent.SIGNATURE, PerformanceAccent.BUTTERFLY, PerformanceAccent.DETECTIVE,
        PerformanceAccent.YARN, PerformanceAccent.BALL, PerformanceAccent.ACORN,
        PerformanceAccent.SNACK, PerformanceAccent.NEST, PerformanceAccent.RAIN,
        PerformanceAccent.MOUSE, PerformanceAccent.BOOK, PerformanceAccent.HAT_COWBOY,
        PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET)

    fun ease(v: Float) = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    fun envelope(p: Float) = if (p.isFinite()) ease(p / .16f) * (1f - ease((p - .84f) / .16f)) else 0f

    private fun between(p: Float, a: Float, b: Float, from: Offset, to: Offset): Offset =
        from + (to - from) * ease((p - a) / (b - a))

    fun target(accent: PerformanceAccent, progress: Float, performanceId: String? = null): Offset {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        LumenStoryArcs.sample(performanceId, accent, p)?.let { return it.gaze }
        return when (accent) {
            PerformanceAccent.SIGNATURE -> LumenSignatureScenes.find(performanceId)?.let { SignatureTimeline.sample(it.prop, p).point } ?: Offset.Zero
            PerformanceAccent.BUTTERFLY -> when {
                p < .32f -> between(p, 0f, .32f, Offset(-.32f, -.20f), Offset(.23f, -.29f))
                p < .53f -> between(p, .32f, .53f, Offset(.23f, -.29f), Offset(.01f, .10f))
                p < .69f -> Offset(.01f, .10f) // Lands by the nose: let the eyes converge and hold.
                else -> between(p, .69f, 1f, Offset(.01f, .10f), Offset(-.30f, -.29f))
            }
            PerformanceAccent.DETECTIVE -> between(p, .15f, .48f, Offset(.31f, .22f), Offset(.23f, .15f))
            PerformanceAccent.YARN -> Offset(sin(p * PI.toFloat() * 2f) * .24f, .235f)
            PerformanceAccent.BALL -> Offset(sin(p * PI.toFloat() * 2f) * -.25f,
                .24f - abs(sin(p * PI.toFloat() * 3f)) * .13f)
            PerformanceAccent.ACORN -> between(p, .20f, .57f, Offset(.28f, .24f), Offset(.055f, .22f))
            PerformanceAccent.SNACK -> between(p, .25f, .58f, Offset(.26f, .24f), Offset(.22f, .04f))
            PerformanceAccent.NEST -> Offset(.03f, .26f)
            PerformanceAccent.RAIN -> Offset(.31f + sin(p * PI.toFloat() * 1.3f) * .025f,
                -.21f + ease((p - .13f) / .6f) * .42f)
            PerformanceAccent.MOUSE, PerformanceAccent.BOOK, PerformanceAccent.HAT_COWBOY,
            PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET -> LumenStoryTimeline.target(accent, p)
            else -> Offset.Zero
        }
    }

    fun gaze(input: LumenInput, accent: PerformanceAccent, p: Float, performanceId: String? = null): LumenInput {
        if (accent !in accents) return input
        val arc = LumenStoryArcs.sample(performanceId, accent, p)
        val point = arc?.gaze ?: target(accent, p, performanceId)
        val weight = envelope(p)
        val glance = if (accent == PerformanceAccent.SNACK) ease((p - .57f) / .17f) else 0f
        return input.copy(sceneGazeX = (point.x / .36f).coerceIn(-1f, 1f) * (1f - glance) - .7f * glance,
            sceneGazeY = (point.y / .36f).coerceIn(-1f, 1f) * (1f - glance) - .08f * glance,
            sceneGazeWeight = weight,
            sceneTravelWeight = arc?.travelWeight ?: if (accent == PerformanceAccent.MOUSE) .75f else 0f,
            headwearWeight = if (accent in LumenStoryTimeline.hats) 1f else 0f,
            earAsymmetry = input.earAsymmetry + (arc?.earAsymmetry ?: 0f) * weight,
            lowerLid = input.lowerLid + (arc?.lowerLid ?: 0f) * weight,
            performanceJaw = maxOf(input.performanceJaw, (arc?.jawOpen ?: 0f) * weight))
    }
}

/** Three authored stories reuse the native props, but never borrow their old
 * timing. The renderer and gaze adapter sample this same pure score on the
 * active performance clock. Holds are deliberate: ears search after the mouse
 * disappears, the owl rereads the same line, and the duck pauses under its hat. */
internal object LumenStoryArcs {
    val ids = setOf("lost_mouse", "reading_realization", "hat_mishap")

    data class Pose(
        val gaze: Offset,
        val mouse: LumenStoryTimeline.Mouse? = null,
        val book: LumenStoryTimeline.Book? = null,
        val hat: LumenStoryTimeline.Hat? = null,
        val propVisibility: Float = 1f,
        val earAsymmetry: Float = 0f,
        val lowerLid: Float = 0f,
        val jawOpen: Float = 0f,
        val travelWeight: Float = 0f,
    )

    private fun move(t: Float, a: Float, b: Float, from: Offset, to: Offset) =
        from + (to - from) * LumenScenes.ease((t - a) / (b - a))
    private fun hold(t: Float, rise: Float, top: Float, release: Float, end: Float) =
        LumenScenes.ease((t - rise) / (top - rise)) *
            (1f - LumenScenes.ease((t - release) / (end - release)))

    fun sample(id: String?, accent: PerformanceAccent, progress: Float): Pose? {
        val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        return when {
            id == "lost_mouse" && accent == PerformanceAccent.MOUSE -> lostMouse(t)
            id == "reading_realization" && accent == PerformanceAccent.BOOK -> reading(t)
            id == "hat_mishap" && accent == PerformanceAccent.HAT_COWBOY -> hatMishap(t)
            else -> null
        }
    }

    private fun lostMouse(t: Float): Pose {
        fun position(p: Float): Offset = when {
            p < .19f -> move(p, .04f, .19f, Offset(-.24f, .26f), Offset(.20f, .25f))
            p < .25f -> move(p, .19f, .25f, Offset(.20f, .25f), Offset(.25f, .215f))
            p < .52f -> move(p, .45f, .52f, Offset(.25f, .215f), Offset(.10f, .263f))
            p < .59f -> move(p, .52f, .59f, Offset(.10f, .263f), Offset(-.075f, .263f))
            p < .68f -> Offset(-.075f, .263f)
            p < .76f -> move(p, .68f, .76f, Offset(-.075f, .263f), Offset(.275f, .251f))
            p < .86f -> Offset(.275f, .251f) // Safe distance; mouse turns to look back.
            else -> move(p, .86f, .98f, Offset(.275f, .251f), Offset(.33f, .26f))
        }
        val disappear = LumenScenes.ease((t - .19f) / .04f)
        val rediscover = LumenScenes.ease((t - .52f) / .045f)
        val visible = (1f - disappear) + rediscover
        val ears = -8f * hold(t, .245f, .285f, .34f, .39f) +
            8f * hold(t, .385f, .425f, .49f, .54f)
        val search = hold(t, .24f, .27f, .49f, .555f)
        val searchGaze = move(t, .36f, .415f, Offset(-.25f, .025f), Offset(.25f, .025f))
        val point = position(t)
        val direction = position((t + .002f).coerceAtMost(1f)).x - position((t - .002f).coerceAtLeast(0f)).x
        val stride = sin(t * 185f) * (abs(direction) * 250f).coerceIn(0f, 1f)
        val pounce = .55f * hold(t, .72f, .75f, .775f, .82f)
        val mouse = LumenStoryTimeline.Mouse(point + Offset(0f, stride * .003f),
            if (direction < 0f || t in .78f.. .86f) -1f else 1f, stride, pounce)
        return Pose(gaze = mouse.point * (1f - search) + searchGaze * search, mouse = mouse,
            propVisibility = visible.coerceIn(0f, 1f), earAsymmetry = ears,
            travelWeight = .42f * (1f - search) * LumenScenes.envelope(t))
    }

    private fun reading(t: Float): Pose {
        val lineStart = Offset(-.12f, .241f)
        val lineEnd = Offset(.12f, .241f)
        val gaze = when {
            t < .19f -> move(t, .12f, .19f, Offset(0f, .245f), lineStart)
            t < .30f -> move(t, .19f, .30f, lineStart, lineEnd)
            t < .36f -> move(t, .30f, .36f, lineEnd, lineStart)
            t < .46f -> move(t, .36f, .46f, lineStart, lineEnd)
            t < .52f -> lineEnd // The difficult passage gets an actual hold.
            t < .575f -> move(t, .52f, .575f, lineEnd, Offset(.025f, -.045f))
            t < .66f -> Offset(.025f, -.045f) // Aha: eyes lift before the satisfied nod.
            t < .755f -> move(t, .66f, .755f, Offset(.025f, -.045f), Offset(-.07f, .24f))
            t < .85f -> move(t, .755f, .85f, Offset(-.07f, .24f), Offset(.07f, .24f))
            else -> move(t, .85f, 1f, Offset(.07f, .24f), Offset(0f, .12f))
        }
        val book = LumenStoryTimeline.Book(LumenScenes.ease(t / .17f),
            LumenScenes.ease((t - .765f) / .095f), gaze)
        return Pose(gaze, book = book,
            jawOpen = .16f * hold(t, .525f, .57f, .625f, .69f),
            lowerLid = .12f * hold(t, .65f, .71f, .84f, .95f))
    }

    private fun hatMishap(t: Float): Pose {
        val slip = LumenScenes.ease((t - .22f) / .12f)
        val recovery = LumenScenes.ease((t - .49f) / .12f)
        val down = slip * (1f - recovery)
        val wobble = hold(t, .465f, .50f, .525f, .555f)
        val straighten = 1f - LumenScenes.ease((t - .78f) / .16f)
        val anchor = Offset(.018f * down, -.252f + .232f * down - .012f * wobble)
        val tip = 6f * down - 10f * wobble + 3f * recovery * straighten
        val gaze = when {
            t < .22f -> Offset(.025f, -.015f)
            t < .34f -> move(t, .22f, .34f, Offset(.025f, -.015f), Offset(.025f, -.19f))
            t < .49f -> Offset(.025f, -.19f)
            t < .63f -> move(t, .49f, .63f, Offset(.025f, -.19f), Offset(.20f, .045f))
            t < .79f -> Offset(.20f, .045f) // Sheepish sideways glance, held long enough to read.
            else -> move(t, .79f, .96f, Offset(.20f, .045f), Offset.Zero)
        }
        return Pose(gaze, hat = LumenStoryTimeline.Hat(anchor, tip, gaze),
            lowerLid = .16f * hold(t, .65f, .71f, .81f, .94f))
    }
}

/** Small original vector scenes. No asset fetch, secondary clock or full-screen blur. */
internal class LumenScenePainter {
    private val path = Path()
    private val storyProps = LumenStoryPropPainter()
    private val signatures = LumenSignaturePropPainter()

    fun draw(scope: DrawScope, accent: PerformanceAccent, progress: Float, center: Offset,
        unit: Float, glow: Color, feature: Color, frame: LumenFrame, performanceId: String? = null) = with(scope) {
        if (accent !in LumenScenes.accents) return
        if (accent == PerformanceAccent.SIGNATURE) {
            LumenSignatureScenes.find(performanceId)?.let { signatures.draw(this, it, progress, center, unit) }
            return
        }
        val alpha = LumenScenes.envelope(progress)
        if (alpha < .001f) return
        val p = progress.coerceIn(0f, 1f)
        val arc = LumenStoryArcs.sample(performanceId, accent, p)
        val target = arc?.gaze ?: LumenScenes.target(accent, p)
        val color = lerp(glow, Color.White, .4f).copy(alpha = alpha)
        val fur = lerp(feature, Color.White, .22f).copy(alpha = alpha)
        translate(center.x, center.y) {
            scale(unit, unit, Offset.Zero) {
                when (accent) {
                    PerformanceAccent.MOUSE, PerformanceAccent.BOOK, PerformanceAccent.HAT_COWBOY,
                    PerformanceAccent.HAT_WIZARD, PerformanceAccent.HAT_BERET ->
                        storyProps.draw(this, accent, p, glow, feature, frame, arc)
                    PerformanceAccent.BUTTERFLY -> translate(target.x, target.y) {
                        rotate(sin(p * 17f) * 16f, Offset.Zero) {
                            val flutter = .35f + abs(sin(p * 100f)) * .65f
                            for (side in intArrayOf(-1, 1)) scale(side * flutter, 1f, Offset.Zero) {
                                path.reset(); path.moveTo(0f, 0f)
                                path.cubicTo(.014f, -.063f, .089f, -.036f, .054f, .004f)
                                path.cubicTo(.088f, .048f, .021f, .055f, 0f, 0f); path.close()
                                drawPath(path, Brush.linearGradient(listOf(color, lerp(glow, Color(0xFFFFC4D9), .7f).copy(alpha = alpha)),
                                    Offset(0f, -.03f), Offset(.07f, .03f)))
                                drawLine(Color.White.copy(alpha = alpha * .4f), Offset(.003f, 0f), Offset(.044f, -.016f), .002f)
                            }
                            drawLine(fur, Offset(0f, -.020f), Offset(0f, .024f), .004f)
                        }
                    }
                    PerformanceAccent.DETECTIVE -> {
                        halo(target, .075f, color)
                        drawRoundRect(color.copy(alpha = alpha * .26f), target - Offset(.025f, .025f),
                            Size(.05f, .05f), CornerRadius(.013f))
                        val lens = target + Offset(.013f * sin(p * 10f), -.012f)
                        drawLine(lerp(fur, Color(0xFF435D6A), .6f).copy(alpha = alpha), lens + Offset(.040f, .042f),
                            lens + Offset(.089f, .103f), .018f, androidx.compose.ui.graphics.StrokeCap.Round)
                        drawCircle(color.copy(alpha = alpha * .12f), .066f, lens)
                        drawCircle(fur, .067f, lens, style = Stroke(.008f))
                        drawArc(Color.White.copy(alpha = alpha * .8f), 215f, 65f, false,
                            lens - Offset(.050f, .050f), Size(.10f, .10f), style = Stroke(.004f))
                        drawCircle(color, .014f, target)
                    }
                    PerformanceAccent.YARN -> {
                        path.reset(); path.moveTo(target.x, target.y + .04f)
                        path.cubicTo(target.x - .10f, .31f, -.20f, .20f, -.31f, .29f)
                        drawPath(path, color.copy(alpha = alpha * .5f), style = Stroke(.003f))
                        ball(target, .046f, color)
                        rotate(p * 370f, target) { repeat(4) { i ->
                            drawArc(fur.copy(alpha = alpha * .7f), 30f, 275f, false,
                                target - Offset(.039f - i * .006f, .039f), Size(.078f - i * .012f, .078f), style = Stroke(.0025f))
                        } }
                        paw(target + Offset(-.043f, .024f + sin(p * 15f) * .014f), fur, alpha)
                    }
                    PerformanceAccent.BALL -> {
                        drawOval(Color.Black.copy(alpha = alpha * .20f), Offset(target.x - .05f, .29f), Size(.1f, .013f))
                        val ballColor = lerp(glow, Color(0xFFD9EA7C), .65f).copy(alpha = alpha)
                        ball(target, .044f, ballColor)
                        rotate(p * -420f, target) {
                            drawArc(Color.White.copy(alpha = alpha * .8f), -80f, 160f, false,
                                target - Offset(.060f, .033f), Size(.065f, .066f), style = Stroke(.0035f))
                            drawArc(Color.White.copy(alpha = alpha * .8f), 100f, 160f, false,
                                target + Offset(-.005f, -.033f), Size(.065f, .066f), style = Stroke(.0035f))
                        }
                    }
                    PerformanceAccent.ACORN -> {
                        val nut = lerp(feature, Color(0xFFC99860), .64f).copy(alpha = alpha)
                        ball(target + Offset(0f, .009f), .029f, nut)
                        drawOval(lerp(nut, Color(0xFF453629), .55f).copy(alpha = alpha),
                            target - Offset(.036f, .025f), Size(.072f, .035f))
                        drawLine(fur, target - Offset(0f, .022f), target - Offset(.006f, .042f), .006f)
                        val approach = LumenScenes.ease((p - .32f) / .22f)
                        for (side in intArrayOf(-1, 1)) paw(target + Offset(side * (.074f - approach * .035f), .022f), fur, alpha)
                    }
                    PerformanceAccent.SNACK -> {
                        val visible = 1f - LumenScenes.ease((p - .47f) / .16f)
                        if (visible > .001f) {
                            val cookie = lerp(feature, Color(0xFFE8AD63), .65f).copy(alpha = alpha * visible)
                            ball(target, .041f, cookie)
                            for (i in 0..4) drawCircle(Color(0xFF634A35).copy(alpha = alpha * visible), .004f,
                                target + Offset(sin(i * 2.4f) * .024f, kotlin.math.cos(i * 2.4f) * .025f))
                            paw(target + Offset(.030f, .025f), fur, alpha * visible)
                        }
                        val crumbs = LumenScenes.ease((p - .53f) / .1f)
                        repeat(3) { i -> drawCircle(fur.copy(alpha = alpha * crumbs * .7f), .0025f,
                            Offset(.08f + i * .009f, .22f + sin(i * 3f) * .008f)) }
                    }
                    PerformanceAccent.NEST -> {
                        val warm = lerp(feature, Color(0xFFE7AF88), .42f).copy(alpha = alpha)
                        drawOval(Brush.radialGradient(listOf(warm.copy(alpha = alpha * .5f), Color.Transparent),
                            Offset(0f, .265f), .30f), Offset(-.33f, .18f), Size(.66f, .15f))
                        path.reset(); path.moveTo(-.27f, .23f)
                        path.cubicTo(-.12f, .30f, .14f, .16f, .28f, .23f)
                        path.cubicTo(.25f, .34f, -.24f, .34f, -.27f, .23f); path.close()
                        drawPath(path, warm.copy(alpha = alpha * .55f))
                        drawPath(path, fur.copy(alpha = alpha * .6f), style = Stroke(.003f))
                        if (frame.form.costume > .05f) {
                            // A temporary curled tail; no full-body rig is required.
                            val curl = LumenScenes.ease((p - .18f) / .36f)
                            path.reset(); path.moveTo(.29f, .18f)
                            path.cubicTo(.32f, .32f, -.06f * curl, .36f, -.18f * curl, .28f)
                            drawPath(path, fur.copy(alpha = alpha * frame.form.costume),
                                style = Stroke(.032f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        }
                    }
                    PerformanceAccent.RAIN -> {
                        val pane = Offset(.23f, -.27f)
                        drawRoundRect(color.copy(alpha = alpha * .05f), pane, Size(.16f, .55f), CornerRadius(.025f))
                        drawRoundRect(color.copy(alpha = alpha * .30f), pane, Size(.16f, .55f), CornerRadius(.025f), style = Stroke(.003f))
                        for (i in 0..4) {
                            val y = -.23f + ((p * .43f + i * .09f) % .45f)
                            drawLine(color.copy(alpha = alpha * .27f), Offset(.25f + i * .025f, y),
                                Offset(.247f + i * .025f, y + .033f), .003f, androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                        ball(target, .012f, color)
                        val splash = LumenScenes.ease((p - .63f) / .06f) * (1f - LumenScenes.ease((p - .73f) / .1f))
                        repeat(5) { i ->
                            val angle = (i + 1) * PI.toFloat() / 6
                            drawCircle(color.copy(alpha = alpha * splash), .0035f,
                                target + Offset(kotlin.math.cos(angle) * .06f * splash, -sin(angle) * .045f * splash))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun DrawScope.halo(p: Offset, r: Float, c: Color) =
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = c.alpha * .18f), Color.Transparent), p, r), r, p)

    private fun DrawScope.ball(p: Offset, r: Float, c: Color) {
        halo(p, r * 1.8f, c)
        drawCircle(Brush.radialGradient(listOf(lerp(c, Color.White, .42f).copy(alpha = c.alpha), c,
            lerp(c, Color.Black, .50f).copy(alpha = c.alpha)), p - Offset(r * .35f, r * .35f), r * 1.6f), r, p)
    }

    private fun DrawScope.paw(p: Offset, c: Color, alpha: Float) {
        drawOval(Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * .1f)), p, .031f),
            p - Offset(.028f, .021f), Size(.056f, .042f))
        for (i in -1..1) drawLine(c.copy(alpha = alpha * .55f), p + Offset(i * .010f, -.01f),
            p + Offset(i * .010f, -.018f), .002f)
    }
}
