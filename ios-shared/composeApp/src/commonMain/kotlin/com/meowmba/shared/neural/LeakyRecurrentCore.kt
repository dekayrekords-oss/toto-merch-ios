package com.meowmba.shared.neural

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.tanh

/**
 * A continuous-time recurrent network of leaky rate units with spike-frequency
 * adaptation, integrated with explicit Euler substeps.
 *
 * Per unit `i`:
 * ```
 *   u_i      = b_i - a_i + sum_j Win[i][j] x_j + sum_k Wrec[i][k] h_k
 *   tau_i    dh_i/dt = -h_i + tanh(u_i)
 *   tauA_i   da_i/dt = -a_i + g_i * h_i
 * ```
 * so every unit has its own time constant (fast relays, slow traces), a real
 * recurrent term (self-excitation lengthens memory, mutual inhibition makes
 * attention competitive), and an adaptation variable that habituates to a
 * sustained drive. The readout is affine in the state and clamped to the
 * checkpoint's declared bounds:
 * ```
 *   y_o = clamp(baseline_o + sum_i Wout[o][i] h_i, min_o, max_o)
 * ```
 * With zero bias, `h = 0` is a fixed point of the zero-input dynamics, so a
 * checkpoint's `outputBaseline` is exactly what the network reads at rest.
 *
 * The substep is at most a quarter of the smallest time constant, which keeps
 * explicit Euler stable for tanh units, and the total is frame-rate
 * independent to first order: one 200 ms step and twenty 10 ms steps agree to
 * the integration tolerance. State is bounded by construction (tanh) and
 * additionally guarded: a non-finite value anywhere resets the state and is
 * counted, so the controller can see it and fall back.
 *
 * Pure Kotlin, no allocation in [step] beyond the readout copy.
 */
class LeakyRecurrentCore(
    checkpoint: NeuralCheckpoint,
) : NeuralCore {

    private val ckpt = checkpoint.requireValid().also {
        require(it.substrate == NeuralSubstrate.RATE && it.rate != null) { "not a rate checkpoint" }
    }
    private val w: RateWeights = ckpt.rate!!

    override val substrate: NeuralSubstrate get() = NeuralSubstrate.RATE
    override val inputLayout: FeatureLayout = ckpt.inputs
    override val outputLayout: FeatureLayout = ckpt.outputs

    val units: Int = w.units
    val populations: List<UnitPopulation> get() = w.populations

    private val inputs = inputLayout.size
    private val outputs = outputLayout.size

    private val tau = FloatArray(units) { w.tauMs[it] }
    private val adaptTau = FloatArray(units) { w.adaptTauMs[it] }
    private val adaptGain = FloatArray(units) { w.adaptGain[it] }
    private val bias = FloatArray(units) { w.bias[it] }
    private val win = FloatArray(units * inputs) { w.inputWeights[it] }
    private val wrec = FloatArray(units * units) { w.recurrentWeights[it] }
    private val wout = FloatArray(outputs * units) { w.outputWeights[it] }
    private val baseline = FloatArray(outputs) { ckpt.outputBaseline[it] }
    private val outMin = FloatArray(outputs) { ckpt.outputMin[it] }
    private val outMax = FloatArray(outputs) { ckpt.outputMax[it] }

    /** Maximum stable substep: a quarter of the fastest unit. */
    private val maxSubstepMs: Float = tau.min() / 4f

    private val h = FloatArray(units)
    private val a = FloatArray(units)
    private val drive = FloatArray(units)
    private val hNext = FloatArray(units)
    private val everActive = BooleanArray(units)

    private var lastSubsteps = 0
    private var nanResets = 0L

    /** Direct read of unit `i`'s activity, for tests and diagnostics. */
    fun activity(i: Int): Float = h[i]

    fun adaptation(i: Int): Float = a[i]

    /** Copy of the whole state, for the trace recorder and the trainer. */
    fun state(): FloatArray = h.copyOf()

    override fun step(input: FloatArray, dtMs: Float) {
        require(input.size == inputs) { "input has ${input.size} features, core expects $inputs" }
        if (!(dtMs > 0f) || !dtMs.isFinite()) return

        val n = ceil(dtMs / maxSubstepMs).toInt().coerceAtLeast(1)
        val sub = dtMs / n
        lastSubsteps = n

        repeat(n) {
            // Input drive is constant over the step; compute it once per substep
            // together with the recurrent term.
            for (i in 0 until units) {
                var u = bias[i] - a[i]
                val rowIn = i * inputs
                for (j in 0 until inputs) {
                    val x = input[j]
                    if (x != 0f) u += win[rowIn + j] * x
                }
                val rowRec = i * units
                for (k in 0 until units) {
                    val hk = h[k]
                    if (hk != 0f) u += wrec[rowRec + k] * hk
                }
                drive[i] = u
            }
            for (i in 0 until units) {
                val target = tanh(drive[i])
                val hi = h[i] + (sub / tau[i]) * (target - h[i])
                hNext[i] = hi.coerceIn(-1f, 1f)
            }
            for (i in 0 until units) {
                val hi = hNext[i]
                h[i] = hi
                if (adaptGain[i] != 0f) {
                    a[i] += (sub / adaptTau[i]) * (adaptGain[i] * hi - a[i])
                }
                if (hi != 0f) everActive[i] = true
            }
        }

        var finite = true
        for (i in 0 until units) {
            if (!h[i].isFinite() || !a[i].isFinite()) {
                finite = false
                break
            }
        }
        if (!finite) {
            nanResets++
            h.fill(0f)
            a.fill(0f)
        }
    }

    override fun readout(): FloatArray {
        val y = FloatArray(outputs)
        for (o in 0 until outputs) {
            var acc = baseline[o]
            val row = o * units
            for (i in 0 until units) {
                val hi = h[i]
                if (hi != 0f) acc += wout[row + i] * hi
            }
            y[o] = if (acc.isFinite()) acc.coerceIn(outMin[o], outMax[o]) else baseline[o]
        }
        return y
    }

    override fun reset() {
        h.fill(0f)
        a.fill(0f)
        everActive.fill(false)
        lastSubsteps = 0
    }

    override fun diagnostics(): CoreDiagnostics {
        var sum = 0f
        var max = 0f
        var saturated = 0
        var silent = 0
        for (i in 0 until units) {
            val v = abs(h[i])
            sum += v
            if (v > max) max = v
            if (v > SATURATION) saturated++
            if (!everActive[i]) silent++
        }
        val extra = HashMap<String, Float>()
        for (p in populations) {
            var s = 0f
            for (i in p.start until min(p.end, units)) s += abs(h[i])
            extra["pop.${p.name}"] = s / (p.end - p.start).coerceAtLeast(1)
        }
        return CoreDiagnostics(
            substrate = NeuralSubstrate.RATE,
            units = units,
            meanAbsActivity = sum / units,
            maxAbsActivity = max,
            saturatedUnits = saturated,
            silentUnits = silent,
            substeps = lastSubsteps,
            nanResets = nanResets,
            extra = extra,
        )
    }

    private companion object {
        const val SATURATION = 0.98f
    }
}
