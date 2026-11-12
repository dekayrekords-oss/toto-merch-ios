package com.meowmba.shared.neural

import com.meowmba.shared.core.JavaLcgRandom
import kotlin.math.exp
import kotlin.math.min

/**
 * A leaky integrate-and-fire network: the "fly brain flavour" candidate behind
 * the same [NeuralCore] interface as the rate core.
 *
 * Every neuron has a membrane potential, a threshold, a reset and a refractory
 * period; synapses are signed and bounded with excitatory and inhibitory
 * neurons kept separate (Dale's law). Output channels are read as the low-pass
 * firing rate of a positive population minus a negative one, so a readout can
 * go both ways from a strictly non-negative code:
 * ```
 *   y_o = clamp(baseline_o + gain * (rate_pos_o - rate_neg_o) / maxRate, min_o, max_o)
 * ```
 * Wiring is derived deterministically from the checkpoint's seed and from
 * [DriveSemantics], and there is no randomness at run time, so the same input
 * sequence always produces the same spikes. Integration uses fixed substeps of
 * `substepMs`, so cost scales with the requested dt; at 5 Hz control ticks and
 * 2 ms substeps that is 100 substeps per tick over 256 sparse neurons.
 *
 * Nothing about this core is trained in this increment. It exists so the
 * substrate comparison the master plan asks for can happen against a real
 * implementation instead of a slide.
 *
 * ## Port note
 *
 * The wiring below draws from [JavaLcgRandom] rather than `java.util.Random`, so
 * the neuron-level wiring is bit-identical on iOS and Android at the same
 * [SpikingConfig.seed]. See that class for why the substitution matters.
 */
class SpikingCore(
    checkpoint: NeuralCheckpoint,
) : NeuralCore {

    private val ckpt = checkpoint.requireValid().also {
        require(it.substrate == NeuralSubstrate.SPIKING && it.spiking != null) { "not a spiking checkpoint" }
    }
    private val cfg: SpikingConfig = ckpt.spiking!!

    override val substrate: NeuralSubstrate get() = NeuralSubstrate.SPIKING
    override val inputLayout: FeatureLayout = ckpt.inputs
    override val outputLayout: FeatureLayout = ckpt.outputs

    private val inputs = inputLayout.size
    private val outputs = outputLayout.size
    val neurons: Int = cfg.neurons
    private val popSize = cfg.populationSize
    /** Output population neurons come first; the rest are inhibitory interneurons. */
    private val excitatoryCount = outputs * 2 * popSize

    private val baseline = FloatArray(outputs) { ckpt.outputBaseline[it] }
    private val outMin = FloatArray(outputs) { ckpt.outputMin[it] }
    private val outMax = FloatArray(outputs) { ckpt.outputMax[it] }

    // Static wiring
    private val postIdx: Array<IntArray>
    private val postW: Array<FloatArray>
    private val inputIdx: Array<IntArray>
    private val inputW: Array<FloatArray>
    private val restCurrent = FloatArray(neurons)

    // Dynamic state
    private val v = FloatArray(neurons)
    private val syn = FloatArray(neurons)
    private val refractory = FloatArray(neurons)
    private val rate = FloatArray(neurons)
    private val lastSpikeMs = FloatArray(neurons) { Float.NEGATIVE_INFINITY }
    private val everSpiked = BooleanArray(neurons)
    private val inputCurrent = FloatArray(neurons)
    private var simMs = 0f
    private var lastSubsteps = 0
    private var lastSpikes = 0L
    private var totalSpikes = 0L
    private var minIsiMs = Float.POSITIVE_INFINITY
    private var nanResets = 0L

    private val synDecay = exp(-cfg.substepMs / cfg.tauSynapseMs)
    private val rateDecay = exp(-cfg.substepMs / cfg.tauRateMs)
    /** A single spike adds this to the neuron's rate estimate so a train at f Hz reads ~f. */
    private val rateKick = 1000f / cfg.tauRateMs

    init {
        val rnd = JavaLcgRandom(cfg.seed)
        val post = Array(neurons) { ArrayList<Int>() }
        val postWeights = Array(neurons) { ArrayList<Float>() }

        // Recurrent synapses: each neuron draws recurrentFanIn presynaptic partners.
        for (i in 0 until neurons) {
            val ownPop = populationOf(i)
            repeat(cfg.recurrentFanIn) { k ->
                val j = if (ownPop >= 0 && k < cfg.recurrentFanIn / 2) {
                    // Half the fan-in from the same assembly keeps a driven population going.
                    val start = ownPop * popSize
                    start + rnd.nextInt(popSize)
                } else {
                    rnd.nextInt(neurons)
                }
                if (j == i) return@repeat
                val w = if (j < excitatoryCount) cfg.excitatoryWeight else cfg.inhibitoryWeight
                post[j].add(i)
                postWeights[j].add(w)
            }
        }
        postIdx = Array(neurons) { post[it].toIntArray() }
        postW = Array(neurons) { postWeights[it].toFloatArray() }

        // Input projections: semantic targets from DriveSemantics plus weak random mixing.
        val inIdx = Array(inputs) { ArrayList<Int>() }
        val inW = Array(inputs) { ArrayList<Float>() }
        fun project(feature: String, output: String, signedDelta: Float) {
            val j = inputLayout.indexOrNull(feature) ?: return
            val o = outputLayout.indexOrNull(output) ?: return
            val positive = signedDelta >= 0f
            val start = popStart(o, positive)
            val fanOut = min(cfg.inputFanOut, popSize)
            for (n in 0 until fanOut) {
                inIdx[j].add(start + n)
                inW[j].add(cfg.inputGain * kotlin.math.abs(signedDelta))
            }
        }
        for ((feature, effects) in DriveSemantics.IMPULSE_TO_DRIVE) {
            for ((drive, delta) in effects) project(feature, drive, delta)
        }
        project(NeuralFeatures.AFFECT, NeuralFeatures.OUT_VALENCE, 1.0f)
        project(NeuralFeatures.FACE_PRESENCE, NeuralFeatures.OUT_ATTEND_USER, 1.0f)
        project(NeuralFeatures.FACE_PRESENCE, NeuralFeatures.OUT_SOCIAL, 0.15f)
        project(NeuralFeatures.FACE_GAZE_ON_ME, NeuralFeatures.OUT_ATTEND_USER, 0.4f)
        project(NeuralFeatures.SPOKEN_TO, NeuralFeatures.OUT_ATTEND_USER, 0.8f)
        project(NeuralFeatures.INTENT_USER, NeuralFeatures.OUT_ATTEND_USER, 1.0f)
        project(NeuralFeatures.VISUAL_MOTION, NeuralFeatures.OUT_ATTEND_MOTION, 1.0f)
        project(NeuralFeatures.HAND_FOLLOW, NeuralFeatures.OUT_ATTEND_MOTION, 0.6f)
        project(NeuralFeatures.NOVELTY, NeuralFeatures.OUT_ATTEND_MOTION, 0.5f)
        project(NeuralFeatures.VOICE_ACTIVITY, NeuralFeatures.OUT_ATTEND_SOUND, 1.0f)
        project(NeuralFeatures.LOUD_SOUND, NeuralFeatures.OUT_ATTEND_SOUND, 0.8f)
        project(NeuralFeatures.LOUD_SOUND, NeuralFeatures.OUT_STARTLE, 1.0f)
        project(NeuralFeatures.LOOMING, NeuralFeatures.OUT_STARTLE, 1.2f)
        project(NeuralFeatures.SHAKE, NeuralFeatures.OUT_STARTLE, 0.8f)
        project(NeuralFeatures.NOVELTY, NeuralFeatures.OUT_INSPECT, 0.8f)
        project(NeuralFeatures.WAVE, NeuralFeatures.OUT_INSPECT, 0.8f)
        project(NeuralFeatures.VISUAL_MOTION, NeuralFeatures.OUT_INSPECT, 0.5f)
        project(NeuralFeatures.THREAT, NeuralFeatures.OUT_INSPECT, -1.0f)
        project(NeuralFeatures.FACE_OFFSET_X, NeuralFeatures.OUT_ORIENT_X, 1.0f)
        project(NeuralFeatures.FACE_OFFSET_Y, NeuralFeatures.OUT_ORIENT_Y, 1.0f)
        project(NeuralFeatures.INTENT_APPROACH, NeuralFeatures.OUT_APPROACH, 1.0f)
        project(NeuralFeatures.INTENT_APPROACH_SET, NeuralFeatures.OUT_APPROACH, -DriveSemantics.REST.approach)
        // Sustained excitement should tire the character: arousing inputs feed the negative energy population weakly.
        for (feature in listOf(
            NeuralFeatures.SPOKEN_TO, NeuralFeatures.LOUD_SOUND, NeuralFeatures.NOVELTY,
            NeuralFeatures.VISUAL_MOTION, NeuralFeatures.VOICE_ACTIVITY, NeuralFeatures.THREAT,
        )) project(feature, NeuralFeatures.OUT_ENERGY, -0.15f)
        // Weak random mixing so the network is not purely feed-forward per channel.
        for (j in 0 until inputs) {
            repeat(cfg.inputFanOut / 3) {
                inIdx[j].add(rnd.nextInt(neurons))
                inW[j].add(cfg.inputGain * 0.15f)
            }
        }
        inputIdx = Array(inputs) { inIdx[it].toIntArray() }
        inputW = Array(inputs) { inW[it].toFloatArray() }

        // Sub-threshold resting currents break symmetry inside a population so its
        // neurons do not fire in lock-step; none of them can fire without input.
        for (i in 0 until neurons) restCurrent[i] = (rnd.nextDouble() * 0.15 * cfg.threshold).toFloat()
    }

    /** Which output population (0 until outputs*2) neuron [i] belongs to, or -1 for interneurons. */
    private fun populationOf(i: Int): Int = if (i < excitatoryCount) i / popSize else -1

    private fun popStart(output: Int, positive: Boolean): Int = (output * 2 + if (positive) 0 else 1) * popSize

    /** Signed inputs (offsets, affect) excite the population matching their sign. */
    private fun isSigned(j: Int): Boolean {
        val name = inputLayout.names[j]
        return name == NeuralFeatures.FACE_OFFSET_X || name == NeuralFeatures.FACE_OFFSET_Y || name == NeuralFeatures.AFFECT
    }

    private val signedInput = BooleanArray(inputs) { isSigned(it) }

    override fun step(input: FloatArray, dtMs: Float) {
        require(input.size == inputs) { "input has ${input.size} features, core expects $inputs" }
        if (!(dtMs > 0f) || !dtMs.isFinite()) return

        // Input current is held constant across the step.
        inputCurrent.fill(0f)
        for (j in 0 until inputs) {
            var x = input[j]
            if (x == 0f || !x.isFinite()) continue
            val targets = inputIdx[j]
            val weights = inputW[j]
            if (signedInput[j]) {
                // Route |x| into the population of matching sign by mirroring the target.
                val negative = x < 0f
                x = kotlin.math.abs(x)
                for (k in targets.indices) {
                    var t = targets[k]
                    val pop = populationOf(t)
                    if (negative && pop >= 0) t = mirrorPopulation(t)
                    inputCurrent[t] += weights[k] * x
                }
            } else {
                for (k in targets.indices) inputCurrent[targets[k]] += weights[k] * x
            }
        }

        val n = kotlin.math.ceil(dtMs / cfg.substepMs).toInt().coerceAtLeast(1)
        val sub = dtMs / n
        lastSubsteps = n
        var spikes = 0L
        val leak = sub / cfg.tauMembraneMs

        repeat(n) {
            simMs += sub
            for (i in 0 until neurons) {
                syn[i] *= synDecay
                rate[i] *= rateDecay
                if (refractory[i] > 0f) {
                    refractory[i] -= sub
                    v[i] = 0f
                    continue
                }
                val drive = inputCurrent[i] + syn[i] + restCurrent[i]
                v[i] += leak * (drive - v[i])
            }
            for (i in 0 until neurons) {
                if (refractory[i] > 0f || v[i] < cfg.threshold) continue
                v[i] = 0f
                refractory[i] = cfg.refractoryMs
                rate[i] += rateKick
                spikes++
                everSpiked[i] = true
                val isi = simMs - lastSpikeMs[i]
                if (isi < minIsiMs) minIsiMs = isi
                lastSpikeMs[i] = simMs
                val targets = postIdx[i]
                val weights = postW[i]
                for (k in targets.indices) syn[targets[k]] += weights[k]
            }
        }
        lastSpikes = spikes
        totalSpikes += spikes

        var finite = true
        for (i in 0 until neurons) if (!v[i].isFinite() || !syn[i].isFinite() || !rate[i].isFinite()) { finite = false; break }
        if (!finite) {
            nanResets++
            v.fill(0f); syn.fill(0f); rate.fill(0f); refractory.fill(0f)
        }
    }

    private fun mirrorPopulation(i: Int): Int {
        val pop = populationOf(i)
        val offset = i - pop * popSize
        val mirrored = if (pop % 2 == 0) pop + 1 else pop - 1
        return mirrored * popSize + offset
    }

    /** Normalised firing rate of output [o]'s positive or negative population. */
    fun populationRate(o: Int, positive: Boolean): Float {
        val start = popStart(o, positive)
        var s = 0f
        for (i in start until start + popSize) s += rate[i]
        return (s / popSize) / cfg.maxRateHz
    }

    override fun readout(): FloatArray {
        val y = FloatArray(outputs)
        for (o in 0 until outputs) {
            val acc = baseline[o] + cfg.outputGain * (populationRate(o, true) - populationRate(o, false))
            y[o] = if (acc.isFinite()) acc.coerceIn(outMin[o], outMax[o]) else baseline[o]
        }
        return y
    }

    override fun reset() {
        v.fill(0f); syn.fill(0f); refractory.fill(0f); rate.fill(0f)
        lastSpikeMs.fill(Float.NEGATIVE_INFINITY)
        everSpiked.fill(false)
        simMs = 0f
        lastSubsteps = 0
        lastSpikes = 0
        minIsiMs = Float.POSITIVE_INFINITY
    }

    override fun diagnostics(): CoreDiagnostics {
        var sum = 0f
        var max = 0f
        var saturated = 0
        var silent = 0
        for (i in 0 until neurons) {
            val r = rate[i] / cfg.maxRateHz
            sum += r
            if (r > max) max = r
            if (r > 0.95f) saturated++
            if (!everSpiked[i]) silent++
        }
        return CoreDiagnostics(
            substrate = NeuralSubstrate.SPIKING,
            units = neurons,
            meanAbsActivity = sum / neurons,
            maxAbsActivity = max,
            saturatedUnits = saturated,
            silentUnits = silent,
            substeps = lastSubsteps,
            nanResets = nanResets,
            extra = mapOf(
                "spikes.last" to lastSpikes.toFloat(),
                "spikes.total" to totalSpikes.toFloat(),
                "isi.min_ms" to (if (minIsiMs.isFinite()) minIsiMs else -1f),
                "sim_ms" to simMs,
            ),
        )
    }
}
