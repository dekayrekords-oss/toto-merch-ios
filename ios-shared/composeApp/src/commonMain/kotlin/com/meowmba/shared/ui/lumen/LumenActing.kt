package com.meowmba.shared.ui.lumen

import kotlin.random.Random

/**
 * Character acting, not a claim about a person's feelings or robot awareness.
 * Only observed interaction states and the companion's explicit expression feed
 * this score. It runs on the renderer's active clock, with no model/network work.
 */
internal data class LumenActingPose(
    val openLeft: Float = 1f, val openRight: Float = 1f,
    val lowerLeft: Float = 0f, val lowerRight: Float = 0f,
    val innerLift: Float = 0f, val tension: Float = 0f,
    val pupil: Float = .5f, val smile: Float = 0f, val concern: Float = 0f,
    val roll: Float = 0f, val lift: Float = 0f, val lean: Float = 0f,
    val earLift: Float = 0f, val earSplit: Float = 0f,
    val gazeX: Float = 0f, val gazeY: Float = 0f,
    val squash: Float = 0f, val warmth: Float = 0f,
)

/** Independent upper/lower lids make each expression readable without a caption. */
internal object LumenActingPoses {
    fun forEmotion(emotion: CompanionEmotion): LumenActingPose = when (emotion) {
        CompanionEmotion.IDLE -> LumenActingPose(openLeft = .94f, openRight = .97f, lowerLeft = .035f, lowerRight = .02f)
        CompanionEmotion.ATTENTIVE -> LumenActingPose(openLeft = 1.04f, openRight = 1.01f, pupil = .57f, earLift = 5f, lift = .025f)
        CompanionEmotion.CURIOUS -> LumenActingPose(openLeft = 1.10f, openRight = .84f, lowerRight = .06f,
            innerLift = .12f, pupil = .62f, roll = -5.5f, lean = .035f, earLift = 5f, earSplit = 4f)
        CompanionEmotion.HAPPY -> LumenActingPose(openLeft = .87f, openRight = .9f, lowerLeft = .31f, lowerRight = .28f,
            pupil = .62f, smile = .92f, lift = .065f, earLift = 7f, warmth = .55f)
        CompanionEmotion.PROUD -> LumenActingPose(openLeft = .72f, openRight = .79f, lowerLeft = .19f, lowerRight = .16f,
            pupil = .48f, smile = .65f, roll = -2f, lift = .07f, earLift = 4f, warmth = .3f)
        CompanionEmotion.ALERT -> LumenActingPose(openLeft = 1.14f, openRight = 1.12f, tension = .45f,
            pupil = .4f, lift = -.045f, earLift = 8f)
        CompanionEmotion.SLEEPY -> LumenActingPose(openLeft = .38f, openRight = .42f, lowerLeft = .12f, lowerRight = .10f,
            pupil = .44f, smile = .13f, roll = 3f, lift = -.07f, earLift = -9f, gazeY = .15f)
        CompanionEmotion.CONFUSED -> LumenActingPose(openLeft = .77f, openRight = 1.06f, lowerLeft = .12f,
            innerLift = .22f, pupil = .55f, concern = .55f, roll = 6f, earSplit = -7f, lean = -.03f)
        CompanionEmotion.UNDERSTANDING -> LumenActingPose(openLeft = .86f, openRight = .89f, lowerLeft = .12f, lowerRight = .1f,
            pupil = .57f, smile = .35f, lift = .015f, earLift = 3f, warmth = .18f)
        CompanionEmotion.CONCERNED -> LumenActingPose(openLeft = .82f, openRight = .88f, lowerLeft = .035f,
            innerLift = .40f, pupil = .67f, concern = .85f, roll = -2f, lift = -.025f, earLift = -5f)
        CompanionEmotion.APOLOGETIC -> LumenActingPose(openLeft = .68f, openRight = .74f, lowerLeft = .08f, lowerRight = .05f,
            innerLift = .48f, pupil = .62f, concern = .72f, roll = 3f, lift = -.065f, earLift = -8f, gazeY = .13f)
        CompanionEmotion.SURPRISED -> LumenActingPose(openLeft = 1.19f, openRight = 1.16f, tension = .12f,
            pupil = .36f, lift = -.035f, earLift = 10f, earSplit = 2f)
        CompanionEmotion.PLAYFUL -> LumenActingPose(openLeft = .91f, openRight = .67f, lowerLeft = .19f, lowerRight = .22f,
            pupil = .62f, smile = .75f, roll = -6f, lift = .03f, lean = -.055f, earLift = 6f, earSplit = 6f, warmth = .35f)
        CompanionEmotion.AFFECTIONATE -> LumenActingPose(openLeft = .66f, openRight = .7f, lowerLeft = .29f, lowerRight = .26f,
            innerLift = .08f, pupil = .73f, smile = .82f, roll = 4f, lift = -.01f, earLift = -3f, warmth = .8f)
        CompanionEmotion.DETERMINED -> LumenActingPose(openLeft = .78f, openRight = .80f, lowerLeft = .1f, lowerRight = .1f,
            innerLift = -.27f, tension = .7f, pupil = .36f, lean = .025f, earLift = 6f)
        CompanionEmotion.RELIEVED -> LumenActingPose(openLeft = .64f, openRight = .68f, lowerLeft = .15f, lowerRight = .12f,
            pupil = .54f, smile = .36f, roll = -2f, lift = -.045f, earLift = -4f, warmth = .25f)
    }
}

/**
 * Notice -> orient -> hold -> settle. Variation is selected once per beat, never
 * per frame. Listening gets an immediate eye response; its head follows after
 * 75 ms. Repeated identical state updates do not restart the beat. A long listen
 * has occasional small acknowledgements with a quiet interval, not a nod loop.
 * Springs in LumenRig own continuity when a beat is interrupted or replaced.
 */
internal class LumenActingDirector(seed: Int) {
    private val random = Random(seed)
    private var previousInteraction: InteractionState? = null
    private var previousEmotion: CompanionEmotion? = null
    private var previousAffection = false
    private var age = 0f
    private var variation = 0
    private var lastVariation = -1
    private var side = 1f
    private var intensity = 1f
    private var acknowledgementAt = 4f
    private var acknowledgementAge = 10f
    internal var beatCount = 0; private set
    internal var lastBeat = "rest"; private set

    fun step(dt: Float, input: LumenInput): LumenActingPose {
        val seconds = dt.takeIf { it.isFinite() }?.coerceIn(0f, .05f) ?: 0f
        if (seconds == 0f) return sample(input)
        if (previousInteraction != input.interaction || previousEmotion != input.emotion || previousAffection != input.affection) {
            age = 0f
            // Three different accents per common state, without an immediate repeat.
            variation = (lastVariation + 1 + random.nextInt(2)).mod(3)
            lastVariation = variation
            side = if (random.nextBoolean()) 1f else -1f
            intensity = .88f + random.nextFloat() * .18f
            acknowledgementAt = 3.7f + random.nextFloat() * 2.7f
            acknowledgementAge = 10f
            lastBeat = when {
                input.interaction == InteractionState.LISTENING && previousInteraction == InteractionState.SPEAKING -> "yield"
                input.interaction == InteractionState.LISTENING -> "listen"
                input.interaction == InteractionState.THINKING -> "consider"
                input.interaction == InteractionState.SPEAKING -> "respond"
                input.affection -> "affection"
                else -> input.emotion.name.lowercase()
            }
            previousInteraction = input.interaction
            previousEmotion = input.emotion
            previousAffection = input.affection
            beatCount++
        }
        age += seconds
        acknowledgementAge += seconds
        if (input.interaction == InteractionState.LISTENING && !input.performing && age >= acknowledgementAt) {
            acknowledgementAge = 0f
            acknowledgementAt = age + 4.2f + random.nextFloat() * 3.4f
        }
        return sample(input)
    }

    private fun sample(input: LumenInput): LumenActingPose {
        if (input.performing) return LumenActingPose()
        val authored = when (input.interaction) {
            InteractionState.LISTENING -> LumenActingPoses.forEmotion(CompanionEmotion.ATTENTIVE)
            InteractionState.THINKING -> LumenActingPose(openLeft = .82f, openRight = .94f, lowerLeft = .075f,
                innerLift = .09f, pupil = .48f, roll = -3f, earSplit = 3f)
            else -> LumenActingPoses.forEmotion(if (input.affection) CompanionEmotion.AFFECTIONATE else input.emotion)
        }
        val plan = input.expressionPlan?.takeIf { it.primary == input.emotion && !input.affection }
        val conversational = input.interaction != InteractionState.LISTENING && input.interaction != InteractionState.THINKING
        val resting = (input.interaction == InteractionState.IDLE || input.interaction == InteractionState.MUTED || input.interaction == InteractionState.OFFLINE)
        val settling = if (resting) 1f - ease((age - 8f) / 8f) else 1f
        val base = if (plan != null && conversational) {
            val mixed = authored.blend(LumenActingPoses.forEmotion(plan.secondary), plan.secondaryWeight)
            LumenActingPoses.forEmotion(CompanionEmotion.IDLE).blend(mixed, plan.intensity * settling)
        } else authored
        val strength = if (plan != null && conversational) plan.intensity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .82f else 1f
        val beat = lobe(age, .075f, .42f, 1.15f) * intensity * strength
        val anticipate = lobe(age, 0f, .09f, .18f) * strength
        val settle = lobe(age, .55f, .9f, 1.6f) * strength
        val nod = lobe(acknowledgementAge, .06f, .30f, .85f) * .028f
        val listening = input.interaction == InteractionState.LISTENING
        val thinking = input.interaction == InteractionState.THINKING
        val speaking = input.interaction == InteractionState.SPEAKING
        val joyous = input.emotion == CompanionEmotion.HAPPY || input.emotion == CompanionEmotion.PLAYFUL || input.emotion == CompanionEmotion.PROUD
        val surprised = input.emotion == CompanionEmotion.SURPRISED || input.emotion == CompanionEmotion.ALERT
        // During consideration a single glance breaks eye contact, then returns.
        // Never drift off an observed face during a person's reply.
        val thinkingGlance = if (thinking) lobe(age, .18f, .64f, 2.4f) else 0f
        val nodBeat = when {
            listening -> (if (variation == 0) -.035f else -.022f) * beat + nod
            speaking -> -.025f * beat + .01f * settle
            input.emotion == CompanionEmotion.UNDERSTANDING -> -.045f * beat + .018f * settle
            joyous -> .065f * beat - .022f * anticipate
            surprised -> -.055f * beat
            input.emotion == CompanionEmotion.RELIEVED -> -.035f * beat
            else -> 0f
        }
        return base.copy(
            openLeft = base.openLeft + (if (listening) .065f * beat else if (joyous) -.035f * beat else 0f),
            openRight = base.openRight + (if (listening) .045f * beat else 0f),
            roll = base.roll + side * beat * when { listening -> if (variation == 1) 2.2f else .75f; joyous -> 2.6f; else -> .7f },
            lift = base.lift + nodBeat,
            lean = base.lean + (if (variation == 2 && listening) side * .024f * beat else 0f),
            earLift = base.earLift + 2.5f * lobe(age, .14f, .50f, 1.35f) * strength,
            earSplit = base.earSplit + if (variation == 2) side * 2f * settle else 0f,
            gazeX = base.gazeX + thinkingGlance * side * .24f,
            gazeY = base.gazeY - thinkingGlance * .13f,
            squash = if (joyous) -.025f * anticipate + .018f * beat - .008f * settle else 0f,
        )
    }

    private fun lobe(t: Float, start: Float, peak: Float, end: Float): Float = when {
        t <= start || t >= end -> 0f
        t < peak -> ease((t - start) / (peak - start))
        else -> 1f - ease((t - peak) / (end - peak))
    }

    private fun ease(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * t * (10f + t * (-15f + 6f * t))
    }
}

/** Linear targets feed the existing damped rig; state changes cannot teleport a pose. */
internal fun LumenActingPose.blend(other: LumenActingPose, weight: Float): LumenActingPose {
    val t = weight.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    fun mix(a: Float, b: Float) = a + (b - a) * t
    return LumenActingPose(
        openLeft = mix(openLeft, other.openLeft),
        openRight = mix(openRight, other.openRight),
        lowerLeft = mix(lowerLeft, other.lowerLeft),
        lowerRight = mix(lowerRight, other.lowerRight),
        innerLift = mix(innerLift, other.innerLift),
        tension = mix(tension, other.tension),
        pupil = mix(pupil, other.pupil),
        smile = mix(smile, other.smile),
        concern = mix(concern, other.concern),
        roll = mix(roll, other.roll),
        lift = mix(lift, other.lift),
        lean = mix(lean, other.lean),
        earLift = mix(earLift, other.earLift),
        earSplit = mix(earSplit, other.earSplit),
        gazeX = mix(gazeX, other.gazeX),
        gazeY = mix(gazeY, other.gazeY),
        squash = mix(squash, other.squash),
        warmth = mix(warmth, other.warmth),
    )
}
