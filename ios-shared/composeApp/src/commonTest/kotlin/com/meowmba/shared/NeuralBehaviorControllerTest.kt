package com.meowmba.shared

import com.meowmba.shared.behavior.NervousSystem
import com.meowmba.shared.neural.BehaviorDecoder
import com.meowmba.shared.neural.CoreDiagnostics
import com.meowmba.shared.neural.FallbackReason
import com.meowmba.shared.neural.FeatureLayout
import com.meowmba.shared.neural.NeuralBehaviorController
import com.meowmba.shared.neural.NeuralCore
import com.meowmba.shared.neural.NeuralFeatures
import com.meowmba.shared.neural.NeuralMode
import com.meowmba.shared.neural.NeuralSubstrate
import com.meowmba.shared.neural.NeuralTelemetry
import com.meowmba.shared.neural.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `NeuralBehaviorController` has no test file in the Android original either, so
 * again there was nothing to inherit. It is also the file where being wrong is
 * expensive: it is the gate that decides whether a learned network is allowed to
 * drive the avatar at all.
 *
 * These tests therefore concentrate on the *safety* surface rather than the
 * numerics — which mode publishes which bias, and what happens when the core
 * misbehaves. The three automatic fallbacks (non-finite readout, exception,
 * repeated deadline misses), the thermal suspension, and the economy duty cycle
 * are each pinned individually, because each is a promise the settings screen
 * makes to the user.
 *
 * A fake [NeuralCore] is injected through `coreFactory`, and a plain mutable
 * `Long` stands in for the monotonic clock, so a slow step can be simulated
 * without sleeping.
 */
class NeuralBehaviorControllerTest {

    /** A core whose readout and step behaviour the test controls. */
    private class FakeCore(
        override val substrate: NeuralSubstrate = NeuralSubstrate.RATE,
        private val readoutFn: () -> FloatArray = { FloatArray(NeuralFeatures.OUTPUT.size) },
        private val stepFn: (FloatArray, Float) -> Unit = { _, _ -> },
    ) : NeuralCore {
        override val inputLayout: FeatureLayout = NeuralFeatures.INPUT
        override val outputLayout: FeatureLayout = NeuralFeatures.OUTPUT

        var steps = 0
        var resets = 0

        override fun step(input: FloatArray, dtMs: Float) {
            steps++
            stepFn(input, dtMs)
        }

        override fun readout(): FloatArray = readoutFn()

        override fun reset() {
            resets++
        }

        override fun diagnostics(): CoreDiagnostics = CoreDiagnostics(
            substrate = substrate,
            units = 64,
            meanAbsActivity = 0f,
            maxAbsActivity = 0f,
            saturatedUnits = 0,
            silentUnits = 0,
            substeps = 1,
            nanResets = 0,
        )
    }

    /** An output vector with the named channels set; everything else resting. */
    private fun readout(vararg overrides: Pair<String, Float>): FloatArray {
        val v = FloatArray(NeuralFeatures.OUTPUT.size)
        for ((name, value) in overrides) v[NeuralFeatures.OUTPUT.index(name)] = value
        return v
    }

    private fun controller(
        core: FakeCore,
        legacy: NervousSystem = NervousSystem(),
        mode: NeuralMode = NeuralMode.SHADOW,
        thermalTier: () -> Int = { 0 },
        monotonicNanos: () -> Long = { 0L },
        telemetry: NeuralTelemetry = NeuralTelemetry.NONE,
        maxDeadlineMisses: Int = 5,
    ) = NeuralBehaviorController(
        legacy = legacy,
        coreFactory = { core },
        initialMode = mode,
        thermalTier = thermalTier,
        monotonicNanos = monotonicNanos,
        telemetry = telemetry,
        maxDeadlineMisses = maxDeadlineMisses,
    )

    // ---- mode: which bias reaches the motor layer --------------------------

    @Test
    fun legacyModeNeverStepsTheCoreAndPublishesLegacy() {
        val core = FakeCore()
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.LEGACY)

        repeat(3) { c.tick(16L) }

        assertEquals(0, core.steps, "the core must not be stepped at all in LEGACY")
        assertEquals(legacy.bias(), c.bias())
    }

    @Test
    fun shadowModePublishesTheLegacyBiasBitForBit() {
        val core = FakeCore()
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.SHADOW)

        repeat(5) { c.tick(16L) }

        // The whole point of SHADOW: the network runs, and its output is thrown
        // away. Anything else would make the divergence numbers meaningless.
        assertTrue(core.steps > 0, "SHADOW still steps the core")
        assertEquals(legacy.bias(), c.bias())
    }

    @Test
    fun neuralModePublishesTheArbitratedNeuralBiasNotTheLegacyOne() {
        val distinctive = readout(NeuralFeatures.OUT_APPROACH to 1f)
        val core = FakeCore(readoutFn = { distinctive })
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.NEURAL)

        c.tick(16L)

        // The arbiter passes the bias through untouched, so what is published
        // must be exactly what the decoder derived from the readout.
        assertEquals(BehaviorDecoder().decode(distinctive).bias, c.bias())
        // And it must genuinely differ from legacy, or this test proves nothing.
        assertNotEquals(legacy.bias(), c.bias())
    }

    @Test
    fun orientationIsWithheldUnlessTheNeuralLayerIsInControl() {
        val core = FakeCore()
        val c = controller(core, mode = NeuralMode.SHADOW)
        c.tick(16L)

        // SHADOW runs the neural path but is not allowed to steer the gaze.
        assertEquals(Orientation.NONE, c.orientationNow())
    }

    // ---- the three automatic fallbacks ------------------------------------

    @Test
    fun nonFiniteReadoutFallsBackToLegacyAndResetsTheCore() {
        val bad = readout().also { it[0] = Float.NaN }
        val core = FakeCore(readoutFn = { bad })
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.NEURAL)

        c.tick(16L)

        assertEquals(FallbackReason.NON_FINITE_OUTPUT, c.fallback)
        assertEquals(legacy.bias(), c.bias())
        assertTrue(core.resets > 0, "a poisoned core is reset, not trusted again next tick")
    }

    @Test
    fun exceptionInsideTheCoreFallsBackToLegacy() {
        val core = FakeCore(stepFn = { _, _ -> throw IllegalStateException("boom") })
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.NEURAL)

        c.tick(16L)

        assertEquals(FallbackReason.EXCEPTION, c.fallback)
        assertEquals(legacy.bias(), c.bias())
    }

    @Test
    fun repeatedDeadlineMissesFallBackToLegacy() {
        var nanos = 0L
        // Each step burns 30ms against a 20ms deadline.
        val core = FakeCore(stepFn = { _, _ -> nanos += 30_000_000L })
        val legacy = NervousSystem()
        val c = controller(
            core, legacy, mode = NeuralMode.NEURAL,
            monotonicNanos = { nanos }, maxDeadlineMisses = 3,
        )

        repeat(3) { c.tick(16L) }

        assertEquals(FallbackReason.DEADLINE, c.fallback)
        assertEquals(legacy.bias(), c.bias())
        assertEquals(3L, c.diagnostics().deadlineMisses)
    }

    @Test
    fun aFastStepNeverTripsTheDeadlineGuard() {
        var nanos = 0L
        val core = FakeCore(stepFn = { _, _ -> nanos += 1_000_000L }) // 1ms
        val c = controller(core, mode = NeuralMode.NEURAL, monotonicNanos = { nanos })

        repeat(10) { c.tick(16L) }

        assertEquals(FallbackReason.NONE, c.fallback)
        assertEquals(0L, c.diagnostics().deadlineMisses)
    }

    @Test
    fun fallbackIsStickyAndOnlyClearedExplicitly() {
        val bad = readout().also { it[0] = Float.NaN }
        var poisoned = true
        val core = FakeCore(readoutFn = { if (poisoned) bad else readout() })
        val c = controller(core, mode = NeuralMode.NEURAL)

        c.tick(16L)
        assertEquals(FallbackReason.NON_FINITE_OUTPUT, c.fallback)

        // The readout recovers, but the fallback must not silently lift: a
        // network that produced NaN once is not trusted again without a reset.
        poisoned = false
        repeat(3) { c.tick(16L) }
        assertEquals(FallbackReason.NON_FINITE_OUTPUT, c.fallback)

        c.clearFallback()
        assertEquals(FallbackReason.NONE, c.fallback)
    }

    // ---- thermal governor --------------------------------------------------

    @Test
    fun survivalTierSuspendsTheCoreAndPublishesLegacy() {
        val core = FakeCore()
        val legacy = NervousSystem()
        val c = controller(core, legacy, mode = NeuralMode.NEURAL, thermalTier = { 2 })

        c.tick(16L)

        assertEquals(FallbackReason.THERMAL_SUSPENDED, c.fallback)
        assertEquals(legacy.bias(), c.bias())
        assertEquals(0, core.steps, "SURVIVAL suspends the core outright")
    }

    @Test
    fun economyTierHalvesTheStepRate() {
        val core = FakeCore()
        val c = controller(core, mode = NeuralMode.SHADOW, thermalTier = { 1 })

        repeat(4) { c.tick(16L) }

        // Every other tick skips the step, so 4 ticks produce 2 steps.
        assertEquals(2, core.steps)
    }

    // ---- lifecycle ---------------------------------------------------------

    @Test
    fun nonPositiveDeltaIsIgnoredEntirely() {
        val core = FakeCore()
        val c = controller(core)

        c.tick(0L)
        c.tick(-5L)

        assertEquals(0, core.steps)
        assertEquals(0L, c.diagnostics().simTimeMs)
    }

    @Test
    fun setSubstrateSwapsTheCoreThatIsStepped() {
        val rate = FakeCore(NeuralSubstrate.RATE)
        val spiking = FakeCore(NeuralSubstrate.SPIKING)
        val c = NeuralBehaviorController(
            coreFactory = { s -> if (s == NeuralSubstrate.RATE) rate else spiking },
            initialSubstrate = NeuralSubstrate.RATE,
            initialMode = NeuralMode.SHADOW,
            telemetry = NeuralTelemetry.NONE,
        )

        c.setSubstrate(NeuralSubstrate.SPIKING)
        assertEquals(NeuralSubstrate.SPIKING, c.substrate)

        c.tick(16L)
        assertEquals(0, rate.steps, "the retired core is no longer stepped")
        assertEquals(1, spiking.steps)
    }

    @Test
    fun enteringNeuralModeClearsAStickyFallback() {
        val bad = readout().also { it[0] = Float.NaN }
        val core = FakeCore(readoutFn = { bad })
        val c = controller(core, mode = NeuralMode.NEURAL)

        c.tick(16L)
        assertEquals(FallbackReason.NON_FINITE_OUTPUT, c.fallback)

        // Going back to LEGACY and forward again is the documented reset path.
        c.setMode(NeuralMode.LEGACY)
        c.setMode(NeuralMode.NEURAL)

        assertEquals(FallbackReason.NONE, c.fallback)
    }

    // ---- telemetry and diagnostics ----------------------------------------

    @Test
    fun bothLegacyAndNeuralStepsAreChargedToTelemetry() {
        val charged = mutableListOf<String>()
        val core = FakeCore()
        val c = controller(
            core,
            mode = NeuralMode.SHADOW,
            telemetry = NeuralTelemetry { feature, _ -> charged += feature },
        )

        c.tick(16L)

        assertTrue(NeuralTelemetry.FEATURE_LEGACY in charged)
        assertTrue(NeuralTelemetry.FEATURE_STEP in charged)
    }

    @Test
    fun diagnosticsReportsTheRunningState() {
        val core = FakeCore()
        val c = controller(core, mode = NeuralMode.SHADOW)

        c.tick(16L)

        val d = c.diagnostics()
        assertEquals(NeuralMode.SHADOW, d.mode)
        assertEquals(NeuralSubstrate.RATE, d.substrate)
        assertEquals(FallbackReason.NONE, d.fallback)
        assertEquals(0, d.thermalTier)
        assertEquals(16L, d.simTimeMs)
        assertEquals(1L, d.steps)
        assertEquals(0L, d.deadlineMisses)
        assertEquals(0L, d.busDropped)
        assertEquals(0L, d.busRejectedStale)
        assertEquals(64, d.core.units)
    }
}
