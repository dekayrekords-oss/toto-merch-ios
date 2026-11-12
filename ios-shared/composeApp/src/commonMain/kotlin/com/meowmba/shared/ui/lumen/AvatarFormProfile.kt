package com.meowmba.shared.ui.lumen

/**
 * Native, reusable anatomy channels. Adding an animal is a profile over these
 * parts, not a separate avatar/eye engine. New geometry still needs authored,
 * tested parts; the model cannot supply executable drawing code or asset URLs.
 */
data class AvatarFormTraits(
    val pointedEars: Float = 0f, val floppyEars: Float = 0f,
    val roundEars: Float = 0f, val earTufts: Float = 0f,
    val shortMuzzle: Float = 0f, val longMuzzle: Float = 0f,
    val whiskers: Float = 0f, val cheeks: Float = 0f,
    val eyeMask: Float = 0f, val bushyTail: Float = 0f, val ringTail: Float = 0f,
    val shepherdCoat: Float = 0f, val owlFeathers: Float = 0f, val duckFeathers: Float = 0f,
    val rabbitFur: Float = 0f, val pigSkin: Float = 0f, val alienSkin: Float = 0f,
    val robotShell: Float = 0f, val pandaFur: Float = 0f,
    val fruitFly: Float = 0f,
    val clippy: Float = 0f, val wizard: Float = 0f,
) {
    val costume: Float get() = (pointedEars + floppyEars + roundEars +
        shepherdCoat + owlFeathers + duckFeathers + rabbitFur + pigSkin +
        alienSkin + robotShell + pandaFur + fruitFly + clippy + wizard).coerceIn(0f, 1f)
}

object AvatarFormProfiles {
    private val profiles = mapOf(
        AvatarAnimal.NONE to AvatarFormTraits(),
        AvatarAnimal.CAT to AvatarFormTraits(pointedEars = 1f, shortMuzzle = 1f, whiskers = 1f),
        AvatarAnimal.DOG to AvatarFormTraits(floppyEars = 1f, longMuzzle = 1f),
        AvatarAnimal.SQUIRREL to AvatarFormTraits(roundEars = 1f, earTufts = 1f,
            shortMuzzle = 1f, whiskers = 0.45f, cheeks = 1f, bushyTail = 1f),
        AvatarAnimal.RACCOON to AvatarFormTraits(roundEars = 1f, shortMuzzle = 1f,
            whiskers = 0.65f, eyeMask = 1f, ringTail = 1f),
        AvatarAnimal.AUSTRALIAN_SHEPHERD to AvatarFormTraits(longMuzzle = 1f, shepherdCoat = 1f),
        AvatarAnimal.OWL to AvatarFormTraits(owlFeathers = 1f),
        AvatarAnimal.DUCK to AvatarFormTraits(duckFeathers = 1f),
        AvatarAnimal.RABBIT to AvatarFormTraits(rabbitFur = 1f),
        AvatarAnimal.PIG to AvatarFormTraits(pigSkin = 1f),
        AvatarAnimal.ALIEN to AvatarFormTraits(alienSkin = 1f),
        AvatarAnimal.ROBOT to AvatarFormTraits(robotShell = 1f),
        AvatarAnimal.PANDA to AvatarFormTraits(pandaFur = 1f),
        AvatarAnimal.FRUIT_FLY to AvatarFormTraits(fruitFly = 1f),
        AvatarAnimal.CLIPPY to AvatarFormTraits(clippy = 1f),
        AvatarAnimal.WIZARD to AvatarFormTraits(wizard = 1f),
    )
    fun traits(animal: AvatarAnimal): AvatarFormTraits = profiles.getValue(animal)
}

/** Morphs anatomy with the same active clock as the eyes, preserving velocity. */
internal class AvatarFormRig {
    private val channels = Array(22) { LumenSpring() }
    fun step(target: AvatarFormTraits, dt: Float): AvatarFormTraits {
        fun channel(index: Int, v: Float) = channels[index].step(v.coerceIn(0f, 1f), dt, 7f).coerceIn(0f, 1f)
        return AvatarFormTraits(
            channel(0, target.pointedEars), channel(1, target.floppyEars),
            channel(2, target.roundEars), channel(3, target.earTufts),
            channel(4, target.shortMuzzle), channel(5, target.longMuzzle),
            channel(6, target.whiskers), channel(7, target.cheeks),
            channel(8, target.eyeMask), channel(9, target.bushyTail), channel(10, target.ringTail),
            channel(11, target.shepherdCoat), channel(12, target.owlFeathers), channel(13, target.duckFeathers),
            channel(14, target.rabbitFur), channel(15, target.pigSkin), channel(16, target.alienSkin),
            channel(17, target.robotShell), channel(18, target.pandaFur),
            channel(19, target.fruitFly),
            channel(20, target.clippy), channel(21, target.wizard),
        )
    }
}
