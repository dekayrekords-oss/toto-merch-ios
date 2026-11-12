package com.meowmba.shared.ui.lumen

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Anatomy changes the aperture, never the blink/gaze/emotion clock. Values are
 * blended from the already damped form weights, including mid-morph reversals. */
internal data class LumenEyeShape(
    val width: Float = 1f,
    val height: Float = 1f,
    val almond: Float = 0f,
    val outerLift: Float = 0f,
    val pupilAspect: Float = 1f,
    val irisScale: Float = 1f,
    val pupilScale: Float = 1f,
    val mechanical: Float = 0f,
    val dark: Float = 0f,
)

internal fun lumenEyeShape(f: AvatarFormTraits): LumenEyeShape = LumenEyeShape(
    width = 1f + f.pointedEars * .08f - f.floppyEars * .06f - f.bushyTail * .05f +
        f.eyeMask * .02f - f.shepherdCoat * .03f + f.owlFeathers * .09f -
        f.duckFeathers * .12f - f.rabbitFur * .12f - f.pigSkin * .15f +
        f.alienSkin * .12f - f.robotShell * .03f - f.pandaFur * .08f + f.fruitFly * .10f - f.clippy * .02f - f.wizard * .07f,
    height = 1f - f.pointedEars * .27f - f.floppyEars * .04f + f.bushyTail * .10f -
        f.eyeMask * .20f - f.shepherdCoat * .08f - f.owlFeathers * .09f +
        f.duckFeathers * .04f + f.rabbitFur * .08f - f.pigSkin * .10f -
        f.alienSkin * .13f - f.robotShell * .18f - f.pandaFur * .08f + f.fruitFly * .06f + f.clippy * .13f - f.wizard * .12f,
    almond = f.pointedEars * .83f + f.eyeMask * .36f + f.shepherdCoat * .17f + f.alienSkin * .85f,
    outerLift = f.pointedEars * 10f + f.eyeMask * 4f + f.shepherdCoat * 3f +
        f.duckFeathers * 3f + f.alienSkin * 23f - f.rabbitFur * 4f - f.pandaFur * 5f,
    pupilAspect = 1f - f.pointedEars * .72f,
    irisScale = 1f + f.owlFeathers * .17f + f.alienSkin * .40f - f.pigSkin * .09f - f.clippy * .25f - f.wizard * .20f,
    pupilScale = 1f + f.floppyEars * .14f + f.bushyTail * .12f + f.rabbitFur * .23f +
        f.pandaFur * .20f + f.alienSkin * .50f - f.clippy * .18f - f.wizard * .10f,
    mechanical = f.robotShell,
    dark = f.alienSkin,
)

internal fun lumenEyeTilt(side: Int, frame: LumenFrame, shape: LumenEyeShape = lumenEyeShape(frame.form)): Float =
    side * (frame.smile * -2.2f + frame.innerLift * 25f + frame.lidTilt - shape.outerLift)

/** The default palette follows the character. An explicit eye-color choice
 * still applies to every form; morphs blend rather than switching swatches. */
internal fun lumenIrisColor(f: AvatarFormTraits, selected: Color): Color {
    if (selected != Color(AvatarColors.DEFAULT_EYE)) return selected
    var color = selected
    fun tint(value: Long, weight: Float) { if (weight > .0001f) color = lerp(color, Color(value), weight) }
    tint(0xFFB3CF48, f.pointedEars)
    tint(0xFFAD7B4D, f.floppyEars)
    tint(0xFFBF884B, f.bushyTail)
    tint(0xFFBC985C, f.eyeMask)
    tint(0xFF77B9D1, f.shepherdCoat)
    tint(0xFFE5C153, f.owlFeathers)
    tint(0xFFD3A25C, f.duckFeathers)
    tint(0xFFA87B68, f.rabbitFur)
    tint(0xFF73BADD, f.pigSkin)
    tint(0xFF66C4D4, f.robotShell)
    tint(0xFFA98D68, f.pandaFur)
    tint(0xFFC72F46, f.fruitFly)
    tint(0xFF545B6B, f.clippy)
    tint(0xFF68A8D6, f.wizard)
    return color
}
