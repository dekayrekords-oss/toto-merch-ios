package com.meowmba.shared.neural

/** Which kind of network is running. Both satisfy [NeuralCore]. */
enum class NeuralSubstrate { RATE, SPIKING }

/**
 * Cheap, allocation-free view of a core's health. Read by the controller's
 * diagnostics and by the Settings section; never used for control.
 */
data class CoreDiagnostics(
    val substrate: NeuralSubstrate,
    val units: Int,
    val meanAbsActivity: Float,
    val maxAbsActivity: Float,
    /** Units pinned near their bound: a sign the input scale is wrong. */
    val saturatedUnits: Int,
    /** Units that have not moved: unused capacity or a dead pathway. */
    val silentUnits: Int,
    /** Integration substeps taken on the last step. */
    val substeps: Int,
    /** Times the state was reset because it went non-finite. */
    val nanResets: Long,
    val extra: Map<String, Float> = emptyMap(),
)

/**
 * The contract every substrate honours. The controller only ever talks to this.
 *
 * `step` advances the internal state by `dtMs` of simulated time given one
 * input vector laid out per [inputLayout]. `readout` returns the current output
 * vector laid out per [outputLayout] and must be finite and within the
 * checkpoint's declared bounds; the controller falls back to the legacy
 * controller if it is not.
 */
interface NeuralCore {
    val substrate: NeuralSubstrate
    val inputLayout: FeatureLayout
    val outputLayout: FeatureLayout

    fun step(input: FloatArray, dtMs: Float)
    fun readout(): FloatArray
    fun reset()
    fun diagnostics(): CoreDiagnostics
}
