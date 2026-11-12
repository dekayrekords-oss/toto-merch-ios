package com.meowmba.shared.neural

import com.meowmba.shared.behavior.SocialTarget

/**
 * Typed contracts between the senses, the reasoning layer and the neural
 * controller. Everything here is plain Kotlin with no platform imports so the
 * whole pipeline is testable on the host JVM — and, in this port, on the iOS
 * targets as well.
 *
 * Honesty notes carried from the master plan: a face is presence evidence, not
 * identity; one microphone gives no sound direction; affect values are
 * behavioural controls, not feelings.
 */

/** Which sense produced an event. Used for coalescing keys and diagnostics. */
enum class SensorySource { FACE, VISUAL_MOTION, VOICE, DEVICE_MOTION, INTERACTION, TOUCH, REASONING, SYSTEM }

/**
 * What an event reports.
 *
 * Continuous kinds are *state*: the latest value per (source, kind) wins and a
 * missing update past the freshness limit means the sensor went away. Transition
 * kinds are *impulses*: every one counts, in order, and the encoder turns them
 * into decaying features.
 */
enum class SensoryKind(val continuous: Boolean, val arity: Int, val defaultFreshnessMs: Long) {
    /** presence, confidence, proximity, offsetX, offsetY, gazeOnMe, count. */
    FACE_PRESENCE(true, 7, 900L),
    /** energy, handFollow. */
    VISUAL_MOTION(true, 2, 900L),
    /** activity, loudness. */
    VOICE_ACTIVITY(true, 2, 1_200L),
    /** magnitude. */
    DEVICE_MOTION(true, 1, 1_500L),

    FACE_ARRIVED(false, 1, 2_000L),
    FACE_LOST(false, 1, 2_000L),
    LOOMING(false, 1, 1_000L),
    WAVE(false, 1, 2_000L),
    SHAKE(false, 1, 2_000L),
    SPOKEN_TO(false, 1, 3_000L),
    BARGE_IN(false, 1, 3_000L),
    TASK_SUCCESS(false, 1, 5_000L),
    TASK_FAILURE(false, 1, 5_000L),
    PRAISE(false, 1, 5_000L),
    TOUCH(false, 1, 2_000L),
    LOUD_SOUND(false, 1, 1_000L),
    THREAT(false, 1, 2_000L),
    NOVELTY(false, 1, 2_000L),
    /** Signed, -1..1. Reply or user affect. */
    AFFECT(false, 1, 5_000L),
    /** Synthesised by the bus when a continuous channel stops updating. */
    SENSOR_LOST(false, 1, 2_000L),
}

/**
 * One observation. [values] has [SensoryKind.arity] entries; magnitudes are 0..1
 * except [SensoryKind.AFFECT] which is -1..1. Non-finite values are treated as
 * "no signal" by [sanitized] rather than propagating into the network.
 *
 * [timestampMs] is in the receiving controller's clock. Producers that do not
 * share that clock leave it [UNSTAMPED] and the controller stamps the event on
 * arrival; replay and tests stamp explicitly.
 */
class SensoryEvent(
    val kind: SensoryKind,
    val source: SensorySource,
    values: FloatArray,
    val confidence: Float = 1f,
    val timestampMs: Long = UNSTAMPED,
    val freshnessLimitMs: Long = kind.defaultFreshnessMs,
) {
    val values: FloatArray = values.copyOf()

    init {
        require(values.size == kind.arity) {
            "${kind.name} expects ${kind.arity} values, got ${values.size}"
        }
    }

    /** Magnitude of an impulse; the first value for continuous kinds. */
    val magnitude: Float get() = values[0]

    fun stamped(nowMs: Long): SensoryEvent =
        if (timestampMs != UNSTAMPED) this
        else SensoryEvent(kind, source, values, confidence, nowMs, freshnessLimitMs)

    /** Non-finite values become 0 and are clamped to their legal range. */
    fun sanitized(): SensoryEvent {
        val lo = if (kind == SensoryKind.AFFECT) -1f else 0f
        val clean = FloatArray(values.size) { i ->
            val v = values[i]
            if (!v.isFinite()) 0f else if (kind.continuous) v else v.coerceIn(lo, 1f)
        }
        val conf = if (confidence.isFinite()) confidence.coerceIn(0f, 1f) else 0f
        return SensoryEvent(kind, source, clean, conf, timestampMs, freshnessLimitMs)
    }

    fun isStale(nowMs: Long): Boolean =
        timestampMs != UNSTAMPED && nowMs - timestampMs > freshnessLimitMs

    override fun toString(): String =
        "SensoryEvent(${kind.name} from ${source.name} t=$timestampMs conf=$confidence values=${values.joinToString(",")})"

    companion object {
        const val UNSTAMPED = -1L

        fun impulse(
            kind: SensoryKind,
            magnitude: Float,
            source: SensorySource,
            confidence: Float = 1f,
            timestampMs: Long = UNSTAMPED,
        ): SensoryEvent {
            require(!kind.continuous) { "${kind.name} is a continuous kind" }
            return SensoryEvent(kind, source, floatArrayOf(magnitude), confidence, timestampMs)
        }
    }
}

/**
 * The reasoning layer's instruction to the controller. It carries a goal and a
 * target, never an animation, and it *expires*: if the reasoning layer stops
 * re-issuing it the character falls back to its own baseline instead of being
 * stuck attending to something that stopped mattering.
 */
data class IntentEnvelope(
    val target: SocialTarget,
    /** Desired approach drive, 0..1; null leaves approach to the controller. */
    val approach: Float? = null,
    val goal: String = "",
    val priority: Int = 0,
    val issuedAtMs: Long,
    val ttlMs: Long = DEFAULT_TTL_MS,
    val cancellationId: String? = null,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs - issuedAtMs > ttlMs

    /** Null when the envelope cannot be acted on safely. */
    fun validated(): IntentEnvelope? {
        if (ttlMs <= 0L || issuedAtMs < 0L) return null
        val a = approach?.let { if (it.isFinite()) it.coerceIn(0f, 1f) else return null }
        return if (a == approach) this else copy(approach = a)
    }

    companion object {
        /** Long enough to outlive a 200 ms re-issue cadence with margin. */
        const val DEFAULT_TTL_MS = 1_500L
    }
}

/**
 * The closed set of things the controller may propose. Deliberately small:
 * there is no privacy action and no robot action here, so nothing downstream
 * can be talked into them by a learned readout.
 */
enum class BehaviorAction { REST, ORIENT, INSPECT, STARTLE, SETTLE }

/** A proposal from the decoder to the arbiter. Expires so a stale one cannot linger. */
data class BehaviorProposal(
    val action: BehaviorAction,
    val target: SocialTarget,
    val intensity: Float,
    val confidence: Float,
    /** Gaze direction, -1..1, for [BehaviorAction.ORIENT]. */
    val dirX: Float = 0f,
    val dirY: Float = 0f,
    val issuedAtMs: Long,
    val ttlMs: Long = 600L,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs - issuedAtMs > ttlMs
}

enum class ActionResult { COMPLETED, INTERRUPTED, FAILED }

/** What the motor layer reports back so the arbiter can release or shorten an action. */
data class ActionFeedback(
    val action: BehaviorAction,
    val result: ActionResult,
    val atMs: Long,
    val detail: String = "",
)
