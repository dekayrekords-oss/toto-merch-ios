package com.meowmba.shared.neural

import com.meowmba.shared.behavior.DriveVector
import com.meowmba.shared.core.JavaLcgRandom
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The one table that says what each sense means for the drives, lifted from the
 * hand-authored `NervousSystem.onStimulus` so the neural cores start out
 * behaving like the controller they shadow. Each entry is the legacy impulse
 * (drive delta per unit magnitude); the cores scale it into their own units.
 */
object DriveSemantics {
    /** (impulse feature) -> list of (drive, legacy delta). */
    val IMPULSE_TO_DRIVE: Map<String, List<Pair<String, Float>>> = mapOf(
        NeuralFeatures.FACE_ARRIVED to listOf(NeuralFeatures.OUT_SOCIAL to 0.35f, NeuralFeatures.OUT_CURIOSITY to 0.15f),
        NeuralFeatures.FACE_LOST to listOf(NeuralFeatures.OUT_SOCIAL to -0.30f, NeuralFeatures.OUT_CURIOSITY to 0.20f),
        NeuralFeatures.SPOKEN_TO to listOf(NeuralFeatures.OUT_SOCIAL to 0.40f, NeuralFeatures.OUT_APPROACH to 0.15f),
        NeuralFeatures.BARGE_IN to listOf(
            NeuralFeatures.OUT_SOCIAL to 0.40f, NeuralFeatures.OUT_APPROACH to 0.15f, NeuralFeatures.OUT_CURIOSITY to 0.10f,
        ),
        NeuralFeatures.PRAISE to listOf(NeuralFeatures.OUT_SOCIAL to 0.30f, NeuralFeatures.OUT_CAUTION to -0.25f),
        NeuralFeatures.NOVELTY to listOf(NeuralFeatures.OUT_CURIOSITY to 0.45f),
        NeuralFeatures.WAVE to listOf(NeuralFeatures.OUT_CURIOSITY to 0.45f, NeuralFeatures.OUT_SOCIAL to 0.10f),
        NeuralFeatures.SHAKE to listOf(NeuralFeatures.OUT_CURIOSITY to 0.30f, NeuralFeatures.OUT_CAUTION to 0.20f),
        NeuralFeatures.LOUD_SOUND to listOf(NeuralFeatures.OUT_CAUTION to 0.45f, NeuralFeatures.OUT_CURIOSITY to 0.20f),
        NeuralFeatures.LOOMING to listOf(
            NeuralFeatures.OUT_CAUTION to 0.35f, NeuralFeatures.OUT_CURIOSITY to 0.15f, NeuralFeatures.OUT_APPROACH to -0.20f,
        ),
        NeuralFeatures.THREAT to listOf(NeuralFeatures.OUT_CAUTION to 0.60f, NeuralFeatures.OUT_APPROACH to -0.40f),
        NeuralFeatures.TOUCH to listOf(NeuralFeatures.OUT_SOCIAL to 0.25f, NeuralFeatures.OUT_CAUTION to 0.10f),
        NeuralFeatures.TASK_SUCCESS to listOf(NeuralFeatures.OUT_CAUTION to -0.20f, NeuralFeatures.OUT_SOCIAL to 0.15f),
        NeuralFeatures.TASK_FAILURE to listOf(NeuralFeatures.OUT_CAUTION to 0.15f, NeuralFeatures.OUT_SOCIAL to -0.05f),
        NeuralFeatures.SENSOR_LOST to listOf(NeuralFeatures.OUT_CURIOSITY to 0.15f, NeuralFeatures.OUT_CAUTION to 0.10f),
    )

    /** Legacy relaxation time constants per drive, ms (`NervousSystem.Tau`). */
    val DRIVE_TAU_MS: Map<String, Float> = mapOf(
        NeuralFeatures.OUT_APPROACH to 4_000f,
        NeuralFeatures.OUT_SOCIAL to 6_000f,
        NeuralFeatures.OUT_CURIOSITY to 3_000f,
        NeuralFeatures.OUT_CAUTION to 2_500f,
        NeuralFeatures.OUT_VALENCE to 12_000f,
    )

    val REST: DriveVector = DriveVector()
}

/**
 * Builds the hand-initialised checkpoints. Nothing here is learned; it is the
 * legacy controller's semantics written as a wiring diagram, plus a seeded
 * reservoir of spare units whose readout weights start at zero so the offline
 * trainer has nonlinear temporal features to fit without touching the
 * explainable pathways.
 *
 * Unit map (64 units):
 * - 0..15   sensory relays, tau 250 ms, one per input group
 * - 16..23  novelty pairs (fast 200 ms / slow 2000 ms) on presence, motion, voice, device
 * - 24..27  attention competition (user / motion / sound / spare), tau 800 ms,
 *           self-excitation, mutual inhibition, habituating adaptation
 * - 28..33  drive traces at the legacy time constants + intent relay
 * - 34..35  energy integrator (60 s) and arousal (3 s)
 * - 36..39  selection: startle, inspect, orient x / y
 * - 40..63  seeded reservoir, spectral radius 0.9, readout zero
 */
object DefaultRateCheckpoint {
    const val NAME = "meow-rate-default-v1"
    const val UNITS = 64
    const val RESERVOIR_SEED = 1234L

    // Unit indices
    const val RELAY_FACE = 0
    const val RELAY_PROXIMITY = 1
    const val RELAY_OFFSET_X = 2
    const val RELAY_OFFSET_Y = 3
    const val RELAY_MOTION = 4
    const val RELAY_VOICE = 5
    const val RELAY_DEVICE = 6
    const val RELAY_GAZE = 7
    const val RELAY_SPOKEN = 8
    const val RELAY_REWARD = 9
    const val RELAY_TOUCH = 10
    const val RELAY_ALARM = 11
    const val RELAY_AVERSIVE = 12
    const val RELAY_NOVELTY = 13
    const val RELAY_AFFECT = 14
    const val RELAY_SENSOR_LOST = 15
    const val NOVELTY_FACE_FAST = 16
    const val NOVELTY_FACE_SLOW = 17
    const val NOVELTY_MOTION_FAST = 18
    const val NOVELTY_MOTION_SLOW = 19
    const val NOVELTY_VOICE_FAST = 20
    const val NOVELTY_VOICE_SLOW = 21
    const val NOVELTY_DEVICE_FAST = 22
    const val NOVELTY_DEVICE_SLOW = 23
    const val ATTEND_USER = 24
    const val ATTEND_MOTION = 25
    const val ATTEND_SOUND = 26
    const val ATTEND_SPARE = 27
    const val TRACE_SOCIAL = 28
    const val TRACE_CURIOSITY = 29
    const val TRACE_CAUTION = 30
    const val TRACE_APPROACH = 31
    const val TRACE_VALENCE = 32
    const val INTENT_RELAY = 33
    const val ENERGY = 34
    const val AROUSAL = 35
    const val STARTLE = 36
    const val INSPECT = 37
    const val ORIENT_X = 38
    const val ORIENT_Y = 39
    const val RESERVOIR_START = 40

    /** Encoder impulse time constant (ms); must match [FeatureEncoder]'s default. */
    const val IMPULSE_TAU_MS = 600f
    /** Synaptic gain applied to a legacy delta before the tanh; 1 keeps the trace units near-linear. */
    private const val IMPULSE_GAIN = 1f
    /** Self-excitation inside the attention competition; > 1 makes a driven population hold briefly without input. */
    const val ATTENTION_SELF = 1.2f
    const val ATTENTION_INHIBITION = -0.6f

    private val traceOf = mapOf(
        NeuralFeatures.OUT_SOCIAL to TRACE_SOCIAL,
        NeuralFeatures.OUT_CURIOSITY to TRACE_CURIOSITY,
        NeuralFeatures.OUT_CAUTION to TRACE_CAUTION,
        NeuralFeatures.OUT_APPROACH to TRACE_APPROACH,
    )

    fun build(): NeuralCheckpoint {
        val inL = NeuralFeatures.INPUT
        val outL = NeuralFeatures.OUTPUT
        val inputs = inL.size
        val outputs = outL.size

        val tau = FloatArray(UNITS) { 1_000f }
        val adaptTau = FloatArray(UNITS) { 5_000f }
        val adaptGain = FloatArray(UNITS)
        val bias = FloatArray(UNITS)
        val win = FloatArray(UNITS * inputs)
        val wrec = FloatArray(UNITS * UNITS)
        val wout = FloatArray(outputs * UNITS)

        fun inW(unit: Int, feature: String, weight: Float) { win[unit * inputs + inL.index(feature)] += weight }
        fun recW(to: Int, from: Int, weight: Float) { wrec[to * UNITS + from] += weight }
        fun outW(output: String, unit: Int, weight: Float) { wout[outL.index(output) * UNITS + unit] += weight }

        // --- Sensory relays -------------------------------------------------
        for (i in RELAY_FACE..RELAY_SENSOR_LOST) tau[i] = 250f
        inW(RELAY_FACE, NeuralFeatures.FACE_PRESENCE, 1.5f)
        inW(RELAY_FACE, NeuralFeatures.FACE_CONFIDENCE, 0.5f)
        inW(RELAY_PROXIMITY, NeuralFeatures.FACE_PROXIMITY, 1.5f)
        inW(RELAY_OFFSET_X, NeuralFeatures.FACE_OFFSET_X, 1.5f)
        inW(RELAY_OFFSET_Y, NeuralFeatures.FACE_OFFSET_Y, 1.5f)
        inW(RELAY_MOTION, NeuralFeatures.VISUAL_MOTION, 1.5f)
        inW(RELAY_MOTION, NeuralFeatures.HAND_FOLLOW, 1.0f)
        inW(RELAY_VOICE, NeuralFeatures.VOICE_ACTIVITY, 1.5f)
        inW(RELAY_VOICE, NeuralFeatures.VOICE_LOUDNESS, 1.0f)
        inW(RELAY_DEVICE, NeuralFeatures.DEVICE_MOTION, 1.5f)
        inW(RELAY_GAZE, NeuralFeatures.FACE_GAZE_ON_ME, 1.0f)
        inW(RELAY_GAZE, NeuralFeatures.FACE_COUNT, 1.0f)
        inW(RELAY_SPOKEN, NeuralFeatures.SPOKEN_TO, 1.5f)
        inW(RELAY_SPOKEN, NeuralFeatures.BARGE_IN, 1.5f)
        inW(RELAY_REWARD, NeuralFeatures.PRAISE, 1.5f)
        inW(RELAY_REWARD, NeuralFeatures.TASK_SUCCESS, 1.0f)
        inW(RELAY_TOUCH, NeuralFeatures.TOUCH, 1.5f)
        inW(RELAY_ALARM, NeuralFeatures.LOUD_SOUND, 1.5f)
        inW(RELAY_ALARM, NeuralFeatures.SHAKE, 1.5f)
        inW(RELAY_ALARM, NeuralFeatures.LOOMING, 1.5f)
        inW(RELAY_AVERSIVE, NeuralFeatures.THREAT, 1.5f)
        inW(RELAY_AVERSIVE, NeuralFeatures.TASK_FAILURE, 1.0f)
        inW(RELAY_NOVELTY, NeuralFeatures.NOVELTY, 1.5f)
        inW(RELAY_NOVELTY, NeuralFeatures.WAVE, 1.0f)
        inW(RELAY_NOVELTY, NeuralFeatures.FACE_ARRIVED, 1.0f)
        inW(RELAY_NOVELTY, NeuralFeatures.FACE_LOST, 1.0f)
        inW(RELAY_AFFECT, NeuralFeatures.AFFECT, 1.5f)
        inW(RELAY_SENSOR_LOST, NeuralFeatures.SENSOR_LOST, 1.5f)

        // --- Novelty (change) detectors --------------------------------------
        for ((fast, slow, feature) in listOf(
            Triple(NOVELTY_FACE_FAST, NOVELTY_FACE_SLOW, NeuralFeatures.FACE_PRESENCE),
            Triple(NOVELTY_MOTION_FAST, NOVELTY_MOTION_SLOW, NeuralFeatures.VISUAL_MOTION),
            Triple(NOVELTY_VOICE_FAST, NOVELTY_VOICE_SLOW, NeuralFeatures.VOICE_ACTIVITY),
            Triple(NOVELTY_DEVICE_FAST, NOVELTY_DEVICE_SLOW, NeuralFeatures.DEVICE_MOTION),
        )) {
            tau[fast] = 200f
            tau[slow] = 2_000f
            inW(fast, feature, 1.0f)
            inW(slow, feature, 1.0f)
        }

        // --- Attention competition -------------------------------------------
        val attention = listOf(ATTEND_USER, ATTEND_MOTION, ATTEND_SOUND)
        for (i in attention + ATTEND_SPARE) {
            tau[i] = 800f
            adaptTau[i] = 6_000f
        }
        for (i in attention) {
            adaptGain[i] = 0.5f
            recW(i, i, ATTENTION_SELF)
            for (j in attention) if (j != i) recW(i, j, ATTENTION_INHIBITION)
        }
        recW(ATTEND_USER, RELAY_FACE, 2.2f)
        recW(ATTEND_USER, RELAY_SPOKEN, 1.6f)
        recW(ATTEND_USER, RELAY_GAZE, 0.4f)
        inW(ATTEND_USER, NeuralFeatures.INTENT_USER, 1.5f)
        inW(ATTEND_USER, NeuralFeatures.FACE_LOST, -1.0f)
        recW(ATTEND_MOTION, RELAY_MOTION, 1.4f)
        recW(ATTEND_MOTION, RELAY_NOVELTY, 0.6f)
        recW(ATTEND_MOTION, RELAY_DEVICE, 0.4f)
        inW(ATTEND_MOTION, NeuralFeatures.INTENT_OTHER, 0.8f)
        recW(ATTEND_SOUND, RELAY_VOICE, 1.4f)
        recW(ATTEND_SOUND, RELAY_ALARM, 0.8f)

        // --- Drive traces at the legacy time constants -----------------------
        tau[TRACE_SOCIAL] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_SOCIAL)
        tau[TRACE_CURIOSITY] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_CURIOSITY)
        tau[TRACE_CAUTION] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_CAUTION)
        tau[TRACE_APPROACH] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_APPROACH)
        tau[TRACE_VALENCE] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_VALENCE)
        for ((feature, effects) in DriveSemantics.IMPULSE_TO_DRIVE) {
            for ((drive, delta) in effects) {
                inW(traceOf.getValue(drive), feature, IMPULSE_GAIN * delta)
            }
        }
        inW(TRACE_VALENCE, NeuralFeatures.AFFECT, IMPULSE_GAIN * 1.0f)
        // A leaky integrator driven by the encoder's decaying impulse peaks at
        // impulsePeakFactor(tau) times the impulse's initial height, so this readout
        // gain makes the neural drive's peak equal the legacy controller's instant jump.
        fun traceGain(drive: String) =
            1f / (IMPULSE_GAIN * impulsePeakFactor(DriveSemantics.DRIVE_TAU_MS.getValue(drive), IMPULSE_TAU_MS))
        outW(NeuralFeatures.OUT_SOCIAL, TRACE_SOCIAL, traceGain(NeuralFeatures.OUT_SOCIAL))
        outW(NeuralFeatures.OUT_CURIOSITY, TRACE_CURIOSITY, traceGain(NeuralFeatures.OUT_CURIOSITY))
        outW(NeuralFeatures.OUT_CAUTION, TRACE_CAUTION, traceGain(NeuralFeatures.OUT_CAUTION))
        outW(NeuralFeatures.OUT_APPROACH, TRACE_APPROACH, traceGain(NeuralFeatures.OUT_APPROACH))
        outW(NeuralFeatures.OUT_VALENCE, TRACE_VALENCE, traceGain(NeuralFeatures.OUT_VALENCE))
        // Neural additions the legacy integrator could not express: sustained
        // evidence keeps social up while a face is attended, and change itself
        // is interesting.
        outW(NeuralFeatures.OUT_SOCIAL, ATTEND_USER, 0.15f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_FACE_FAST, 0.6f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_FACE_SLOW, -0.6f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_MOTION_FAST, 0.6f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_MOTION_SLOW, -0.6f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_VOICE_FAST, 0.4f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_VOICE_SLOW, -0.4f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_DEVICE_FAST, 0.4f)
        outW(NeuralFeatures.OUT_CURIOSITY, NOVELTY_DEVICE_SLOW, -0.4f)

        // --- Intent relay: approach eases toward the requested value ----------
        tau[INTENT_RELAY] = DriveSemantics.DRIVE_TAU_MS.getValue(NeuralFeatures.OUT_APPROACH)
        inW(INTENT_RELAY, NeuralFeatures.INTENT_APPROACH, 1.0f)
        inW(INTENT_RELAY, NeuralFeatures.INTENT_APPROACH_SET, -DriveSemantics.REST.approach)
        outW(NeuralFeatures.OUT_APPROACH, INTENT_RELAY, 1.04f)

        // --- Arousal and energy ------------------------------------------------
        tau[AROUSAL] = 3_000f
        recW(AROUSAL, TRACE_CURIOSITY, 3.0f)
        recW(AROUSAL, TRACE_CAUTION, 3.0f)
        recW(AROUSAL, TRACE_APPROACH, 2.0f)
        for (i in attention) recW(AROUSAL, i, 0.6f)
        tau[ENERGY] = 60_000f
        recW(ENERGY, AROUSAL, 1.0f)
        outW(NeuralFeatures.OUT_ENERGY, ENERGY, -0.8f)

        // --- Selection -------------------------------------------------------
        tau[STARTLE] = 300f
        recW(STARTLE, RELAY_ALARM, 1.5f)
        inW(STARTLE, NeuralFeatures.LOOMING, 1.5f)
        inW(STARTLE, NeuralFeatures.LOUD_SOUND, 1.0f)
        outW(NeuralFeatures.OUT_STARTLE, STARTLE, 1.2f)
        tau[INSPECT] = 1_500f
        recW(INSPECT, ATTEND_MOTION, 0.8f)
        recW(INSPECT, TRACE_CURIOSITY, 2.5f)
        recW(INSPECT, TRACE_CAUTION, -3.0f)
        outW(NeuralFeatures.OUT_INSPECT, INSPECT, 1.0f)
        tau[ORIENT_X] = 300f
        tau[ORIENT_Y] = 300f
        recW(ORIENT_X, RELAY_OFFSET_X, 1.0f)
        recW(ORIENT_Y, RELAY_OFFSET_Y, 1.0f)
        outW(NeuralFeatures.OUT_ORIENT_X, ORIENT_X, 1.4f)
        outW(NeuralFeatures.OUT_ORIENT_Y, ORIENT_Y, 1.4f)
        outW(NeuralFeatures.OUT_ATTEND_USER, ATTEND_USER, 1.0f)
        outW(NeuralFeatures.OUT_ATTEND_MOTION, ATTEND_MOTION, 1.0f)
        outW(NeuralFeatures.OUT_ATTEND_SOUND, ATTEND_SOUND, 1.0f)

        // --- Reservoir ---------------------------------------------------------
        seedReservoir(tau, win, wrec, inputs)

        val rate = RateWeights(
            units = UNITS,
            tauMs = tau.toList(),
            adaptTauMs = adaptTau.toList(),
            adaptGain = adaptGain.toList(),
            bias = bias.toList(),
            inputWeights = win.toList(),
            recurrentWeights = wrec.toList(),
            outputWeights = wout.toList(),
            weightBound = 4f,
            populations = listOf(
                UnitPopulation("sensory", RELAY_FACE, NOVELTY_FACE_FAST),
                UnitPopulation("novelty", NOVELTY_FACE_FAST, ATTEND_USER),
                UnitPopulation("attention", ATTEND_USER, TRACE_SOCIAL),
                UnitPopulation("traces", TRACE_SOCIAL, ENERGY),
                UnitPopulation("energy", ENERGY, STARTLE),
                UnitPopulation("selection", STARTLE, RESERVOIR_START),
                UnitPopulation("reservoir", RESERVOIR_START, UNITS),
            ),
        )
        return NeuralCheckpoint(
            name = NAME,
            substrate = NeuralSubstrate.RATE,
            inputLayout = inL.names,
            outputLayout = outL.names,
            outputBaseline = outputBaseline(outL),
            outputMin = outputMin(outL),
            outputMax = outputMax(outL),
            rate = rate,
            notes = "Hand-initialised from NervousSystem semantics; reservoir readout is zero until trained.",
        ).requireValid()
    }

    fun buildSpiking(): NeuralCheckpoint {
        val outL = NeuralFeatures.OUTPUT
        return NeuralCheckpoint(
            name = "meow-spiking-default-v1",
            substrate = NeuralSubstrate.SPIKING,
            inputLayout = NeuralFeatures.INPUT.names,
            outputLayout = outL.names,
            outputBaseline = outputBaseline(outL),
            outputMin = outputMin(outL),
            outputMax = outputMax(outL),
            spiking = SpikingConfig(),
            notes = "Leaky integrate-and-fire candidate; wiring derived from the seed and DriveSemantics.",
        ).requireValid()
    }

    fun outputBaseline(outL: FeatureLayout): List<Float> = outL.names.map { name ->
        when (name) {
            NeuralFeatures.OUT_APPROACH -> DriveSemantics.REST.approach
            NeuralFeatures.OUT_SOCIAL -> DriveSemantics.REST.social
            NeuralFeatures.OUT_CURIOSITY -> DriveSemantics.REST.curiosity
            NeuralFeatures.OUT_CAUTION -> DriveSemantics.REST.caution
            NeuralFeatures.OUT_ENERGY -> DriveSemantics.REST.energy
            else -> 0f
        }
    }

    fun outputMin(outL: FeatureLayout): List<Float> = outL.names.map { name ->
        when (name) {
            NeuralFeatures.OUT_VALENCE, NeuralFeatures.OUT_ORIENT_X, NeuralFeatures.OUT_ORIENT_Y -> -1f
            else -> 0f
        }
    }

    fun outputMax(outL: FeatureLayout): List<Float> = outL.names.map { 1f }

    /**
     * Peak of `tauH dh/dt = -h + exp(-t/tauX)` starting from rest, as a fraction
     * of the impulse height. Closed form of the first-order response to an
     * exponential input; used to calibrate readout gains against the legacy jump.
     */
    fun impulsePeakFactor(tauH: Float, tauX: Float): Float {
        if (abs(tauH - tauX) < 1e-3f) return exp(-1.0).toFloat()
        val r = tauX / tauH
        val tPeak = ln(1.0 / r) * tauH * tauX / (tauH - tauX)
        val value = (tauX / (tauH - tauX)) * (exp(-tPeak / tauH) - exp(-tPeak / tauX))
        return value.toFloat()
    }

    /**
     * Fill units [RESERVOIR_START, UNITS) with seeded sparse weights: input
     * density 0.3, internal density 0.2 scaled to spectral radius 0.9, and
     * log-uniform time constants between 300 ms and 3 s.
     *
     * Deterministic across platforms because it draws from [JavaLcgRandom], a
     * bit-exact reproduction of `java.util.Random`'s fixed LCG. Using
     * `kotlin.random.Random` here instead would give iOS a different network from
     * Android at the same seed — silently, and without anything crashing.
     */
    private fun seedReservoir(tau: FloatArray, win: FloatArray, wrec: FloatArray, inputs: Int) {
        val rnd = JavaLcgRandom(RESERVOIR_SEED)
        val size = UNITS - RESERVOIR_START
        for (r in 0 until size) {
            val i = RESERVOIR_START + r
            val logTau = ln(300.0) + rnd.nextDouble() * (ln(3_000.0) - ln(300.0))
            tau[i] = exp(logTau).toFloat()
            for (j in 0 until inputs) {
                if (rnd.nextDouble() < 0.3) win[i * inputs + j] = ((rnd.nextDouble() * 2 - 1) * 0.4).toFloat()
            }
            // Feed-forward taps from the sensory relays so the reservoir sees
            // the same evidence as the explainable pathways.
            for (j in RELAY_FACE..RELAY_SENSOR_LOST) {
                if (rnd.nextDouble() < 0.2) wrec[i * UNITS + j] = ((rnd.nextDouble() * 2 - 1) * 0.5).toFloat()
            }
        }
        val block = Array(size) { DoubleArray(size) }
        for (r in 0 until size) for (c in 0 until size) {
            if (rnd.nextDouble() < 0.2) block[r][c] = rnd.nextDouble() * 2 - 1
        }
        val radius = spectralRadius(block)
        val scale = if (radius > 1e-9) 0.9 / radius else 1.0
        for (r in 0 until size) for (c in 0 until size) {
            wrec[(RESERVOIR_START + r) * UNITS + (RESERVOIR_START + c)] = (block[r][c] * scale).toFloat()
        }
    }

    /** Power iteration on |M| gives an upper bound on the spectral radius, which is what we need for stability. */
    private fun spectralRadius(m: Array<DoubleArray>): Double {
        val n = m.size
        var v = DoubleArray(n) { 1.0 / sqrt(n.toDouble()) }
        var lambda = 0.0
        repeat(200) {
            val next = DoubleArray(n)
            for (r in 0 until n) {
                var s = 0.0
                for (c in 0 until n) s += abs(m[r][c]) * v[c]
                next[r] = s
            }
            val norm = sqrt(next.sumOf { it * it })
            if (norm < 1e-12) return 0.0
            lambda = norm
            v = DoubleArray(n) { next[it] / norm }
        }
        return lambda
    }
}
