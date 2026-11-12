package com.meowmba.shared.agent.runtime

/**
 * Named robot-base motions and their spoken phrases (the pure phrase→motion
 * half of `core/robot/RobotManager.Motion`, ported verbatim; the hardware
 * side stays platform-specific).
 *
 * Named rather than free-form so the same set can be driven by the voice
 * fast path, the language model, and the Settings UI without any of them
 * inventing a motion the hardware does not have.
 */
enum class RobotMotion(
    val phrases: List<String>,
    /**
     * True when the avatar owns this phrase in conversation. The face already
     * performs these, and performing them moves the body anyway (servos
     * follow the played clip). "shake your head" reaches the face, while
     * "spin around" reaches the motors directly, because no face can spin.
     */
    val faceOwnsPhrase: Boolean = false,
) {
    FORWARD(listOf("forward", "go forward", "drive forward", "come here", "move forward", "ahead")),
    BACK(listOf("back", "backward", "backwards", "go back", "reverse", "back up")),
    LEFT(listOf("left", "turn left", "go left")),
    RIGHT(listOf("right", "turn right", "go right")),
    SPIN(listOf("spin", "spin around", "spin in a circle", "turn around", "twirl", "circle")),
    STOP(listOf("stop", "halt", "stay", "freeze", "stop moving", "whoa")),
    LOOK_UP(listOf("look up", "head up", "chin up")),
    LOOK_DOWN(listOf("look down", "head down", "look at the floor")),
    LOOK_CENTER(listOf("look ahead", "center your head", "head center")),
    NOD(listOf("nod", "nod your head", "say yes"), faceOwnsPhrase = true),
    SHAKE_HEAD(listOf("shake your head", "shake head", "say no"), faceOwnsPhrase = true),
    WIGGLE(listOf("wiggle", "dance", "shimmy", "wiggle around"), faceOwnsPhrase = true),
    HEADLIGHT_ON(listOf(
        "headlights on", "headlight on", "lights on",
        "turn the headlights on", "turn on the headlights",
        "turn on the headlight", "turn on the lights", "light on",
    )),
    HEADLIGHT_OFF(listOf(
        "headlights off", "headlight off", "lights off",
        "turn the headlights off", "turn off the headlights",
        "turn off the headlight", "turn off the lights", "light off",
    )),
    ;

    companion object {
        /**
         * Resolve a spoken phrase to a motion. Longest phrase first, so
         * "turn off the lights" cannot be won by a shorter sibling.
         * [includeFaceOwned] is false for the router's fast path, where the
         * avatar has already had its turn.
         */
        fun fromPhrase(text: String, includeFaceOwned: Boolean = true): RobotMotion? {
            val t = text.lowercase()
            return entries
                .filter { includeFaceOwned || !it.faceOwnsPhrase }
                .flatMap { m -> m.phrases.map { it to m } }
                .sortedByDescending { it.first.length }
                .firstOrNull { (phrase, _) -> t.contains(phrase) }
                ?.second
        }
    }
}
