package com.meowmba.shared.companion

/**
 * Avatar animation framework (ported from the Android app's
 * `ui/companion/AvatarAnimation.kt`, unmodified in behavior).
 *
 * A state-machine-driven controller that maps [CompanionStateCore] into a
 * declarative [AvatarPose], plus a named [CatAnimationRegistry] so animation
 * clips resolve by name instead of being hardcoded in the renderer.
 *
 * Pure Kotlin (no Compose) so the logic is unit-testable on any target.
 */

/** How the mouth should be drawn for the current pose. */
enum class MouthMode {
    IDLE,       // gentle closed "w" cat mouth with slow wobble
    HAPPY,      // raised happy arcs
    SPEAKING,   // animated open mouth synced to speech energy
    MUTED       // closed mouth with an explicit muted "X" mark
}

/**
 * Declarative, renderer-agnostic description of the cat's face for one frame.
 * The renderer reads this pose and eases every field toward the latest value,
 * which makes all expressions interruptible and blendable by construction.
 */
data class AvatarPose(
    /** Vertical eye openness multiplier. 1.0 = neutral, 0.0 = closed, >1 = wide. */
    val eyeOpen: Float = 1.0f,
    /** Pupil width as a fraction of eye width (slit pupil). */
    val pupilSize: Float = 0.28f,
    /** Extra upward gaze bias (0 = none, 1 = fully looking up), used by THINKING. */
    val pupilGazeUp: Float = 0f,
    /** Head tilt in degrees (positive = curious tilt). */
    val headTiltDeg: Float = 0f,
    /** Ear perk multiplier. 1.0 = neutral, >1 = perked forward, <1 = flattened. */
    val earPerk: Float = 1.0f,
    /** Ear droop rotation in degrees (positive = relaxed/sleepy droop). */
    val earDroopDeg: Float = 0f,
    /** Draw eyes as happy "^^" arcs instead of open eyes. */
    val happyEyes: Boolean = false,
    /** Fast whisker flutter (talking / purring energy) instead of slow sway. */
    val whiskerFlutter: Boolean = false,
    /** Mouth rendering mode. */
    val mouth: MouthMode = MouthMode.IDLE,
    /** Show the mic-privacy "X" marks on the ears. */
    val micOffMark: Boolean = false,
    /** Show the camera-privacy "X" marks inside the pupils. */
    val cameraOffMark: Boolean = false,
    /** Halo glow color (ARGB hex) behind the head. */
    val glowColorHex: Long = 0xFF58A6FF,
    /** Whether the halo gently pulses (listening). */
    val glowPulse: Boolean = false,
    /** Dim the whole face (privacy-sensitive states). */
    val dimmed: Boolean = false,
    /** Tongue extension (0 = hidden, 1 = fully out for licking). */
    val tongueOut: Float = 0f,
    /** Vertical body bounce (-1 = squish down, 1 = pop up). */
    val bodyBounce: Float = 0f,
    /** Sideways body shift (-1 = left, 1 = right), used for hard-turn leans. */
    val leanX: Float = 0f,
    /** Tail wag angle (-1 = left, 1 = right, 0 = center). */
    val tailWag: Float = 0f,
    /** Sparkle particle intensity (0 = none, 1 = full sparkle). */
    val sparkle: Float = 0f
)

/**
 * Maps [CompanionStateCore] (interaction state x emotion x privacy flags) to
 * an [AvatarPose]. Pure function: same input always produces the same pose.
 */
object CatAvatarStateMachine {

    fun resolvePose(state: CompanionStateCore): AvatarPose {
        val interaction = state.interactionState
        val emotion = state.emotion
        val cameraOn = state.isCameraEnabled
        val happy = emotion == CompanionEmotion.HAPPY || emotion == CompanionEmotion.PROUD

        // Driving reactions are additive animation: lean into turns, lurch on
        // hard braking, sparkle at highway speed. They never touch emotion or
        // interaction state, so the chat/mood pipeline is not fought.
        val reaction = state.driveReaction
        val maneuvering = reaction == DriveReaction.LEAN_LEFT ||
            reaction == DriveReaction.LEAN_RIGHT ||
            reaction == DriveReaction.LURCH

        val eyeOpen = when {
            !cameraOn -> 0.30f                       // camera privacy: eyes nearly closed
            emotion == CompanionEmotion.SLEEPY -> 0.15f
            maneuvering -> 1.25f                     // wide-eyed into the maneuver
            interaction == InteractionState.LISTENING -> 1.15f
            interaction == InteractionState.THINKING -> 0.72f
            emotion == CompanionEmotion.CURIOUS -> 1.15f
            emotion == CompanionEmotion.ALERT -> 1.15f
            happy -> 0.9f
            else -> 1.0f
        }

        val pupilSize = when {
            !cameraOn -> 0.20f
            maneuvering -> 0.55f
            interaction == InteractionState.LISTENING -> 0.60f
            emotion == CompanionEmotion.CURIOUS -> 0.62f
            emotion == CompanionEmotion.ALERT -> 0.50f
            emotion == CompanionEmotion.SLEEPY -> 0.22f
            happy -> 0.45f
            else -> 0.28f
        }

        val earPerk = when {
            interaction == InteractionState.LISTENING -> 1.18f   // ears rotate toward the voice
            maneuvering -> 1.22f
            emotion == CompanionEmotion.ALERT -> 1.20f
            emotion == CompanionEmotion.CURIOUS -> 1.15f
            emotion == CompanionEmotion.SLEEPY -> 0.55f
            else -> 1.0f
        }

        val earDroop = when {
            emotion == CompanionEmotion.SLEEPY -> 24f
            happy -> 10f                                          // relaxed, content ears
            else -> 0f
        }

        val headTilt = when (emotion) {
            CompanionEmotion.CURIOUS -> 7f
            CompanionEmotion.CONFUSED -> 9f
            else -> 0f
        }

        val mouth = when {
            state.isMuted -> MouthMode.MUTED
            interaction == InteractionState.SPEAKING -> MouthMode.SPEAKING
            happy -> MouthMode.HAPPY
            else -> MouthMode.IDLE
        }

        val glowColor = when {
            state.errorMessage != null -> 0xFFF85149              // error red
            interaction == InteractionState.LISTENING -> 0xFF58A6FF
            interaction == InteractionState.THINKING -> 0xFFD29922
            interaction == InteractionState.SPEAKING -> 0xFF3FB950
            interaction == InteractionState.OFFLINE -> 0xFFF85149
            else -> 0xFF58A6FF
        }

        return AvatarPose(
            eyeOpen = eyeOpen,
            pupilSize = pupilSize,
            pupilGazeUp = if (interaction == InteractionState.THINKING) 0.55f else 0f,
            headTiltDeg = headTilt,
            earPerk = earPerk,
            earDroopDeg = earDroop,
            happyEyes = (happy && cameraOn) || reaction == DriveReaction.SPEED,
            whiskerFlutter = interaction == InteractionState.SPEAKING ||
                reaction == DriveReaction.SPEED,
            mouth = mouth,
            micOffMark = !state.isMicEnabled,
            cameraOffMark = !cameraOn,
            glowColorHex = glowColor,
            glowPulse = interaction == InteractionState.LISTENING,
            dimmed = !state.isMicEnabled || interaction == InteractionState.MUTED,
            bodyBounce = if (reaction == DriveReaction.LURCH) -0.7f else 0f,
            leanX = when (reaction) {
                DriveReaction.LEAN_LEFT -> -1f
                DriveReaction.LEAN_RIGHT -> 1f
                else -> 0f
            },
            tailWag = if (reaction == DriveReaction.SPEED) 0.5f else 0f,
            sparkle = if (reaction == DriveReaction.SPEED) 0.6f else 0f
        )
    }
}

/**
 * Named animation registry.
 *
 * Names follow the convention `<category>.<action>`. The renderer resolves
 * timing and motion parameters from here so new clips can be registered
 * without touching drawing code.
 */
object CatAnimationRegistry {

    /** Timing/motion parameters for one registered animation. */
    data class AnimSpec(
        val name: String,
        val durationMs: Int,
        val minDelayMs: Int = 0,      // for randomized idle behaviors
        val maxDelayMs: Int = 0,
        val from: Float = 0f,
        val to: Float = 1f
    )

    private val specs = mutableMapOf<String, AnimSpec>()

    init {
        // IDLE
        register(AnimSpec("idle.breathe", durationMs = 2600, from = 0.98f, to = 1.02f))
        register(AnimSpec("idle.blink", durationMs = 100, minDelayMs = 2200, maxDelayMs = 5200))
        register(AnimSpec("idle.ear_twitch", durationMs = 120, minDelayMs = 3000, maxDelayMs = 8000))
        register(AnimSpec("idle.gaze_drift_x", durationMs = 4800, from = -0.3f, to = 0.3f))
        register(AnimSpec("idle.gaze_drift_y", durationMs = 3400, from = -0.25f, to = 0.25f))
        register(AnimSpec("idle.whisker_sway", durationMs = 1500, from = -1f, to = 1f))
        register(AnimSpec("idle.mouth_wobble", durationMs = 1900, from = 0.88f, to = 1.12f))
        // SPEECH
        register(AnimSpec("speech.talk_cycle", durationMs = 160, from = 0.15f, to = 1f))
        // SYSTEM
        register(AnimSpec("system.halo_pulse", durationMs = 1000, from = 0.25f, to = 0.6f))
        register(AnimSpec("system.expression_blend", durationMs = 350))
    }

    fun register(spec: AnimSpec) {
        specs[spec.name] = spec
    }

    /** Look up a registered animation. Unknown names fall back to a 1s spec. */
    fun spec(name: String): AnimSpec =
        specs[name] ?: AnimSpec(name, durationMs = 1000)

    /** All currently registered animation names (for tooling/debug UI). */
    fun registeredNames(): Set<String> = specs.keys
}
