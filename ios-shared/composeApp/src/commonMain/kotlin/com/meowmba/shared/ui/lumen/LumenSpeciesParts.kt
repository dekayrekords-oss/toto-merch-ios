package com.meowmba.shared.ui.lumen

import androidx.compose.ui.geometry.Offset
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original species-specific parts, in the shared rig's normalized space. Coats
 * and facial discs are behind the lenses; bills occupy the gap below them.
 * Every opacity is a spring channel, so form changes never replace the eyes or
 * start a second animation clock. Species markings retain a tint of user color.
 */
internal class LumenSpeciesParts {
    private val outline = Path()
    private val detail = Path()
    private val colorBlends = ColorLerpCache()

    /** Compose's color lerp, memoized: these blends repeat identically every frame. */
    private fun lerp(start: Color, stop: Color, fraction: Float): Color = colorBlends.lerp(start, stop, fraction)

    fun backdrop(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame,
        color: Color, glow: Color) = with(scope) {
        translate(center.x, center.y) {
            scale(unit, unit, Offset.Zero) {
                val shepherd = f.form.shepherdCoat
                if (shepherd > .005f) {
                    val merle = lerp(Color(0xFF8398AE), color, .22f)
                    val cream = lerp(Color(0xFFF0EEE5), color, .08f)
                    // Overlapping curved locks rather than a sawtooth collar.
                    for (side in intArrayOf(-1, 1)) scale(side.toFloat(), 1f, Offset.Zero) {
                        outline.reset(); outline.moveTo(.10f, .135f)
                        outline.cubicTo(.19f, .098f, .31f, .12f, .352f, .159f)
                        outline.cubicTo(.37f, .18f, .343f, .188f, .323f, .195f)
                        outline.cubicTo(.35f, .216f, .321f, .226f, .288f, .22f)
                        outline.cubicTo(.293f, .246f, .268f, .249f, .242f, .236f)
                        outline.cubicTo(.24f, .264f, .218f, .269f, .195f, .249f)
                        outline.cubicTo(.148f, .257f, .098f, .216f, .075f, .18f); outline.close()
                        plushSurface(outline, cream, merle, Offset(.19f, .148f), .155f, shepherd * .94f)
                        // Sparse flowing fur catches the light, without parallel ribs.
                        for (i in 0..3) {
                            val x = .158f + i * .043f
                            detail.reset(); detail.moveTo(x, .153f + i * .001f)
                            detail.cubicTo(x + .036f, .178f, x + .018f, .212f, x - .002f, .232f - i * .006f)
                            drawPath(detail, cream.copy(alpha = shepherd * .15f), style = Stroke(.009f, cap = StrokeCap.Round))
                            drawPath(detail, Color.White.copy(alpha = shepherd * .20f), style = Stroke(.0021f, cap = StrokeCap.Round))
                        }
                    }
                    // Breed-specific semi-flop ears, with a visible folded tip.
                    for (side in intArrayOf(-1, 1)) {
                        val pivot = Offset(side * .30f, -.18f)
                        rotate(side * (3f - f.earLift * .30f) + f.earLag * .40f + f.earAsymmetry.coerceIn(-8f, 8f) * .65f, pivot) {
                            // Unfold with the same channel that grows the stage's
                            // anatomy allowance; a half-morphed ear cannot clip.
                            scale(side.toFloat(), .40f + shepherd * .60f, Offset(0f, -.18f)) {
                                outline.reset(); outline.moveTo(.205f, -.15f)
                                outline.cubicTo(.198f, -.252f, .266f, -.346f, .315f, -.358f)
                                outline.cubicTo(.351f, -.363f, .391f, -.257f, .367f, -.197f)
                                outline.cubicTo(.35f, -.153f, .321f, -.139f, .291f, -.158f)
                                outline.cubicTo(.258f, -.132f, .226f, -.128f, .205f, -.15f); outline.close()
                                plushSurface(outline, merle, Color(0xFF263344), Offset(.282f, -.29f), .145f, shepherd)
                                detail.reset(); detail.moveTo(.258f, -.26f)
                                detail.cubicTo(.28f, -.287f, .326f, -.287f, .348f, -.264f)
                                detail.cubicTo(.363f, -.245f, .345f, -.211f, .318f, -.20f)
                                detail.cubicTo(.292f, -.194f, .278f, -.233f, .258f, -.26f); detail.close()
                                plushSurface(detail, lerp(color, Color(0xFF91767C), .68f), Color(0xFF363443),
                                    Offset(.301f, -.26f), .072f, shepherd * .93f)
                                detail.reset(); detail.moveTo(.255f, -.262f)
                                detail.cubicTo(.28f, -.282f, .323f, -.281f, .347f, -.263f)
                                drawPath(detail, cream.copy(alpha = shepherd * .23f), style = Stroke(.003f, cap = StrokeCap.Round))
                            }
                        }
                    }
                    // Organic asymmetric blaze: soft fur transitions, no rigid divider.
                    outline.reset(); outline.moveTo(-.008f, -.256f)
                    outline.cubicTo(-.037f, -.267f, -.040f, -.233f, -.027f, -.197f)
                    outline.cubicTo(-.016f, -.145f, -.039f, -.114f, -.028f, -.048f)
                    outline.cubicTo(-.032f, .055f, -.051f, .113f, -.039f, .148f)
                    outline.cubicTo(-.013f, .171f, .031f, .166f, .045f, .139f)
                    outline.cubicTo(.049f, .107f, .028f, .031f, .027f, -.055f)
                    outline.cubicTo(.023f, -.127f, .049f, -.191f, .026f, -.239f)
                    outline.quadraticBezierTo(.012f, -.261f, -.008f, -.256f); outline.close()
                    drawPath(outline, cream.copy(alpha = shepherd * .08f), style = Stroke(.012f, cap = StrokeCap.Round))
                    plushSurface(outline, cream, merle, Offset(-.012f, -.15f), .34f, shepherd * .76f)
                }
                val owl = f.form.owlFeathers
                if (owl > .005f) {
                    val feather = lerp(Color(0xFF9D8468), color, .34f)
                    for (side in intArrayOf(-1, 1)) {
                        val pivot = Offset(side * .275f, -.19f)
                        rotate(side * -f.earLift * .3f + f.earLag * .3f, pivot) {
                            scale(side.toFloat(), .40f + owl * .60f, Offset(0f, -.19f)) {
                                outline.reset(); outline.moveTo(.18f, -.20f)
                                outline.cubicTo(.208f, -.288f, .286f, -.339f, .309f, -.378f)
                                outline.cubicTo(.33f, -.35f, .325f, -.322f, .319f, -.302f)
                                outline.cubicTo(.334f, -.315f, .345f, -.338f, .349f, -.349f)
                                outline.cubicTo(.371f, -.289f, .342f, -.204f, .315f, -.164f)
                                outline.quadraticBezierTo(.257f, -.143f, .18f, -.20f); outline.close()
                                plushSurface(outline, feather, lerp(feather, Color.Black, .45f),
                                    Offset(.26f, -.278f), .155f, owl)
                                for (i in 0..2) {
                                    detail.reset(); detail.moveTo(.231f + i * .027f, -.197f)
                                    detail.cubicTo(.25f + i * .021f, -.244f, .27f + i * .018f, -.268f,
                                        .30f + i * .01f, -.326f + i * .013f)
                                    drawPath(detail, lerp(feather, Color.White, .55f).copy(alpha = owl * (.22f - i * .04f)),
                                        style = Stroke(.004f - i * .0006f, cap = StrokeCap.Round))
                                }
                            }
                        }
                    }
                }
                val duck = f.form.duckFeathers
                if (duck > .005f) {
                    val feather = lerp(Color(0xFFF5EFD9), color, .18f)
                    outline.reset(); outline.moveTo(-.322f, -.155f)
                    outline.cubicTo(-.328f, -.25f, -.179f, -.303f, -.045f, -.303f)
                    outline.cubicTo(.115f, -.31f, .306f, -.264f, .322f, -.159f)
                    outline.cubicTo(.209f, -.165f, .132f, -.22f, .011f, -.196f)
                    outline.cubicTo(-.10f, -.176f, -.237f, -.175f, -.322f, -.155f); outline.close()
                    plushSurface(outline, feather, lerp(feather, color, .65f), Offset(-.071f, -.269f), .37f, duck * .85f)
                    // Soft overlapping crown locks, with tapered curved ends.
                    for (i in 0..2) {
                        val x = -.045f + i * .031f
                        detail.reset(); detail.moveTo(x, -.275f)
                        detail.cubicTo(x - .058f, -.296f, x - .049f, -.346f, x + .003f, -.353f + i * .014f)
                        detail.cubicTo(x - .013f, -.326f, x + .037f, -.319f, x + .032f, -.286f)
                        detail.quadraticBezierTo(x + .014f, -.27f, x, -.275f); detail.close()
                        plushSurface(detail, feather, lerp(feather, color, .55f), Offset(x, -.321f), .075f, duck * (.84f - i * .08f))
                    }
                }
            }
        }
    }

    /** Called in each eye's existing translated/perspective-scaled coordinates. */
    fun eyeSurround(scope: DrawScope, side: Int, unit: Float, f: LumenFrame, color: Color) = with(scope) {
        scale(unit, unit, Offset.Zero) {
            val shepherd = f.form.shepherdCoat
            if (shepherd > .005f) {
                val merle = lerp(Color(0xFF8D9FB2), color, .20f)
                outline.reset(); outline.moveTo(-.168f, -.077f)
                outline.cubicTo(-.189f, -.169f, -.122f, -.252f, -.026f, -.252f)
                outline.cubicTo(.086f, -.265f, .179f, -.187f, .182f, -.061f)
                outline.cubicTo(.193f, .063f, .135f, .191f, .035f, .214f)
                outline.cubicTo(-.085f, .244f, -.173f, .161f, -.178f, .062f)
                outline.quadraticBezierTo(-.186f, -.018f, -.168f, -.077f); outline.close()
                plushSurface(outline, merle, lerp(merle, Color.Black, .31f), Offset(-.085f, -.161f), .38f, shepherd * .94f)
                clipPath(outline) {
                    // Mottling sits in the coat rather than opaque sticker dots.
                    for (i in 0..5) {
                        val x = if (side < 0) -.155f + (i % 3) * .105f else -.14f + (i % 2) * .265f
                        val y = -.22f + (i / 3) * .36f + (if (side > 0) i % 3 * .017f else 0f)
                        detail.reset(); detail.moveTo(x, y)
                        detail.cubicTo(x - .038f, y - .025f, x + .024f, y - .039f, x + .047f, y - .018f)
                        detail.cubicTo(x + .065f, y - .025f, x + .081f, y + .007f, x + .060f, y + .025f)
                        detail.cubicTo(x + .037f, y + .062f, x - .024f, y + .031f, x, y); detail.close()
                        drawPath(detail, Color(0xFF304154).copy(alpha = shepherd * .11f), style = Stroke(.011f, cap = StrokeCap.Round))
                        drawPath(detail, Brush.radialGradient(listOf(Color(0xFF304052).copy(alpha = shepherd * .67f),
                            lerp(merle, Color(0xFF304052), .44f).copy(alpha = shepherd * .32f)), Offset(x + .02f, y + .01f), .082f))
                    }
                }
                val brow = -.231f - f.concern * .009f + f.smile * .006f
                detail.reset(); detail.moveTo(-.068f + side * .017f, brow + .011f)
                detail.cubicTo(-.039f, brow - .015f, .014f, brow - .016f - side * f.concern * .008f, .063f + side * .015f, brow + .009f)
                drawPath(detail, lerp(Color(0xFFCE9C6C), color, .12f).copy(alpha = shepherd * .22f), style = Stroke(.032f, cap = StrokeCap.Round))
                drawPath(detail, lerp(Color(0xFFE3B580), color, .12f).copy(alpha = shepherd * .81f), style = Stroke(.021f, cap = StrokeCap.Round))
            }
            val owl = f.form.owlFeathers
            if (owl > .005f) {
                val feather = lerp(Color(0xFFB39A78), color, .30f)
                val cream = lerp(Color(0xFFF2E6C9), color, .13f)
                // Facial discs have soft, asymmetrical feather volume, not a
                // complete bright circular rim with evenly spaced clock ticks.
                outline.reset(); outline.moveTo(-.179f, -.074f)
                outline.cubicTo(-.176f, -.181f, -.112f, -.239f, -.017f, -.237f)
                outline.cubicTo(.103f, -.245f, .177f, -.173f, .182f, -.058f)
                outline.cubicTo(.193f, .060f, .155f, .175f, .063f, .219f)
                outline.cubicTo(-.02f, .262f, -.149f, .213f, -.174f, .093f)
                outline.quadraticBezierTo(-.184f, .015f, -.179f, -.074f); outline.close()
                plushSurface(outline, lerp(cream, feather, .34f), lerp(feather, Color.Black, .18f),
                    Offset(-.064f, -.121f), .36f, owl * .93f)
                clipPath(outline) {
                    for (i in 0..9) {
                        val a = .11f + i * kotlin.math.PI.toFloat() / 5f
                        val start = Offset(cos(a) * .145f, sin(a) * .177f)
                        val middle = Offset(cos(a + .11f * side) * .175f, sin(a + .11f * side) * .215f)
                        val end = Offset(cos(a + .20f * side) * .183f, sin(a + .20f * side) * .235f)
                        detail.reset(); detail.moveTo(start.x, start.y)
                        detail.quadraticBezierTo(middle.x, middle.y, end.x, end.y)
                        drawPath(detail, cream.copy(alpha = owl * (.15f + (i % 3) * .045f)), style = Stroke(.009f, cap = StrokeCap.Round))
                    }
                }
            }
            val duck = f.form.duckFeathers
            if (duck > .005f) {
                val feather = lerp(Color(0xFFF8F1DB), color, .18f)
                outline.reset(); outline.moveTo(-.177f, -.053f)
                outline.cubicTo(-.187f, -.156f, -.101f, -.231f, -.002f, -.226f)
                outline.cubicTo(.115f, -.231f, .182f, -.136f, .182f, -.027f)
                outline.cubicTo(.193f, .109f, .128f, .221f, .015f, .222f)
                outline.cubicTo(-.116f, .232f, -.185f, .137f, -.177f, -.053f); outline.close()
                plushSurface(outline, feather, lerp(feather, color, .58f), Offset(-.065f, -.13f), .36f, duck * .91f)
                // Downy cheek highlights curve under the smile; no whisker ticks.
                for (i in 0..1) {
                    detail.reset(); detail.moveTo(side * .153f, .095f + i * .026f)
                    detail.cubicTo(side * .151f, .157f, side * .101f, .189f + i * .011f, side * .068f, .185f + i * .021f)
                    drawPath(detail, Color.White.copy(alpha = duck * .17f), style = Stroke(.007f, cap = StrokeCap.Round))
                }
            }
        }
    }

    fun beak(scope: DrawScope, center: Offset, unit: Float, f: LumenFrame, color: Color) = with(scope) {
        translate(center.x + f.gazeX * unit * .006f, center.y) {
            scale(unit, unit, Offset.Zero) {
                val owl = f.form.owlFeathers
                if (owl > .005f) {
                    val horn = lerp(Color(0xFFDCAA55), color, .14f)
                    outline.reset(); outline.moveTo(0f, .112f)
                    outline.cubicTo(-.06f, .136f, -.047f, .185f, -.012f, .216f)
                    outline.quadraticBezierTo(.015f, .25f, .035f, .219f)
                    outline.quadraticBezierTo(.009f, .226f, .02f, .201f)
                    outline.cubicTo(.067f, .165f, .045f, .122f, 0f, .112f); outline.close()
                    plushSurface(outline, horn, lerp(horn, Color.Black, .48f), Offset(-.019f, .144f), .101f, owl)
                    detail.reset(); detail.moveTo(.007f, .154f)
                    detail.cubicTo(.008f, .18f, .024f, .193f, .021f, .204f + f.mouthOpen * .013f)
                    drawPath(detail, Color(0xFF514233).copy(alpha = owl * .65f), style = Stroke(.0027f, cap = StrokeCap.Round))
                }
                val duck = f.form.duckFeathers
                if (duck > .005f) {
                    val orange = lerp(Color(0xFFFFA541), color, .08f)
                    val opening = f.mouthOpen * .014f
                    val lip = .207f - f.smile * .010f + f.concern * .006f
                    drawOval(Color(0xFF7C3C20).copy(alpha = duck), Offset(-.123f, .177f), Size(.246f, .057f + opening))
                    outline.reset(); outline.moveTo(0f, .135f)
                    outline.cubicTo(-.075f, .132f, -.146f, .175f, -.14f, lip)
                    outline.cubicTo(-.137f, .247f, .135f, .247f, .14f, lip)
                    outline.cubicTo(.145f, .178f, .079f, .132f, 0f, .135f); outline.close()
                    plushSurface(outline, orange, Color(0xFFCE6C25), Offset(-.039f, .159f), .19f, duck)
                    detail.reset(); detail.moveTo(-.124f, lip + .003f)
                    detail.cubicTo(-.069f, .232f + opening, .069f, .232f + opening, .124f, lip + .003f)
                    drawPath(detail, Color(0xFF965122).copy(alpha = duck * .72f), style = Stroke(.0034f, cap = StrokeCap.Round))
                    for (side in intArrayOf(-1, 1)) drawOval(Color(0xFF71452B).copy(alpha = duck * .8f),
                        Offset(side * .051f - .009f, .163f), Size(.018f, .009f))
                    drawOval(Brush.radialGradient(listOf(Color(0xFFFFF0C0).copy(alpha = duck * .31f),
                        Color.Transparent), Offset(-.039f, .165f), .063f), Offset(-.099f, .151f), Size(.122f, .027f))
                }
            }
        }
    }

    /** Broad diffuse light describes fur/feather volume without a metallic rim. */
    private fun DrawScope.plushSurface(path: Path, light: Color, shade: Color,
        lightAt: Offset, radius: Float, alpha: Float) {
        drawPath(path, Brush.radialGradient(
            0f to lerp(light, Color.White, .16f).copy(alpha = alpha),
            .36f to light.copy(alpha = alpha),
            .73f to lerp(light, shade, .49f).copy(alpha = alpha * .97f),
            1f to shade.copy(alpha = alpha * .90f),
            center = lightAt, radius = radius,
        ))
    }
}
