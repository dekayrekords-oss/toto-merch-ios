package com.meowmba.shared.ui.lumen

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.cos
import androidx.compose.ui.geometry.Offset
import kotlin.random.Random
import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.NervousSystem

/**
 * Gains at rest. Every drive-driven gain is applied as a *ratio* against this,
 * not as an absolute, for the reason spelled out on [NervousSystem.REST_BIAS].
 */
internal val REST_BIAS = com.meowmba.shared.behavior.NervousSystem.REST_BIAS

/**
 * Bounds on drive-driven gains.
 *
 * The floor matters more than the ceiling: a thoroughly cautious, exhausted
 * character has a vitality of zero, and without a floor its idle motion decays
 * to nothing — which reads as a frozen, broken avatar rather than a wary one.
 * Presence is the whole point of the drive layer, so the character always keeps
 * some movement and the drives modulate it rather than switching it off.
 */
internal const val DRIVE_GAIN_FLOOR = 0.35f
internal const val DRIVE_GAIN_CEILING = 1.25f

/** Saved appearance forms; explicit performances may temporarily override them. */
enum class AvatarAnimal(val key: String, val label: String) {
    NONE("none", "Eyes only"), CAT("cat", "Cat"), DOG("dog", "Dog"),
    SQUIRREL("squirrel", "Squirrel"), RACCOON("raccoon", "Raccoon"),
    AUSTRALIAN_SHEPHERD("australian_shepherd", "Australian shepherd"),
    OWL("owl", "Owl"), DUCK("duck", "Duck"),
    RABBIT("rabbit", "Rabbit"), PIG("pig", "Pig"), ALIEN("alien", "Alien"),
    ROBOT("robot", "Robot"), PANDA("panda", "Panda"), FRUIT_FLY("fruit_fly", "Fruit fly"),
    CLIPPY("clippy", "Clippy"), WIZARD("wizard", "The Wizard");
    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
        fun resolve(name: String): AvatarAnimal? = when (name.trim().lowercase()) {
            "none", "eyes", "eyes only", "just eyes", "lumen", "original", "normal" -> NONE
            "cat", "kitten", "kitty" -> CAT
            "dog", "puppy", "pup" -> DOG
            "squirrel" -> SQUIRREL
            "raccoon", "racoon" -> RACCOON
            "australian_shepherd", "australian shepherd", "australian shepherd dog", "aussie",
            "aussie dog", "shepherd", "shepherd dog" -> AUSTRALIAN_SHEPHERD
            "owl", "owlet" -> OWL
            "duck", "duckling", "mallard" -> DUCK
            "rabbit", "bunny", "bunny rabbit" -> RABBIT
            "pig", "piglet", "piggy" -> PIG
            "alien", "extraterrestrial" -> ALIEN
            "robot", "android", "robot face" -> ROBOT
            "panda", "panda bear", "giant panda" -> PANDA
            "fruit_fly", "fruit fly", "fruit-fly", "fruitfly", "drosophila" -> FRUIT_FLY
            "clippy", "clippit", "paperclip", "paper clip", "the paperclip" -> CLIPPY
            "wizard", "the wizard", "merlin", "merlin the wizard" -> WIZARD
            else -> null
        }
        fun performanceFor(name: String): AvatarAnimal? {
            LumenSignatureScenes.find(AvatarPerformanceCatalog.resolve(name)?.id)?.let { return it.animal }
            when (AvatarPerformanceCatalog.resolve(name)?.id) {
                "lost_mouse" -> return AUSTRALIAN_SHEPHERD
                "reading_realization" -> return OWL
                "hat_mishap" -> return DUCK
            }
            when (AvatarPerformanceCatalog.resolve(name)?.accent) {
                PerformanceAccent.YARN -> return CAT
                PerformanceAccent.BALL -> return DOG
                PerformanceAccent.ACORN -> return SQUIRREL
                PerformanceAccent.SNACK -> return RACCOON
                else -> Unit
            }
            val words = name.lowercase().split(Regex("[^a-z]+"))
            return when {
                "shepherd" in words || "aussie" in words -> AUSTRALIAN_SHEPHERD
                "owl" in words || "owlet" in words -> OWL
                "duck" in words || "duckling" in words || "mallard" in words -> DUCK
                "dog" in words || "puppy" in words -> DOG
                "cat" in words || "kitten" in words -> CAT
                "squirrel" in words -> SQUIRREL
                "raccoon" in words || "racoon" in words -> RACCOON
                "rabbit" in words || "bunny" in words -> RABBIT
                "pig" in words || "piglet" in words || "piggy" in words -> PIG
                "alien" in words -> ALIEN
                "robot" in words -> ROBOT
                "panda" in words -> PANDA
                else -> null
            }
        }
    }
}

/** Analytic critical damping: preserves velocity on retarget and cannot overshoot a resting step. */
internal class LumenSpring(initial: Float = 0f) {
    var value = initial; private set
    private var velocity = 0f
    fun step(target: Float, dt: Float, speed: Float, maxVelocity: Float = Float.POSITIVE_INFINITY,
        stopAtTarget: Boolean = false): Float {
        val goal = if (target.isFinite()) target else value
        val t = dt.coerceIn(0f, 0.05f)
        if (t == 0f) return value
        val offset = value - goal
        val carry = velocity + speed * offset
        val decay = exp(-speed * t)
        val next = goal + (offset + carry * t) * decay
        velocity = (velocity - speed * carry * t) * decay
        val limit = maxVelocity.coerceAtLeast(0f)
        val bounded = if (limit.isFinite()) next.coerceIn(value - limit * t, value + limit * t) else next
        // Error-adaptive gaze relaxes its spring near a fixation. Remove residual
        // velocity at contact so that relaxation cannot sail beyond a held target.
        if (stopAtTarget && (goal - value) * (goal - bounded) <= 0f) {
            value = goal; velocity = 0f
            return value
        }
        value = bounded
        velocity = velocity.coerceIn(-limit, limit)
        return value
    }
}

data class LumenInput(
    val gazeX: Float? = null, val gazeY: Float? = null,
    val cameraEnabled: Boolean = false,
    val emotion: CompanionEmotion = CompanionEmotion.IDLE,
    val interaction: InteractionState = InteractionState.IDLE,
    val animal: AvatarAnimal = AvatarAnimal.NONE,
    val openness: Float = 1f, val pupil: Float = 0.5f,
    val tilt: Float = 0f, val bounce: Float = 0f,
    val lean: Float = 0f, val lurch: Float = 0f,
    val happy: Boolean = false, val speaking: Float = 0f,
    val performing: Boolean = false,
    val gazeUp: Float = 0f, val earPerk: Float = 1f, val earDroop: Float = 0f,
    val lidTilt: Float = 0f,
    val winkLeft: Float = 0f, val presence: Float = 1f,
    val whiskerFlutter: Boolean = false,
    val driveWeight: Float = 0f, val brace: Float = 0f,
    val tailWag: Float = 0f,
    val sceneGazeX: Float? = null, val sceneGazeY: Float? = null,
    val sceneGazeWeight: Float = 0f,
    /** Authored pursuit can move the shared root; quiet/hat scenes leave it at zero. */
    val sceneTravelWeight: Float = 0f,
    /** Shared stage allowance for a worn hat, rather than moving only the prop. */
    val headwearWeight: Float = 0f,
    /** Existing filtered phone tilt, applied about the character, not the screen. */
    val screenRoll: Float = 0f,
    val affection: Boolean = false,
    val emotionPreview: CompanionEmotion? = null,
    val expressionPlan: ExpressionPlan? = null,
    /** Channels previously lost between the composed actor and this rig. */
    val lowerLid: Float = 0f,
    val earAsymmetry: Float = 0f,
    val performanceLean: Float = 0f,
    val performanceJaw: Float = 0f,
    /**
     * Continuous drives, as gains relative to [REST_BIAS]. Defaults to rest, so
     * anything that does not supply it animates exactly as before.
     */
    val bias: BehaviorBias = REST_BIAS,
    /** BANC's bounded wing-power population, retargeted visually only. */
    val wingDrive: Float = 0f,
)

data class LumenFrame(
    val gazeX: Float = 0f, val gazeY: Float = 0f,
    val headX: Float = 0f, val headY: Float = 0f, val roll: Float = 0f,
    val leftOpen: Float = 1f, val rightOpen: Float = 1f,
    val pupil: Float = 0.5f, val smile: Float = 0f, val concern: Float = 0f,
    val cat: Float = 0f, val dog: Float = 0f, val earLag: Float = 0f,
    val breath: Float = 0f, val engagement: Float = 0f,
    val earLift: Float = 0f,
    val lidTilt: Float = 0f,
    val mouthOpen: Float = 0f, val whiskers: Float = 0f, val presence: Float = 1f,
    val depth: Float = 0f,
    val driveWeight: Float = 0f,
    val form: AvatarFormTraits = AvatarFormTraits(),
    val tailSwing: Float = 0f,
    /** Whole-pair travel; separate from head perspective and shared by scene anchors. */
    val driftX: Float = 0f, val driftY: Float = 0f,
    val screenRoll: Float = 0f,
    val headwearWeight: Float = 0f,
    val lowerLidLeft: Float = 0f, val lowerLidRight: Float = 0f,
    val innerLift: Float = 0f, val tension: Float = 0f,
    val earAsymmetry: Float = 0f, val warmth: Float = 0f,
    val squash: Float = 0f,
    /** Shared active-clock wing rhythm for floating fruit-fly features. */
    val wingBeat: Float = 0f,
    /** Side-to-side head turn cue, used as a subtle drawn perspective. */
    val perspective: Float = 0f,
)

/**
 * One clock / one owner for all eye motion. No per-frame noise and no wandering
 * over a tracked person. Eyes acquire first; the head and ears follow with inertia.
 * [time] is the renderer's active (pause-aware) clock, NOT wall time.
 */
class LumenRig(seed: Int = 731) {
    private val random = Random(seed)
    private val gaze = LumenGazeMotion()
    private val idle = LumenIdleAttention(seed xor 0x51A7)
    private val body = LumenBodyMotion()
    private val acting = LumenActingDirector(seed xor 0x6A31)
    private val lowerLeft = LumenSpring(); private val lowerRight = LumenSpring()
    private val innerLift = LumenSpring(); private val tension = LumenSpring()
    private val earSplit = LumenSpring(); private val warmth = LumenSpring()
    private val squash = LumenSpring()
    private val hx = LumenSpring(); private val hy = LumenSpring()
    private val roll = LumenSpring(); private val ears = LumenSpring()
    private val left = LumenSpring(1f); private val right = LumenSpring(1f)
    private val pupil = LumenSpring(0.5f); private val smile = LumenSpring()
    private val concern = LumenSpring()
    private val formRig = AvatarFormRig(); private val tail = LumenSpring()
    private val energy = LumenSpring()
    private val earLift = LumenSpring()
    private val lidTilt = LumenSpring()
    private val mouth = LumenSpring(); private val whiskers = LumenSpring()
    private val presence = LumenSpring(1f)
    private val depth = LumenSpring()
    private val headwear = LumenSpring()
    private var lastTime: Float? = null
    private var nextBlink = 2.9f
    private var blinkStart = -10f
    private var doubleBlink = false
    private var frame = LumenFrame()

    fun step(time: Float, input: LumenInput): LumenFrame {
        if (!time.isFinite()) return frame
        val previous = lastTime
        lastTime = time
        val dt = if (previous == null) 0f else (time - previous).coerceIn(0f, 0.05f)
        // Repeated draws or a frozen screen must not advance expression or physiology.
        if (previous != null && dt == 0f) return frame
        val actor = acting.step(dt, if (input.emotionPreview != null) input.copy(
            emotion = input.emotionPreview, interaction = InteractionState.IDLE,
            performing = false, affection = false, expressionPlan = null,
        ) else input)
        val tracking = input.cameraEnabled && input.gazeX?.isFinite() == true && input.gazeY?.isFinite() == true
        val drive = input.driveWeight.clean().coerceIn(0f, 1f)
        // Mic kill sets MUTED, not IDLE. Privacy must stop capture, not turn the
        // visible character into a statue. Offline also permits quiet visual idle.
        val resting = (input.interaction == InteractionState.IDLE || input.interaction == InteractionState.MUTED ||
            input.interaction == InteractionState.OFFLINE) && input.speaking.clean() <= .02f
        // Driving contributes inertia to the same character; it must not stop
        // its attention score or reset its held screen position on every mode
        // transition. The shared stage mapping below reduces travel in a car.
        val idlePoint = idle.step(dt, !tracking && !input.performing && resting, input.bias)
        // Drive gains, every one a ratio against the resting character, so only a
        // real departure from rest changes the hand-tuned look.
        val followScale = (input.bias.followGain / REST_BIAS.followGain).coerceIn(DRIVE_GAIN_FLOOR, DRIVE_GAIN_CEILING)
        val earScale = (input.bias.earGain / REST_BIAS.earGain).coerceIn(DRIVE_GAIN_FLOOR, DRIVE_GAIN_CEILING)
        val blinkScale = (input.bias.blinkRateScale / REST_BIAS.blinkRateScale).coerceIn(0.5f, 2f)
        if (time >= nextBlink) {
            blinkStart = time
            doubleBlink = random.nextFloat() < 0.13f
            nextBlink = time + (3.1f + random.nextFloat() * 4.3f) / blinkScale
        }
        val blinkTime = time - blinkStart
        val blink = if (doubleBlink && blinkTime >= 0.31f) blinkAt(blinkTime - 0.31f) else blinkAt(blinkTime)
        var open = 1f
        if (input.performing) {
            val authored = input.openness.clean(1f).coerceIn(0f, 1.6f)
            // Compress the largest accents instead of clipping every surprise to
            // the same expression; keep the glass globe within its ear silhouette.
            open = if (authored <= 1.18f) authored else 1.18f + (authored - 1.18f) * 0.3f
        }
        val bracing = input.brace.clean().coerceIn(0f, 1f) * drive
        // Brief alert eyes, not a new speech turn or an unrelated performance.
        open = (open + bracing * 0.22f).coerceAtMost(1.34f)
        val actorWeight = if (input.performing) 0f else 1f
        // An authored prop is an explicit gaze target, independent of camera
        // permission. Blend back to live attention without resetting the spring.
        val sceneWeight = if (input.performing && input.sceneGazeX?.isFinite() == true &&
            input.sceneGazeY?.isFinite() == true) input.sceneGazeWeight.clean().coerceIn(0f, 1f) else 0f
        val liveX = (if (tracking) input.gazeX!!.coerceIn(-1f, 1f) else idlePoint.x) + actor.gazeX * actorWeight
        val liveY = (if (tracking) input.gazeY!!.coerceIn(-1f, 1f) else idlePoint.y) - input.gazeUp.clean() * 0.5f + actor.gazeY * actorWeight
        val targetY = liveY * (1f - sceneWeight) + input.sceneGazeY.cleanNullable() * sceneWeight
        val aim = gaze.step(liveX * (1f - sceneWeight) + input.sceneGazeX.cleanNullable() * sceneWeight,
            targetY, dt, tracking && sceneWeight == 0f)
        val gx = aim.x; val gy = aim.y
        val sceneTravel = input.sceneTravelWeight.clean().coerceIn(0f, 1f) * sceneWeight
        val bodyTarget = when {
            input.performing -> LumenAttentionPoint(gx * sceneTravel, gy * sceneTravel * .35f)
            tracking -> LumenAttentionPoint(gx * .95f, gy * .95f)
            else -> LumenAttentionPoint(
                idle.bodyTarget.x * followScale,
                idle.bodyTarget.y * followScale,
            )
        }
        // Continuous low-amplitude weight shifts, not random per-frame noise. The
        // existing spring keeps these smooth; gaze and authored scenes stay dominant.
        // Driving amplitude is handled by the shared stage; retain the idle clock
        // and target here so entering drive mode cannot jump the body.
        val alive = if (resting && !input.performing) 1f else 0f
        val sway = (.018f * kotlin.math.sin(time * .63f) + .008f * kotlin.math.sin(time * .27f)) * alive
        val breathe = .014f * kotlin.math.sin(time * 1.15f) * alive
        val position = body.step(LumenAttentionPoint(bodyTarget.x + actor.lean * actorWeight + sway,
            bodyTarget.y - actor.lift * actorWeight * 2f + breathe), dt, tracking)
        val headX = hx.step((gx * 0.42f + input.lean.clean().coerceIn(-1f, 1f) * (0.24f + drive * 0.60f) +
            input.performanceLean.clean().coerceIn(-1f, 1f) * .45f + actor.lean * actorWeight).coerceIn(-1.1f, 1.1f), dt, 6.8f + drive * 3.2f)
        val headY = hy.step((gy * 0.3f + input.lurch.clean().coerceIn(-1f, 1f) * (0.12f + drive * 0.65f) -
            input.bounce.clean().coerceIn(-1f, 1f) * 0.35f - actor.lift * actorWeight * 2f).coerceIn(-1f, 1f), dt, 6.4f + drive * 3.6f)
        val tilt = roll.step((input.tilt.clean() * 0.65f + actor.roll * actorWeight + headX * 6f + input.lean.clean().coerceIn(-1f, 1f) * drive * 6f).coerceIn(-18f, 18f), dt, 7f + drive * 2f)
        val earLag = ears.step(tilt, dt, 4.8f)
        val level = if (input.interaction == InteractionState.LISTENING) 0.7f else input.speaking.clean().coerceIn(0f, 1f)
        val anatomy = formRig.step(AvatarFormProfiles.traits(input.animal), dt)
        frame = LumenFrame(
            gazeX = gx, gazeY = gy, headX = headX, headY = headY, roll = tilt,
            leftOpen = left.step((open + (actor.openLeft - 1f) * actorWeight * (1f - bracing)) *
                (1f - input.winkLeft.clean().coerceIn(0f, 1f)), dt, if (input.winkLeft > 0f) 22f else 10f) * blink,
            rightOpen = right.step(open + (actor.openRight - 1f) * actorWeight * (1f - bracing), dt, 10f) * blink,
            pupil = pupil.step((input.pupil.clean(0.5f) + bracing * 0.08f + (actor.pupil - .5f) * actorWeight).coerceIn(0.15f, 0.9f), dt, 5f),
            smile = smile.step(if (input.performing && input.happy) 1f else actor.smile * actorWeight, dt, 8f),
            concern = concern.step(actor.concern * actorWeight, dt, 8f),
            cat = anatomy.pointedEars, dog = anatomy.floppyEars,
            earLag = earLag - tilt,
            breath = sin(time * 1.58f) * 0.004f + sin(time * 0.71f) * 0.0015f,
            engagement = energy.step(level, dt, 8f),
            earLift = earLift.step((((input.earPerk.clean(1f) - 1f) * 10f) * earScale - input.earDroop.clean() * 0.5f - bracing * 7f + actor.earLift * actorWeight).coerceIn(-15f, 15f), dt, 6f),
            lidTilt = lidTilt.step(input.lidTilt.clean().coerceIn(-16f, 16f), dt, 10f),
            mouthOpen = mouth.step(maxOf(input.speaking.clean(), input.performanceJaw.clean()).coerceIn(0f, 1f), dt, 20f),
            whiskers = whiskers.step(if (input.whiskerFlutter) 1f else 0f, dt, 7f) * sin(time * 7.2f),
            presence = presence.step(input.presence.clean(1f).coerceIn(0f, 1f), dt, 12f),
            depth = depth.step(input.lurch.clean().coerceIn(-1f, 1f) * drive, dt, 10f),
            driveWeight = drive,
            form = anatomy,
            tailSwing = tail.step((-headX * 0.16f + input.tailWag.clean().coerceIn(-1f, 1f) * 0.5f +
                sin(time * 0.85f) * 0.035f).coerceIn(-0.7f, 0.7f), dt, 4.5f),
            driftX = position.x, driftY = position.y,
            screenRoll = input.screenRoll.clean().coerceIn(-14f, 14f),
            headwearWeight = headwear.step(input.headwearWeight.clean().coerceIn(0f, 1f), dt, 12f).coerceIn(0f, 1f),
            lowerLidLeft = lowerLeft.step((actor.lowerLeft * actorWeight + input.lowerLid.clean()).coerceIn(0f, .65f), dt, 12f),
            lowerLidRight = lowerRight.step((actor.lowerRight * actorWeight + input.lowerLid.clean()).coerceIn(0f, .65f), dt, 12f),
            innerLift = innerLift.step(actor.innerLift * actorWeight, dt, 11f),
            tension = tension.step(actor.tension * actorWeight, dt, 10f),
            warmth = warmth.step(actor.warmth * actorWeight, dt, 5f),
            earAsymmetry = earSplit.step((actor.earSplit * actorWeight + input.earAsymmetry.clean()).coerceIn(-12f, 12f), dt, 6f),
            squash = squash.step(actor.squash * actorWeight, dt, 16f),
            wingBeat = sin(time * (12f + input.bias.arousal.coerceIn(0f, 1f) * 12f + input.wingDrive.coerceIn(0f, 1f) * 16f)) * .5f + .5f,
            perspective = (headX * .62f + gx * .18f).coerceIn(-1f, 1f),
        )
        return frame
    }

    companion object {
        internal fun blinkAt(t: Float): Float = when {
            t < 0f -> 1f
            t < 0.075f -> 1f - ease(t / 0.075f)
            t < 0.105f -> 0f
            t < 0.255f -> ease((t - 0.105f) / 0.15f)
            else -> 1f
        }
        private fun ease(t: Float) = t * t * (3f - 2f * t)
        private fun Float.clean(fallback: Float = 0f) = if (isFinite()) this else fallback
        private fun Float?.cleanNullable(): Float = if (this?.isFinite() == true) coerceIn(-1f, 1f) else 0f
    }
}

/** Shared draw/hit-test geometry. Costumes make room gradually, not in a one-frame resize. */
internal fun lumenUnit(width: Float, height: Float, frame: LumenFrame): Float {
    val portrait = ((height / width.coerceAtLeast(1f) - 1f) / .4f).coerceIn(0f, 1f)
    val costume = maxOf(frame.cat, frame.dog, frame.form.costume).coerceIn(0f, 1f)
    val enlargement = 1.08f + portrait * .18f
    val baseline = minOf(width * (.74f + frame.driveWeight.coerceIn(0f, 1f) * .20f),
        height * (1f + frame.driveWeight.coerceIn(0f, 1f) * .12f)) *
        (1f - maxOf(frame.cat, frame.dog, frame.form.costume).coerceIn(0f, 1f) * .14f) *
        (1f + frame.depth.coerceIn(-1f, 1f) * .055f) *
        (1f - frame.driveWeight.coerceIn(0f, 1f) * .12f) *
        (1f - frame.headwearWeight.coerceIn(0f, 1f) * .12f)
    val desired = baseline * enlargement
    val e = lumenEnvelope(frame)
    val margin = minOf(width, height) * .04f
    val yOffset = frame.headY * .07f + frame.breath
    val floor = if (height > width * 1.2f) height * .76f else height * .84f
    // Reserve actual travel before enlarging animal features. The established
    // eyes-only footprint remains intact, including its driving/idle motion.
    // Blend the fit with the same anatomy spring so changing forms never snaps.
    val fitted = minOf(desired,
        (width * .5f - margin - width * .065f) / (e.x + abs(frame.headX) * .09f),
        (height * .45f - margin) / (e.top - yOffset).coerceAtLeast(.1f),
        (floor - height * .45f) / (e.bottom + yOffset).coerceAtLeast(.1f)).coerceAtLeast(1f)
    return baseline + (fitted - baseline) * costume
}

internal data class LumenEnvelope(val x: Float, val top: Float, val bottom: Float)

internal fun lumenEnvelope(frame: LumenFrame): LumenEnvelope {
    val costume = maxOf(frame.cat, frame.dog, frame.form.costume).coerceIn(0f, 1f)
    val halfWidth = (.41f + frame.dog * .12f + frame.form.eyeMask * .045f +
        frame.form.pigSkin * .06f + frame.form.robotShell * .05f + frame.form.fruitFly * .20f + frame.form.clippy * .09f + frame.form.wizard * .12f +
        maxOf(frame.form.pandaFur, frame.form.rabbitFur) * .025f) * (1f + abs(frame.headX) * .10f)
    val top = maxOf(.27f + costume * .15f + frame.form.rabbitFur * .25f + frame.form.robotShell * .05f + frame.form.wizard * .18f,
        .45f * frame.headwearWeight.coerceIn(0f, 1f))
    val bottom = .28f + maxOf(frame.form.bushyTail, frame.form.ringTail) * .10f +
        maxOf(frame.form.rabbitFur, frame.form.pigSkin, frame.form.alienSkin, frame.form.pandaFur) * .06f +
        frame.form.robotShell * .025f + frame.form.fruitFly * .07f +
        frame.form.clippy * .24f + frame.form.wizard * .24f
    val angle = lumenRoll(frame) * kotlin.math.PI.toFloat() / 180f
    val c = abs(cos(angle)); val s = abs(sin(angle)); val stretch = lumenStretch(frame)
    return LumenEnvelope((halfWidth * c + maxOf(top, bottom) * s) / stretch,
        (top * c + halfWidth * s) * stretch, (bottom * c + halfWidth * s) * stretch)
}

/**
 * One root for paint, prop anchors and hit testing. Extra free-space travel is
 * mapped into the available stage, not a tiny fraction of eye size. Normalize
 * into the safe room before applying the position, rather than hitting a hard
 * clamp halfway through a move. The rotated anatomy envelope keeps ears/tails in
 * frame; portrait travel stays above the lower controls. Driving retains a
 * smaller amount of travel while the existing lean/lurch springs add inertia.
 */
internal fun lumenCenter(width: Float, height: Float, frame: LumenFrame): Offset {
    val unit = lumenUnit(width, height, frame)
    val base = Offset(width * 0.5f + frame.headX * unit * 0.09f,
        height * 0.45f + (frame.headY * 0.07f + frame.breath) * unit)
    val envelope = lumenEnvelope(frame)
    val extentX = unit * envelope.x
    val extentTop = unit * envelope.top
    val extentBottom = unit * envelope.bottom
    val margin = minOf(width, height) * 0.04f
    // The old 1 - driving multiplier erased even observed face-follow motion.
    // Retain attention at full drive weight, without a second root clock or
    // bypassing the actual free space left by leaning/rotated anatomy.
    val extra = 1f - frame.driveWeight.coerceIn(0f, 1f) * .35f
    fun travel(value: Float, scale: Float, before: Float, after: Float): Float {
        val direction = (if (value.isFinite()) value else 0f).coerceIn(-1f, 1f)
        val room = (if (direction < 0f) before else after).coerceAtLeast(0f)
        return direction * minOf(scale, room) * extra
    }
    val floor = if (height > width * 1.2f) height * .76f else height * .84f
    return base + Offset(
        travel(frame.driftX, width * .22f, base.x - margin - extentX, width - margin - base.x - extentX),
        travel(frame.driftY, height * .20f, base.y - margin - extentTop, floor - base.y - extentBottom),
    )
}

internal fun lumenRoll(frame: LumenFrame): Float = frame.roll + frame.screenRoll

internal fun lumenStretch(frame: LumenFrame): Float = 1f + frame.squash.coerceIn(-.035f, .035f)

/** Screen-owned privacy position/scale, independent of form, gaze and driving. */
internal fun lumenPrivacyScale(width: Float, height: Float, density: Float): Float =
    density.coerceAtMost(minOf(width * .74f, height) / 150f)

internal fun lumenPrivacyCenter(width: Float, height: Float, scale: Float): Offset =
    Offset(maxOf(width * .14f, minOf(width, height) * .04f + 48f * scale), height * .90f)

/** Authored performance labels only, never a classifier of the person being observed. */
internal fun lumenPerformanceTilt(name: String?): Float {
    val words = name?.lowercase()?.split(Regex("[^a-z]+")) ?: return 0f
    return when {
        words.any { it in setOf("angry", "mad", "furious", "annoyed") } -> -12f
        words.any { it in setOf("sad", "wounded", "sorry", "tender") } -> 9f
        else -> 0f
    }
}
