package com.meowmba.shared.neural

import com.meowmba.shared.behavior.SocialTarget
import kotlin.math.exp

/**
 * A named, ordered set of scalar features. The checkpoint carries the names so
 * the app, the tests and the offline trainer can refuse to run against a
 * network whose inputs or outputs mean something else.
 */
class FeatureLayout(names: List<String>) {
    val names: List<String> = names.toList()
    val size: Int get() = names.size
    private val index: Map<String, Int> = names.withIndex().associate { it.value to it.index }

    init {
        require(names.isNotEmpty()) { "layout needs at least one feature" }
        require(index.size == names.size) { "duplicate feature names in layout" }
    }

    fun index(name: String): Int =
        index[name] ?: throw IllegalArgumentException("unknown feature '$name'")

    fun indexOrNull(name: String): Int? = index[name]

    override fun equals(other: Any?): Boolean = other is FeatureLayout && other.names == names
    override fun hashCode(): Int = names.hashCode()
    override fun toString(): String = "FeatureLayout(${names.joinToString(",")})"
}

/** Canonical feature names shared by the encoder, the cores, the decoder and the trainer. */
object NeuralFeatures {
    // Continuous channels
    const val FACE_PRESENCE = "face_presence"
    const val FACE_CONFIDENCE = "face_confidence"
    const val FACE_PROXIMITY = "face_proximity"
    const val FACE_OFFSET_X = "face_offset_x"
    const val FACE_OFFSET_Y = "face_offset_y"
    const val FACE_GAZE_ON_ME = "face_gaze_on_me"
    const val FACE_COUNT = "face_count"
    const val VISUAL_MOTION = "visual_motion"
    const val HAND_FOLLOW = "hand_follow"
    const val VOICE_ACTIVITY = "voice_activity"
    const val VOICE_LOUDNESS = "voice_loudness"
    const val DEVICE_MOTION = "device_motion"

    // Decaying impulses
    const val FACE_ARRIVED = "face_arrived"
    const val FACE_LOST = "face_lost"
    const val LOOMING = "looming"
    const val WAVE = "wave"
    const val SHAKE = "shake"
    const val SPOKEN_TO = "spoken_to"
    const val BARGE_IN = "barge_in"
    const val TASK_SUCCESS = "task_success"
    const val TASK_FAILURE = "task_failure"
    const val PRAISE = "praise"
    const val TOUCH = "touch"
    const val LOUD_SOUND = "loud_sound"
    const val THREAT = "threat"
    const val NOVELTY = "novelty"
    const val AFFECT = "affect"
    const val SENSOR_LOST = "sensor_lost"

    // Reasoning-layer intent
    const val INTENT_APPROACH = "intent_approach"
    /** 1 when the envelope specified an approach value; lets "no instruction" differ from "approach 0". */
    const val INTENT_APPROACH_SET = "intent_approach_set"
    const val INTENT_USER = "intent_user"
    const val INTENT_OTHER = "intent_other"

    val CONTINUOUS_FEATURES: Map<SensoryKind, List<String>> = mapOf(
        SensoryKind.FACE_PRESENCE to listOf(
            FACE_PRESENCE, FACE_CONFIDENCE, FACE_PROXIMITY, FACE_OFFSET_X, FACE_OFFSET_Y, FACE_GAZE_ON_ME, FACE_COUNT,
        ),
        SensoryKind.VISUAL_MOTION to listOf(VISUAL_MOTION, HAND_FOLLOW),
        SensoryKind.VOICE_ACTIVITY to listOf(VOICE_ACTIVITY, VOICE_LOUDNESS),
        SensoryKind.DEVICE_MOTION to listOf(DEVICE_MOTION),
    )

    val IMPULSE_FEATURES: Map<SensoryKind, String> = mapOf(
        SensoryKind.FACE_ARRIVED to FACE_ARRIVED,
        SensoryKind.FACE_LOST to FACE_LOST,
        SensoryKind.LOOMING to LOOMING,
        SensoryKind.WAVE to WAVE,
        SensoryKind.SHAKE to SHAKE,
        SensoryKind.SPOKEN_TO to SPOKEN_TO,
        SensoryKind.BARGE_IN to BARGE_IN,
        SensoryKind.TASK_SUCCESS to TASK_SUCCESS,
        SensoryKind.TASK_FAILURE to TASK_FAILURE,
        SensoryKind.PRAISE to PRAISE,
        SensoryKind.TOUCH to TOUCH,
        SensoryKind.LOUD_SOUND to LOUD_SOUND,
        SensoryKind.THREAT to THREAT,
        SensoryKind.NOVELTY to NOVELTY,
        SensoryKind.AFFECT to AFFECT,
        SensoryKind.SENSOR_LOST to SENSOR_LOST,
    )

    val INPUT: FeatureLayout = FeatureLayout(
        CONTINUOUS_FEATURES.values.flatten() +
            IMPULSE_FEATURES.values +
            listOf(INTENT_APPROACH, INTENT_APPROACH_SET, INTENT_USER, INTENT_OTHER),
    )

    // Readout channels
    const val OUT_APPROACH = "approach"
    const val OUT_SOCIAL = "social"
    const val OUT_CURIOSITY = "curiosity"
    const val OUT_CAUTION = "caution"
    const val OUT_ENERGY = "energy"
    const val OUT_VALENCE = "valence"
    const val OUT_ORIENT_X = "orient_x"
    const val OUT_ORIENT_Y = "orient_y"
    const val OUT_ATTEND_USER = "attend_user"
    const val OUT_ATTEND_MOTION = "attend_motion"
    const val OUT_ATTEND_SOUND = "attend_sound"
    const val OUT_STARTLE = "startle"
    const val OUT_INSPECT = "inspect"

    val OUTPUT: FeatureLayout = FeatureLayout(
        listOf(
            OUT_APPROACH, OUT_SOCIAL, OUT_CURIOSITY, OUT_CAUTION, OUT_ENERGY, OUT_VALENCE,
            OUT_ORIENT_X, OUT_ORIENT_Y, OUT_ATTEND_USER, OUT_ATTEND_MOTION, OUT_ATTEND_SOUND,
            OUT_STARTLE, OUT_INSPECT,
        ),
    )
}

/**
 * Turns a [SensoryBatch] plus the current intent into the fixed input vector
 * the core consumes.
 *
 * Continuous channels are copied as-is (scaled by confidence) and read as 0
 * when absent or stale. Impulses are accumulated into features that decay
 * exponentially with [impulseTauMs]; because the decay is `exp(-dt/tau)` the
 * result depends on elapsed time, not on how many ticks it was chopped into.
 * Anything non-finite becomes 0 before it can reach the network.
 */
class FeatureEncoder(
    val layout: FeatureLayout = NeuralFeatures.INPUT,
    private val impulseTauMs: Float = 600f,
) {
    private val impulses = FloatArray(layout.size)
    private val impulseIndex: IntArray = NeuralFeatures.IMPULSE_FEATURES.entries
        .map { layout.index(it.value) }
        .toIntArray()
    private val affectIndex = layout.index(NeuralFeatures.AFFECT)
    private val intentApproach = layout.index(NeuralFeatures.INTENT_APPROACH)
    private val intentApproachSet = layout.index(NeuralFeatures.INTENT_APPROACH_SET)
    private val intentUser = layout.index(NeuralFeatures.INTENT_USER)
    private val intentOther = layout.index(NeuralFeatures.INTENT_OTHER)

    init {
        require(impulseTauMs > 0f)
    }

    /** Advance the impulse memory by [dtMs] and produce the input for this tick. */
    fun encode(batch: SensoryBatch, intent: IntentEnvelope?, dtMs: Float, nowMs: Long): FloatArray {
        val decay = if (dtMs > 0f) exp(-dtMs / impulseTauMs) else 1f
        for (i in impulseIndex) impulses[i] *= decay

        for (event in batch.transitions) {
            val name = NeuralFeatures.IMPULSE_FEATURES[event.kind] ?: continue
            val i = layout.indexOrNull(name) ?: continue
            val add = finiteOrZero(event.magnitude * event.confidence)
            val lo = if (i == affectIndex) -1f else 0f
            impulses[i] = (impulses[i] + add).coerceIn(lo, 1f)
        }

        val x = FloatArray(layout.size)
        for (i in impulseIndex) x[i] = impulses[i]

        for ((key, event) in batch.continuous) {
            val names = NeuralFeatures.CONTINUOUS_FEATURES[key.kind] ?: continue
            for ((k, name) in names.withIndex()) {
                val i = layout.indexOrNull(name) ?: continue
                x[i] = finiteOrZero(event.values[k] * event.confidence)
            }
        }

        val live = intent?.validated()?.takeIf { !it.isExpired(nowMs) }
        if (live != null) {
            live.approach?.let { a ->
                x[intentApproach] = a
                x[intentApproachSet] = 1f
            }
            when (live.target) {
                SocialTarget.USER -> x[intentUser] = 1f
                SocialTarget.NONE, SocialTarget.SELF -> Unit
                else -> x[intentOther] = 1f
            }
        }
        return x
    }

    fun reset() {
        impulses.fill(0f)
    }

    private fun finiteOrZero(v: Float): Float = if (v.isFinite()) v else 0f
}
