package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/** Reusable paths; two translucent sculpted lenses, not a bitmap or a copied device face. */
class LumenPainter {
    private val aperture = Path()
    private val ear = Path()
    private val features = Path()
    private val presencePaint = Paint()
    private val accents = LumenAccentPainter()
    private val scenes = LumenScenePainter()
    private val animalParts = LumenAnimalParts()
    private val speciesParts = LumenSpeciesParts()
    private val characterParts = LumenCharacterParts()
    private val flyParts = LumenFruitFlyParts()
    private val officeHelpers = LumenOfficeHelperParts()
    private val morphPaint = Paint()
    private var eyeMaterials: LumenEyeMaterials? = null
    private val irisDirections = Array(48) { i ->
        val a = i * kotlin.math.PI.toFloat() * 2f / 48f
        Offset(kotlin.math.cos(a), kotlin.math.sin(a))
    }


    fun draw(
        scope: DrawScope, frame: LumenFrame,
        eyeColor: Color, glowColor: Color,
        cameraOff: Boolean = false, micOff: Boolean = false,
        speakerOff: Boolean = false, statusColor: Color = Color(0xFF849DA5),
        catFeatures: Boolean = false, featureColor: Color = eyeColor,
        performance: AvatarPerformance? = null, progress: Float = 0f,
        showPrivacyMarks: Boolean = true,
    ) = with(scope) {
        val unit = lumenUnit(size.width, size.height, frame)
        val center = lumenCenter(size.width, size.height, frame)
        val rx = unit * 0.152f
        val ry = unit * 0.19f
        val spread = unit * 0.213f
        // Only an explicitly requested hide uses an offscreen layer; normal idle
        // rendering keeps its cheap direct-to-canvas path. Privacy stays visible.
        val stretch = lumenStretch(frame)
        scale(1f / stretch, stretch, center) {
        // Privacy-only passes are used by 3D and generated avatars every frame.
        // An alpha-zero saveLayer still builds every lens and anatomy path.
        if (frame.presence > 0f) {
        val fading = frame.presence < 0.995f
        if (fading) {
            presencePaint.alpha = frame.presence.coerceIn(0f, 1f)
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), presencePaint)
        }
        rotate(lumenRoll(frame), center) {
            animalParts.tail(this, center, unit, frame, featureColor, glowColor)
            characterParts.backdrop(this, center, unit, frame, featureColor)
            flyParts.backdrop(this, center, unit, frame)
            officeHelpers.backdrop(this, center, unit, frame)
            speciesParts.backdrop(this, center, unit, frame, featureColor, glowColor)
            for (side in intArrayOf(-1, 1)) {
                val flyTurn = frame.form.fruitFly * frame.perspective
                val c = center + Offset(side * spread + side * flyTurn * unit * .018f,
                    abs(frame.headX) * side * unit * .009f)
                translate(c.x, c.y) {
                    val nearScale = 1f + side * (frame.headX * .10f + flyTurn * .13f)
                    scale(nearScale, 1f, Offset.Zero) {
                        animalEar(side, rx, ry, frame, featureColor, glowColor)
                        animalParts.roundEar(this, side, rx, ry, frame, featureColor, glowColor)
                        animalParts.mask(this, rx, ry, frame, featureColor)
                        speciesParts.eyeSurround(this, side, unit, frame, featureColor)
                        characterParts.eyeSurround(this, side, unit, frame, featureColor)
                        lens(side, rx, ry, frame, eyeColor, glowColor)
                    }
                }
            }
            animalParts.muzzle(this, center, unit, frame, featureColor, glowColor)
            speciesParts.beak(this, center, unit, frame, featureColor)
            characterParts.muzzle(this, center, unit, frame, featureColor)
            flyParts.mouth(this, center, unit, frame)
            officeHelpers.foreground(this, center, unit, frame)
            val muzzleAlpha = if (catFeatures) 1f else frame.form.shortMuzzle
            if (muzzleAlpha > 0.005f) {
                // Only the brief morph needs a small offscreen layer. Stable
                // forms draw directly; eye/privacy rendering is never faded here.
                if (muzzleAlpha < 0.995f) {
                    morphPaint.alpha = muzzleAlpha
                    drawContext.canvas.saveLayer(Rect(center - Offset(unit * 0.33f, 0f),
                        Size(unit * 0.66f, unit * 0.36f)), morphPaint)
                }
                catMuzzle(center, unit, frame, featureColor, glowColor,
                    if (catFeatures) 1f else frame.form.whiskers)
                if (muzzleAlpha < 0.995f) drawContext.canvas.restore()
            }
        }
        if (fading) drawContext.canvas.restore()
        }
        if (performance != null) {
            // Phone tilt carries the entire scene, as the old outer layer did.
            // Authored head roll remains relative to its prop; privacy is fixed.
            rotate(frame.screenRoll, center) {
                if (performance.accent in LumenScenes.accents)
                    scenes.draw(this, performance.accent, progress, center, unit, glowColor, featureColor, frame, performance.id)
                else accents.draw(this, performance.accent, progress, center, unit, glowColor)
            }
        }
        }
        // Honest, quiet privacy indicators: these are not pretending to track when camera is off.
        // Keep privacy anchored to the stage, not chasing a roaming character.
        // Neither a performance, hide nor attention can move these marks away.
        if (!showPrivacyMarks) return@with
        val badgeScale = lumenPrivacyScale(size.width, size.height, 1.dp.toPx())
        val badgeCenter = lumenPrivacyCenter(size.width, size.height, badgeScale)
        val badgeY = badgeCenter.y
        val count = listOf(cameraOff, micOff, speakerOff).count { it }
        var badge = 0
        fun badgeX(): Float = badgeCenter.x + (badge++ - (count - 1) / 2f) * 32f * badgeScale
        if (cameraOff) {
            val p = Offset(badgeX(), badgeY)
            scale(badgeScale, badgeScale, p) {
            drawRoundRect(statusColor, p - Offset(9f, 6f), Size(18f, 12f),
                androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(1.6f))
            drawLine(statusColor, p + Offset(-11f, 9f), p + Offset(11f, -9f), 1.8f, StrokeCap.Round)
            }
        }
        if (micOff) {
            val p = Offset(badgeX(), badgeY)
            scale(badgeScale, badgeScale, p) {
            drawRoundRect(statusColor, p - Offset(3f, 8f), Size(6f, 12f),
                androidx.compose.ui.geometry.CornerRadius(3f), style = Stroke(1.6f))
            drawLine(statusColor, p + Offset(-9f, 9f), p + Offset(9f, -9f), 1.8f, StrokeCap.Round)
            }
        }
        if (speakerOff) {
            val p = Offset(badgeX(), badgeY)
            scale(badgeScale, badgeScale, p) {
                ear.reset(); ear.moveTo(p.x - 8f, p.y - 3f); ear.lineTo(p.x - 3f, p.y - 3f)
                ear.lineTo(p.x + 3f, p.y - 8f); ear.lineTo(p.x + 3f, p.y + 8f)
                ear.lineTo(p.x - 3f, p.y + 3f); ear.lineTo(p.x - 8f, p.y + 3f); ear.close()
                drawPath(ear, statusColor, style = Stroke(1.6f))
                drawLine(statusColor, p + Offset(-10f, 10f), p + Offset(10f, -10f), 1.8f, StrokeCap.Round)
            }
        }
    }

    private fun DrawScope.catMuzzle(c: Offset, u: Float, f: LumenFrame, color: Color, glow: Color, whiskerWeight: Float = 1f) {
        // A small hovering nose and two soft muzzle curves; no opaque head slab.
        val nose = c + Offset(f.gazeX * u * 0.012f, u * 0.158f)
        val r = u * 0.027f
        features.reset()
        features.moveTo(nose.x - r, nose.y)
        features.cubicTo(nose.x - r * 0.8f, nose.y - r * 0.55f, nose.x + r * 0.8f, nose.y - r * 0.55f, nose.x + r, nose.y)
        features.cubicTo(nose.x + r * 0.82f, nose.y + r * 0.50f, nose.x + r * 0.18f, nose.y + r, nose.x, nose.y + r * 0.93f)
        features.cubicTo(nose.x - r * 0.18f, nose.y + r, nose.x - r * 0.82f, nose.y + r * 0.50f, nose.x - r, nose.y)
        drawPath(features, glow.copy(alpha = 0.08f), style = Stroke(u * 0.025f))
        drawPath(features, Brush.linearGradient(listOf(lerp(color, Color.White, 0.55f), color,
            lerp(color, Color.Black, 0.48f)), nose - Offset(r, r), nose + Offset(r, r)))
        drawOval(Color.White.copy(alpha = 0.4f), nose + Offset(-r * 0.45f, -r * 0.18f), Size(r * 0.7f, r * 0.2f))
        val mouth = nose + Offset(0f, r * 1.15f)
        val opening = f.mouthOpen * u * 0.018f
        if (opening > u * 0.001f) {
            drawOval(lerp(color, Color.Black, 0.78f), mouth + Offset(-u * 0.024f, u * 0.009f),
                Size(u * 0.048f, opening + u * 0.01f))
        }
        features.reset()
        features.moveTo(nose.x, nose.y + r * 0.83f)
        features.lineTo(mouth.x, mouth.y + u * 0.006f)
        for (side in intArrayOf(-1, 1)) {
            features.moveTo(mouth.x, mouth.y + u * 0.006f)
            features.cubicTo(mouth.x + side * u * 0.012f, mouth.y + u * (0.028f + f.mouthOpen * 0.006f),
                mouth.x + side * u * 0.038f, mouth.y + u * 0.034f,
                mouth.x + side * u * 0.055f, mouth.y + u * (0.010f - f.smile * 0.016f))
        }
        drawPath(features, glow.copy(alpha = 0.08f), style = Stroke(u * 0.016f, cap = StrokeCap.Round))
        drawPath(features, lerp(color, Color.White, 0.16f).copy(alpha = 0.85f),
            style = Stroke(u * 0.004f, cap = StrokeCap.Round))
        // Low-amplitude whisker follow-through comes from the same damped rig.
        for (side in intArrayOf(-1, 1)) for (i in -1..1) {
            val root = nose + Offset(side * u * 0.085f, u * (0.044f + i * 0.009f))
            val tip = nose + Offset(side * u * (0.215f - kotlin.math.abs(i) * 0.016f),
                u * (0.045f + i * 0.047f + f.whiskers * 0.005f + f.earLag * side * 0.0009f))
            features.reset(); features.moveTo(root.x, root.y)
            features.cubicTo(root.x + side * u * 0.045f, root.y, tip.x - side * u * 0.026f, tip.y, tip.x, tip.y)
            drawPath(features, Brush.linearGradient(listOf(color.copy(alpha = 0.62f * whiskerWeight), glow.copy(alpha = 0.20f * whiskerWeight)), root, tip),
                style = Stroke(u * 0.003f, cap = StrokeCap.Round))
        }
    }

    // Both lenses, and every frame of a settled form, ask for the same iris blend.
    private var irisForm: AvatarFormTraits? = null
    private var irisSelected = Color.Unspecified
    private var irisColor = Color.Unspecified

    private fun irisColorFor(form: AvatarFormTraits, selected: Color): Color {
        if (form != irisForm || selected != irisSelected) {
            irisColor = lumenIrisColor(form, selected)
            irisForm = form
            irisSelected = selected
        }
        return irisColor
    }

    private fun DrawScope.lens(side: Int, baseRx: Float, baseRy: Float, f: LumenFrame, selectedColor: Color, glow: Color) {
        val shape = lumenEyeShape(f.form)
        val helper = (f.form.clippy + f.form.wizard).coerceIn(0f, 1f)
        val color = irisColorFor(f.form, selectedColor)
        val rx = baseRx * shape.width
        val ry = baseRy * shape.height
        val m = eyeMaterials?.takeIf { it.rx == rx && it.ry == ry && it.color == color && it.glow == glow }
            ?: LumenEyeMaterials(rx, ry, color, glow).also { eyeMaterials = it }
        val open = (if (side < 0) f.leftOpen else f.rightOpen).coerceIn(0f, 1.31f)
        val lowerLift = (if (side < 0) f.lowerLidLeft else f.lowerLidRight).coerceIn(0f, .65f)
        val upper = ry * (.10f - 1.10f * open)
        val lower = ry * (.10f + (.88f - f.smile * .17f - lowerLift) * open)
        val tilt = lumenEyeTilt(side, f, shape)
        rotate(tilt, Offset.Zero) {
            drawOval(m.halo, Offset(-rx * 2.7f, -ry * 2.5f), Size(rx * 5.4f, ry * 5f), alpha = 1f - helper * .65f)
            if (open < .025f) {
                aperture.reset()
                aperture.moveTo(-rx, 0f)
                val curve = ry * (.10f - f.smile * .25f)
                aperture.cubicTo(-rx * .45f, curve, rx * .45f, curve, rx, 0f)
                if (helper > .005f) drawPath(aperture, Color(0xFFFFFCF1).copy(alpha = helper),
                    style = Stroke(ry * .07f, cap = StrokeCap.Round))
                drawPath(aperture, lerp(lerp(color, Color.White, .40f), Color(0xFF202538), helper),
                    style = Stroke(ry * .028f, cap = StrokeCap.Round))
                return@rotate
            }
            // The inner brow rises for concern and lowers for resolve. The lower
            // lid lifts independently for warmth; neither deforms the iris itself.
            val inner = f.innerLift.coerceIn(-.5f, .55f) * ry * open
            val leftBrow = upper + if (side < 0) inner * .23f else -inner * .55f
            val rightBrow = upper + if (side < 0) -inner * .55f else inner * .23f
            val lidTension = f.tension.coerceIn(0f, 1f)
            aperture.reset()
            aperture.moveTo(-rx, 0f)
            val corner = .70f + lidTension * .10f - shape.almond * .48f + shape.mechanical * .20f
            val shoulder = .58f + shape.mechanical * .28f
            aperture.cubicTo(-rx, leftBrow * corner, -rx * shoulder, leftBrow, -rx * .04f, upper)
            aperture.cubicTo(rx * shoulder, rightBrow, rx, rightBrow * corner, rx, 0f)
            aperture.cubicTo(rx, lower * (corner - .04f), rx * shoulder, lower, rx * .02f, lower)
            aperture.cubicTo(-rx * shoulder, lower, -rx, lower * (corner - .02f), -rx, 0f)
            aperture.close()
            drawPath(aperture, glow.copy(alpha = .045f), style = Stroke(ry * .08f))
            clipPath(aperture) {
                drawRect(m.sclera, Offset(-rx, -ry * 1.5f), Size(rx * 2f, ry * 3f))
                if (helper > .005f) drawRect(Color(0xFFFFFCF1).copy(alpha = helper),
                    Offset(-rx, -ry * 1.5f), Size(rx * 2f, ry * 3f))
                if (shape.dark > .005f) drawRect(Color(0xFF07161D).copy(alpha = shape.dark),
                    Offset(-rx, -ry * 1.5f), Size(rx * 2f, ry * 3f))
                if (f.form.fruitFly > .005f) flyParts.compoundEye(this, rx, ry, f, color)
                val iris = Offset(f.gazeX * rx * .28f, f.gazeY * ry * .22f + ry * .06f)
                val irisR = rx * .86f * shape.irisScale
                translate(iris.x, iris.y) {
                    drawCircle(m.iris, irisR, Offset.Zero, alpha = (1f - shape.dark * .92f) * (1f - f.form.fruitFly) * (1f - helper))
                    drawCircle(Color(0xFF14333E).copy(alpha = .64f * (1f - f.form.fruitFly) * (1f - helper)), irisR * .99f, Offset.Zero,
                        style = Stroke(rx * .023f))
                    // Irregular long/short fibres are cached directions; nothing
                    // shimmers or rolls a random number at display refresh rate.
                    val brightFibre = m.fibre.copy(alpha = .21f * (1f - shape.dark) * (1f - f.form.fruitFly) * (1f - helper))
                    val dimFibre = m.fibre.copy(alpha = .10f * (1f - shape.dark) * (1f - f.form.fruitFly) * (1f - helper))
                    for (i in irisDirections.indices) {
                        val direction = irisDirections[i]
                        val root = .48f + (i % 5) * .024f
                        val tip = .89f + (i % 3) * .018f
                        drawLine(if (i % 3 == 0) brightFibre else dimFibre,
                            direction * (irisR * root), direction * (irisR * tip), rx * .009f, StrokeCap.Round)
                    }
                    val pr = irisR * (.56f + (f.pupil - .5f) * .40f) * shape.pupilScale * (1f - .24f * f.form.fruitFly)
                    val slit = shape.pupilAspect + (1f - shape.pupilAspect) * (f.pupil - .5f).coerceAtLeast(0f) * .6f
                    val pupilHeight = pr * (1f + (1f - shape.pupilAspect) * .38f)
                    // Small flat pupils retain the same gaze and blink geometry.
                    // Custom eye colors remain available as a cartoon iris ring.
                    val tint = if (selectedColor != Color(AvatarColors.DEFAULT_EYE)) helper else f.form.wizard
                    if (tint > .005f) drawCircle(color.copy(alpha = tint), pr * 1.23f, Offset.Zero)
                    drawOval(m.pupil, Offset(-pr * slit, -pupilHeight), Size(pr * slit * 2f, pupilHeight * 2f))
                    if (helper > .005f) {
                        drawOval(Color(0xFF131A2A).copy(alpha = helper), Offset(-pr * slit, -pupilHeight),
                            Size(pr * slit * 2f, pupilHeight * 2f))
                        drawCircle(Color.White.copy(alpha = helper), pr * .12f, Offset(-pr * .62f, -pr * .50f))
                    }
                    drawOval(color.copy(alpha = .13f * (1f - shape.dark) * (1f - f.form.fruitFly) * (1f - helper)),
                        Offset(-pr * slit * 1.035f, -pupilHeight * 1.035f),
                        Size(pr * slit * 2.07f, pupilHeight * 2.07f), style = Stroke(rx * .015f))
                    if (shape.mechanical > .005f) drawCircle(color.copy(alpha = shape.mechanical * .7f),
                        irisR * .9f, Offset.Zero, style = Stroke(rx * .035f))
                }
                // Studio key + fill: a shaped soft reflection, a small sharp
                // catchlight and a restrained lower bounce describe curved glass.
                drawOval(m.reflection, Offset(-rx * .60f - f.gazeX * rx * .035f, -ry * .67f), Size(rx * .77f, ry * .43f), alpha = 1f - helper)
                drawOval(Color.White.copy(alpha = .86f * (1f - helper)), Offset(-rx * .39f, -ry * .52f), Size(rx * .13f, ry * .09f))
                drawOval(m.fillLight, Offset(-rx * .9f, ry * .24f), Size(rx * 1.8f, ry * .9f), alpha = 1f - helper)
                drawOval(Color(0xFFE4F4F0).copy(alpha = .64f * (1f - helper)), Offset(rx * .44f, ry * .32f), Size(rx * .065f, ry * .095f))
                drawRect(Brush.verticalGradient(listOf(Color(0x88091622), Color.Transparent), upper, upper + ry * .31f),
                    Offset(-rx, upper), Size(rx * 2f, ry * .32f), alpha = 1f - helper)
            }
            drawPath(aperture, m.rim, style = Stroke(ry * .014f))
            if (helper > .005f) drawPath(aperture, Color(0xFF202538).copy(alpha = helper),
                style = Stroke(ry * .028f))
            // A little cheek lift warms a smile without turning every emotion
            // into a colour-changing alarm light.
            if (f.warmth > .01f) {
                drawOval(Brush.radialGradient(listOf(Color(0xFFE8BBA8).copy(alpha = f.warmth * .08f), Color.Transparent),
                    Offset(side * rx * .35f, ry * .83f), rx * .55f),
                    Offset(side * rx * .35f - rx * .55f, ry * .50f), Size(rx * 1.1f, ry * .66f))
            }
        }
    }

    private fun DrawScope.animalEar(side: Int, rx: Float, ry: Float, f: LumenFrame, color: Color, glow: Color) {
        val cat = f.cat.coerceIn(0f, 1f); val dog = f.dog.coerceIn(0f, 1f)
        if (cat < 0.005f && dog < 0.005f) return
        rotate(side * (8f - f.earLift + f.earAsymmetry) + f.earLag, Offset(side * rx * 0.65f, -ry * 0.5f)) {
            if (cat > 0.005f) {
                scale(-side.toFloat(), 1f, Offset.Zero) {
                ear.reset()
                ear.moveTo(-rx * 0.65f, -ry * 0.75f)
                ear.cubicTo(-rx * 0.85f, -ry * (0.9f + cat * 0.6f), -rx * 0.61f, -ry * (1f + cat * 0.82f), -rx * 0.35f, -ry * (1f + cat * 0.72f))
                ear.cubicTo(rx * 0.04f, -ry * (1f + cat * 0.52f), rx * 0.45f, -ry * 0.98f, rx * 0.58f, -ry * 0.82f)
                ear.close()
                drawEar(ear, rx, ry, color, glow, cat)
                scale(0.62f, 0.68f, Offset(-rx * 0.12f, -ry * 1.2f)) {
                    drawPath(ear, Brush.linearGradient(listOf(lerp(color, Color.White, 0.72f).copy(alpha = cat * 0.48f),
                        color.copy(alpha = cat * 0.08f)), Offset(-rx, -ry * 1.8f), Offset(rx * 0.5f, -ry * 0.6f)))
                }
                }
            }
            if (dog > 0.005f) {
                scale(side.toFloat(), 1f, Offset.Zero) {
                    ear.reset()
                    ear.moveTo(rx * 0.15f, -ry * 0.80f)
                    ear.cubicTo(rx * 0.57f, -ry * (1f + dog * 0.57f), rx * 1.41f, -ry * (1f + dog * 0.54f), rx * (1f + dog * 0.66f), -ry * 0.45f)
                    ear.cubicTo(rx * (1f + dog * 0.83f), ry * dog * 0.65f, rx * 0.98f, ry * dog * 0.77f, rx * 0.99f, -ry * 0.04f)
                    ear.cubicTo(rx * 0.99f, -ry * 0.50f, rx * 0.8f, -ry * 0.90f, rx * 0.15f, -ry * 0.80f)
                    ear.close()
                    drawEar(ear, rx, ry, color, glow, dog)
                }
            }
        }
    }

    private fun DrawScope.drawEar(path: Path, rx: Float, ry: Float, color: Color, glow: Color, alpha: Float) {
        drawPath(path, glow.copy(alpha = alpha * 0.09f), style = Stroke(rx * 0.18f))
        drawPath(path, Brush.linearGradient(listOf(lerp(color, Color.White, 0.25f).copy(alpha = alpha * 0.9f),
            lerp(color, Color.Black, 0.72f).copy(alpha = alpha * 0.96f)), Offset(-rx, -ry * 2f), Offset(rx, 0f)))
        drawPath(path, glow.copy(alpha = alpha * 0.6f), style = Stroke(rx * 0.016f))
    }
}
