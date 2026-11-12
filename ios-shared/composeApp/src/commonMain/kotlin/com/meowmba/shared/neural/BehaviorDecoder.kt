package com.meowmba.shared.neural

import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.DriveVector
import com.meowmba.shared.behavior.SocialTarget

/**
 * The drives-to-gains mapping, identical to `NervousSystem.bias()` so a neural
 * bias and a legacy bias computed from the same drives are the same numbers.
 * That is what makes shadow-mode divergence a statement about the *drives* the
 * two controllers arrive at, not about two different formulas.
 */
object DriveBias {
    fun arousal(d: DriveVector): Float =
        ((d.approach * 0.25f) + (d.curiosity * 0.35f) + (d.caution * 0.40f) +
            ((1f - d.energy) * 0.15f)).coerceIn(0f, 1f)

    fun compute(d: DriveVector, valenceImpulse: Float): BehaviorBias {
        val arousal = arousal(d)
        val valence = ((d.social - 0.5f) * 1.2f + (0.5f - d.caution) * 0.8f + valenceImpulse)
            .coerceIn(-1f, 1f)
        val vitality = ((1f - d.caution) * 0.6f + d.energy * 0.4f).coerceIn(0f, 1f)
        val inspection = (d.curiosity * (1f - d.caution)).coerceIn(0f, 1f)
        return BehaviorBias(
            glanceGain = (0.55f + 0.45f * d.curiosity) * vitality,
            followGain = (0.35f + 0.65f * d.social) * vitality,
            restWeight = (1f - inspection) * (1.2f - d.curiosity) + 0.05f,
            inspectWeight = inspection + 0.05f,
            verticalWeight = inspection * 0.7f + 0.05f,
            earGain = (0.4f + 0.6f * arousal) * vitality,
            blinkRateScale = (1f + d.caution * 0.8f - inspection * 0.25f).coerceIn(0.5f, 2f),
            arousal = arousal,
            valence = valence,
        )
    }

    /** Mean absolute difference over the nine bias fields; 0 means identical. */
    fun divergence(a: BehaviorBias, b: BehaviorBias): Float {
        val sum = kotlin.math.abs(a.glanceGain - b.glanceGain) +
            kotlin.math.abs(a.followGain - b.followGain) +
            kotlin.math.abs(a.restWeight - b.restWeight) +
            kotlin.math.abs(a.inspectWeight - b.inspectWeight) +
            kotlin.math.abs(a.verticalWeight - b.verticalWeight) +
            kotlin.math.abs(a.earGain - b.earGain) +
            kotlin.math.abs(a.blinkRateScale - b.blinkRateScale) +
            kotlin.math.abs(a.arousal - b.arousal) +
            kotlin.math.abs(a.valence - b.valence)
        return sum / 9f
    }

    fun toArray(b: BehaviorBias): FloatArray = floatArrayOf(
        b.glanceGain, b.followGain, b.restWeight, b.inspectWeight, b.verticalWeight,
        b.earGain, b.blinkRateScale, b.arousal, b.valence,
    )

    val FIELD_NAMES: List<String> = listOf(
        "glanceGain", "followGain", "restWeight", "inspectWeight", "verticalWeight",
        "earGain", "blinkRateScale", "arousal", "valence",
    )
}

/** Attention evidence per candidate target, 0..1 each. */
data class AttentionReadout(val user: Float, val motion: Float, val sound: Float) {
    /** Strongest candidate, or NONE below [threshold]. */
    fun strongest(threshold: Float = 0.25f): SocialTarget {
        val best = maxOf(user, motion, sound)
        if (best < threshold) return SocialTarget.NONE
        return when (best) {
            user -> SocialTarget.USER
            motion -> SocialTarget.MOTION
            else -> SocialTarget.SOUND
        }
    }
}

/** Everything the decoder extracts from one readout vector. */
data class DecodedBehavior(
    val drives: DriveVector,
    val valenceImpulse: Float,
    val bias: BehaviorBias,
    val attention: AttentionReadout,
    val orientX: Float,
    val orientY: Float,
    val startle: Float,
    val inspect: Float,
)

/**
 * Turns a core's readout into drives, a bias and a small set of proposals.
 * Pure: the same readout always decodes to the same thing. A readout whose
 * layout does not carry a channel decodes that channel as its resting value
 * rather than failing, so a checkpoint with a smaller head still runs.
 */
class BehaviorDecoder(
    private val layout: FeatureLayout = NeuralFeatures.OUTPUT,
    private val startleThreshold: Float = 0.5f,
    private val inspectThreshold: Float = 0.5f,
    private val orientThreshold: Float = 0.15f,
) {
    private val iApproach = layout.indexOrNull(NeuralFeatures.OUT_APPROACH)
    private val iSocial = layout.indexOrNull(NeuralFeatures.OUT_SOCIAL)
    private val iCuriosity = layout.indexOrNull(NeuralFeatures.OUT_CURIOSITY)
    private val iCaution = layout.indexOrNull(NeuralFeatures.OUT_CAUTION)
    private val iEnergy = layout.indexOrNull(NeuralFeatures.OUT_ENERGY)
    private val iValence = layout.indexOrNull(NeuralFeatures.OUT_VALENCE)
    private val iOrientX = layout.indexOrNull(NeuralFeatures.OUT_ORIENT_X)
    private val iOrientY = layout.indexOrNull(NeuralFeatures.OUT_ORIENT_Y)
    private val iAttendUser = layout.indexOrNull(NeuralFeatures.OUT_ATTEND_USER)
    private val iAttendMotion = layout.indexOrNull(NeuralFeatures.OUT_ATTEND_MOTION)
    private val iAttendSound = layout.indexOrNull(NeuralFeatures.OUT_ATTEND_SOUND)
    private val iStartle = layout.indexOrNull(NeuralFeatures.OUT_STARTLE)
    private val iInspect = layout.indexOrNull(NeuralFeatures.OUT_INSPECT)

    private val rest = DriveSemantics.REST

    fun decode(readout: FloatArray): DecodedBehavior {
        require(readout.size == layout.size) { "readout has ${readout.size} channels, layout has ${layout.size}" }
        fun at(i: Int?, default: Float, lo: Float = 0f, hi: Float = 1f): Float {
            if (i == null) return default
            val v = readout[i]
            return if (v.isFinite()) v.coerceIn(lo, hi) else default
        }
        val drives = DriveVector(
            approach = at(iApproach, rest.approach),
            social = at(iSocial, rest.social),
            curiosity = at(iCuriosity, rest.curiosity),
            caution = at(iCaution, rest.caution),
            energy = at(iEnergy, rest.energy),
        )
        val valenceImpulse = at(iValence, 0f, -1f, 1f)
        return DecodedBehavior(
            drives = drives,
            valenceImpulse = valenceImpulse,
            bias = DriveBias.compute(drives, valenceImpulse),
            attention = AttentionReadout(
                user = at(iAttendUser, 0f),
                motion = at(iAttendMotion, 0f),
                sound = at(iAttendSound, 0f),
            ),
            orientX = at(iOrientX, 0f, -1f, 1f),
            orientY = at(iOrientY, 0f, -1f, 1f),
            startle = at(iStartle, 0f),
            inspect = at(iInspect, 0f),
        )
    }

    /** Proposals for the arbiter, strongest first. Always includes a REST fallback. */
    fun proposals(d: DecodedBehavior, nowMs: Long): List<BehaviorProposal> {
        val out = ArrayList<BehaviorProposal>(4)
        val target = d.attention.strongest()
        if (d.startle >= startleThreshold) {
            out += BehaviorProposal(
                action = BehaviorAction.STARTLE,
                target = if (d.attention.sound >= d.attention.motion) SocialTarget.SOUND else SocialTarget.MOTION,
                intensity = d.startle,
                confidence = d.startle,
                issuedAtMs = nowMs,
            )
        }
        val orientMagnitude = maxOf(kotlin.math.abs(d.orientX), kotlin.math.abs(d.orientY))
        if (orientMagnitude >= orientThreshold && target != SocialTarget.NONE) {
            out += BehaviorProposal(
                action = BehaviorAction.ORIENT,
                target = target,
                intensity = orientMagnitude,
                confidence = maxOf(d.attention.user, d.attention.motion, d.attention.sound),
                dirX = d.orientX,
                dirY = d.orientY,
                issuedAtMs = nowMs,
            )
        }
        if (d.inspect >= inspectThreshold) {
            out += BehaviorProposal(
                action = BehaviorAction.INSPECT,
                target = if (target == SocialTarget.NONE) SocialTarget.MOTION else target,
                intensity = d.inspect,
                confidence = d.inspect,
                issuedAtMs = nowMs,
            )
        }
        if (d.drives.energy < 0.3f || d.drives.caution > 0.7f) {
            out += BehaviorProposal(
                action = BehaviorAction.SETTLE,
                target = SocialTarget.SELF,
                intensity = maxOf(1f - d.drives.energy, d.drives.caution),
                confidence = 0.6f,
                issuedAtMs = nowMs,
            )
        }
        out += BehaviorProposal(
            action = BehaviorAction.REST,
            target = SocialTarget.NONE,
            intensity = 0f,
            confidence = 1f,
            issuedAtMs = nowMs,
        )
        return out
    }
}
