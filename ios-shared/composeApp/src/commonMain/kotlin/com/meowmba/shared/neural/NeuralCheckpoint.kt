package com.meowmba.shared.neural

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A named contiguous range of units, `[start, end)`, for diagnostics and explainability. */
@Serializable
data class UnitPopulation(val name: String, val start: Int, val end: Int)

/**
 * Parameters of a [LeakyRecurrentCore]. Matrices are flat, row-major:
 * `inputWeights[i * inputs + j]`, `recurrentWeights[i * units + k]`,
 * `outputWeights[o * units + i]`.
 */
@Serializable
data class RateWeights(
    val units: Int,
    val tauMs: List<Float>,
    val adaptTauMs: List<Float>,
    val adaptGain: List<Float>,
    val bias: List<Float>,
    val inputWeights: List<Float>,
    val recurrentWeights: List<Float>,
    val outputWeights: List<Float>,
    val weightBound: Float = 4f,
    val populations: List<UnitPopulation> = emptyList(),
)

/** Construction parameters of a [SpikingCore]; its wiring is derived deterministically from [seed]. */
@Serializable
data class SpikingConfig(
    val seed: Long = 7L,
    val neurons: Int = 256,
    /** Neurons per output population, for each of the positive and negative halves. */
    val populationSize: Int = 8,
    val recurrentFanIn: Int = 24,
    val inputFanOut: Int = 12,
    val tauMembraneMs: Float = 20f,
    val tauSynapseMs: Float = 8f,
    val tauRateMs: Float = 250f,
    val threshold: Float = 1f,
    val refractoryMs: Float = 3f,
    val substepMs: Float = 2f,
    val inputGain: Float = 1.6f,
    val excitatoryWeight: Float = 0.12f,
    val inhibitoryWeight: Float = -0.2f,
    /** Spikes per second at which a population's normalised rate reads 1. */
    val maxRateHz: Float = 120f,
    val outputGain: Float = 1f,
)

/**
 * Everything needed to reconstruct a core, as one JSON document. The layouts
 * travel with the weights so a checkpoint trained against a different feature
 * set is rejected instead of silently misread.
 */
@Serializable
data class NeuralCheckpoint(
    val version: Int = FORMAT_VERSION,
    val name: String,
    val substrate: NeuralSubstrate,
    val inputLayout: List<String>,
    val outputLayout: List<String>,
    val outputBaseline: List<Float>,
    val outputMin: List<Float>,
    val outputMax: List<Float>,
    val rate: RateWeights? = null,
    val spiking: SpikingConfig? = null,
    val notes: String = "",
) {
    val inputs: FeatureLayout get() = FeatureLayout(inputLayout)
    val outputs: FeatureLayout get() = FeatureLayout(outputLayout)

    /** Every problem found; empty means the checkpoint is safe to load. */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (version != FORMAT_VERSION) problems += "unsupported version $version (expected $FORMAT_VERSION)"
        if (inputLayout.isEmpty()) problems += "empty input layout"
        if (outputLayout.isEmpty()) problems += "empty output layout"
        if (inputLayout.toSet().size != inputLayout.size) problems += "duplicate input names"
        if (outputLayout.toSet().size != outputLayout.size) problems += "duplicate output names"
        val outs = outputLayout.size
        checkVector("outputBaseline", outputBaseline, outs, problems)
        checkVector("outputMin", outputMin, outs, problems)
        checkVector("outputMax", outputMax, outs, problems)
        if (outputMin.size == outs && outputMax.size == outs && outputBaseline.size == outs) {
            for (o in 0 until outs) {
                if (outputMin[o] >= outputMax[o]) problems += "output $o: min >= max"
                if (outputBaseline[o] !in outputMin[o]..outputMax[o]) problems += "output $o: baseline outside bounds"
            }
        }
        when (substrate) {
            NeuralSubstrate.RATE -> {
                val r = rate
                if (r == null) problems += "rate substrate without rate weights" else validateRate(r, problems)
            }
            NeuralSubstrate.SPIKING -> {
                val s = spiking
                if (s == null) problems += "spiking substrate without spiking config" else validateSpiking(s, problems)
            }
        }
        return problems
    }

    fun requireValid(): NeuralCheckpoint {
        val problems = validate()
        require(problems.isEmpty()) { "invalid neural checkpoint '$name': ${problems.joinToString("; ")}" }
        return this
    }

    fun toJson(): String = JSON.encodeToString(this)

    private fun validateRate(r: RateWeights, problems: MutableList<String>) {
        val n = r.units
        if (n <= 0) {
            problems += "rate.units must be positive"
            return
        }
        if (!(r.weightBound > 0f && r.weightBound.isFinite())) problems += "rate.weightBound must be positive"
        checkVector("rate.tauMs", r.tauMs, n, problems)
        checkVector("rate.adaptTauMs", r.adaptTauMs, n, problems)
        checkVector("rate.adaptGain", r.adaptGain, n, problems)
        checkVector("rate.bias", r.bias, n, problems)
        checkVector("rate.inputWeights", r.inputWeights, n * inputLayout.size, problems, r.weightBound)
        checkVector("rate.recurrentWeights", r.recurrentWeights, n * n, problems, r.weightBound)
        checkVector("rate.outputWeights", r.outputWeights, outputLayout.size * n, problems, OUTPUT_WEIGHT_BOUND)
        if (r.tauMs.size == n && r.tauMs.any { it < MIN_TAU_MS || it > MAX_TAU_MS }) {
            problems += "rate.tauMs outside [$MIN_TAU_MS, $MAX_TAU_MS]"
        }
        if (r.adaptTauMs.size == n && r.adaptTauMs.any { it <= 0f }) problems += "rate.adaptTauMs must be positive"
        if (r.adaptGain.size == n && r.adaptGain.any { it < 0f || it > 2f }) problems += "rate.adaptGain outside [0, 2]"
        for (p in r.populations) {
            if (p.start < 0 || p.end > n || p.start >= p.end) problems += "population '${p.name}' out of range"
        }
    }

    private fun validateSpiking(s: SpikingConfig, problems: MutableList<String>) {
        if (s.neurons < 16) problems += "spiking.neurons too small"
        if (s.populationSize <= 0) problems += "spiking.populationSize must be positive"
        if (s.populationSize * 2 * outputLayout.size > s.neurons) problems += "spiking: output populations exceed neuron count"
        if (s.recurrentFanIn <= 0 || s.recurrentFanIn >= s.neurons) problems += "spiking.recurrentFanIn out of range"
        if (s.inputFanOut <= 0 || s.inputFanOut > s.neurons) problems += "spiking.inputFanOut out of range"
        if (s.tauMembraneMs <= 0f || s.tauSynapseMs <= 0f || s.tauRateMs <= 0f) problems += "spiking time constants must be positive"
        if (s.threshold <= 0f) problems += "spiking.threshold must be positive"
        if (s.refractoryMs < 0f) problems += "spiking.refractoryMs must be non-negative"
        if (s.substepMs <= 0f || s.substepMs > s.tauMembraneMs / 2f) problems += "spiking.substepMs must be in (0, tauMembrane/2]"
        if (s.excitatoryWeight < 0f || s.inhibitoryWeight > 0f) problems += "spiking E/I weights have the wrong sign"
        if (s.maxRateHz <= 0f) problems += "spiking.maxRateHz must be positive"
        listOf(
            s.tauMembraneMs, s.tauSynapseMs, s.tauRateMs, s.threshold, s.refractoryMs, s.substepMs,
            s.inputGain, s.excitatoryWeight, s.inhibitoryWeight, s.maxRateHz, s.outputGain,
        ).forEach { if (!it.isFinite()) problems += "spiking config contains a non-finite value" }
    }

    private fun checkVector(
        label: String,
        v: List<Float>,
        expected: Int,
        problems: MutableList<String>,
        bound: Float = Float.POSITIVE_INFINITY,
    ) {
        if (v.size != expected) {
            problems += "$label has ${v.size} entries, expected $expected"
            return
        }
        var nonFinite = 0
        var outOfBound = 0
        for (x in v) {
            if (!x.isFinite()) nonFinite++ else if (kotlin.math.abs(x) > bound) outOfBound++
        }
        if (nonFinite > 0) problems += "$label has $nonFinite non-finite entries"
        if (outOfBound > 0) problems += "$label has $outOfBound entries beyond ±$bound"
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MIN_TAU_MS = 50f
        const val MAX_TAU_MS = 180_000f
        /** Readout gains are allowed to be larger than synaptic weights; see [DefaultRateCheckpoint]. */
        const val OUTPUT_WEIGHT_BOUND = 32f

        val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        /** Parses and validates; throws [IllegalArgumentException] on any problem. */
        fun fromJson(json: String): NeuralCheckpoint {
            val parsed = try {
                JSON.decodeFromString<NeuralCheckpoint>(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("unreadable neural checkpoint: ${e.message}", e)
            }
            return parsed.requireValid()
        }

        /** The hand-initialised rate network that ships with the app. */
        fun default(): NeuralCheckpoint = DefaultRateCheckpoint.build()

        fun defaultSpiking(): NeuralCheckpoint = DefaultRateCheckpoint.buildSpiking()
    }
}
