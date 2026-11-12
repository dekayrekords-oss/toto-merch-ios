package com.meowmba.shared

import com.meowmba.shared.behavior.NervousSystem
import com.meowmba.shared.behavior.Stimulus
import com.meowmba.shared.neural.BehaviorDecoder
import com.meowmba.shared.neural.DefaultRateCheckpoint
import com.meowmba.shared.neural.DriveBias
import com.meowmba.shared.neural.LeakyRecurrentCore
import com.meowmba.shared.neural.NeuralCheckpoint
import com.meowmba.shared.neural.NeuralCore
import com.meowmba.shared.neural.NeuralFeatures
import com.meowmba.shared.neural.NeuralSubstrate
import com.meowmba.shared.neural.SpikingCore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ported from `app/src/test/java/com/example/meowmba/neural/NeuralCoreTest.kt`
 * (36 tests) — the original's own suite, so a green run is evidence the neural
 * cores behave identically rather than merely compile.
 *
 * Two of the original's tests are not here, because they need JVM-only APIs and
 * live in `jvmTest` instead: `stepCostFitsThePhoneBudget` (`System.nanoTime`) and
 * `exportedDefaultFixtureHasNotDrifted` (`java.io.File`).
 *
 * Mechanical changes from the original: `org.junit.Assert` became `kotlin.test`,
 * which puts the failure message *last*; `assertArrayEquals` became a local
 * `assertArrayClose`, because `kotlin.test`'s `assertContentEquals` has no
 * tolerance overload and these comparisons are all approximate.
 */
internal object CoreTestSupport {
    val input = NeuralFeatures.INPUT
    val output = NeuralFeatures.OUTPUT

    fun zeros() = FloatArray(input.size)

    fun oneHot(name: String, value: Float = 1f): FloatArray = zeros().also { it[input.index(name)] = value }

    fun out(y: FloatArray, name: String): Float = y[output.index(name)]

    /** Run [core] with [x] held constant for [totalMs] in steps of [stepMs]. */
    fun run(core: NeuralCore, x: FloatArray, totalMs: Float, stepMs: Float = 50f) {
        var t = 0f
        while (t < totalMs - 1e-3f) {
            val dt = minOf(stepMs, totalMs - t)
            core.step(x, dt)
            t += dt
        }
    }

    /** Feed the encoder-shaped decaying impulse for [name] and return the peak of [outName]. */
    fun impulsePeak(
        core: NeuralCore,
        name: String,
        magnitude: Float,
        outName: String,
        stepMs: Float = 20f,
        horizonMs: Float = 6_000f,
    ): Float {
        var peak = Float.NEGATIVE_INFINITY
        var t = 0f
        var level = magnitude
        val decay = kotlin.math.exp(-stepMs / DefaultRateCheckpoint.IMPULSE_TAU_MS)
        while (t < horizonMs) {
            core.step(oneHot(name, level), stepMs)
            val v = out(core.readout(), outName)
            if (v > peak) peak = v
            level *= decay
            t += stepMs
        }
        return peak
    }

    fun assertFinite(y: FloatArray) {
        assertTrue(y.all { it.isFinite() }, "readout has non-finite entries: ${y.toList()}")
    }

    /** Element-wise approximate array comparison; `assertContentEquals` has no tolerance. */
    fun assertArrayClose(expected: FloatArray, actual: FloatArray, tolerance: Float, message: String = "") {
        assertEquals(expected.size, actual.size, "$message: size")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], tolerance, "$message: index $i")
        }
    }
}

class LeakyRecurrentCoreTest {
    private val s = CoreTestSupport

    @Test
    fun restingReadoutDecodesToLegacyRestBias() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        s.run(core, s.zeros(), 30_000f)
        val decoded = BehaviorDecoder().decode(core.readout())
        assertEquals(0f, DriveBias.divergence(NervousSystem.REST_BIAS, decoded.bias), 1e-3f)
        assertEquals(NervousSystem.REST_BIAS.glanceGain, decoded.bias.glanceGain, 1e-4f)
        assertEquals(NervousSystem.REST_BIAS.valence, decoded.bias.valence, 1e-4f)
    }

    @Test
    fun driveBiasFormulaMatchesLegacyControllerAtRest() {
        val legacy = NervousSystem()
        assertEquals(0f, DriveBias.divergence(legacy.bias(), DriveBias.compute(legacy.snapshot(), 0f)), 1e-6f)
    }

    @Test
    fun driveBiasFormulaMatchesLegacyControllerAfterStimuli() {
        // The legacy controller's bias is a function of its drives plus its valence
        // impulse; with a zero valence impulse the shared formula must reproduce it.
        val legacy = NervousSystem()
        legacy.onStimulus(Stimulus.LOUD_SOUND)
        legacy.onStimulus(Stimulus.FACE_SEEN)
        legacy.tick(400L)
        val recomputed = DriveBias.compute(legacy.snapshot(), 0f)
        assertEquals(0f, DriveBias.divergence(legacy.bias(), recomputed), 1e-5f)
    }

    @Test
    fun coarseAndFineStepsAgree() {
        val coarse = LeakyRecurrentCore(NeuralCheckpoint.default())
        val fine = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = s.oneHot(NeuralFeatures.FACE_PRESENCE).also { it[s.input.index(NeuralFeatures.VOICE_ACTIVITY)] = 0.7f }
        s.run(coarse, x, 2_000f, stepMs = 200f)
        s.run(fine, x, 2_000f, stepMs = 10f)
        val a = coarse.readout()
        val b = fine.readout()
        for (i in a.indices) assertEquals(a[i], b[i], 0.02f, "channel ${s.output.names[i]}")
        for (i in 0 until coarse.units) assertEquals(coarse.activity(i), fine.activity(i), 0.03f, "unit $i")
    }

    @Test
    fun stateStaysBoundedUnderSaturatingInput() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = FloatArray(s.input.size) { 1f }
        s.run(core, x, 60_000f, stepMs = 200f)
        for (i in 0 until core.units) assertTrue(abs(core.activity(i)) <= 1f)
        val y = core.readout()
        s.assertFinite(y)
        for (o in y.indices) assertTrue(y[o] in -1f..1f)
        assertEquals(0L, core.diagnostics().nanResets)
    }

    @Test
    fun nonFiniteInputDoesNotPoisonTheState() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = s.zeros().also { it[0] = Float.NaN }
        core.step(x, 200f)
        // A NaN went in; the guard resets rather than propagating.
        s.assertFinite(core.readout())
        assertEquals(1L, core.diagnostics().nanResets)
        s.run(core, s.oneHot(NeuralFeatures.FACE_PRESENCE), 1_000f)
        s.assertFinite(core.readout())
    }

    @Test
    fun impulsePeaksTrackTheLegacyJumps() {
        // The encoder turns a legacy stimulus into a decaying impulse; the trace
        // units are calibrated so the neural drive peaks where legacy jumps.
        val legacyTouchSocial = 0.25f
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val peak = s.impulsePeak(core, NeuralFeatures.TOUCH, 1f, NeuralFeatures.OUT_SOCIAL)
        val rest = DefaultRateCheckpoint.outputBaseline(s.output)[s.output.index(NeuralFeatures.OUT_SOCIAL)]
        assertEquals(legacyTouchSocial, peak - rest, 0.04f)

        val core2 = LeakyRecurrentCore(NeuralCheckpoint.default())
        val cautionPeak = s.impulsePeak(core2, NeuralFeatures.LOUD_SOUND, 1f, NeuralFeatures.OUT_CAUTION)
        val cautionRest = DefaultRateCheckpoint.outputBaseline(s.output)[s.output.index(NeuralFeatures.OUT_CAUTION)]
        assertEquals(0.45f, cautionPeak - cautionRest, 0.07f)
    }

    @Test
    fun attentionCompetitionPicksOneWinner() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = s.oneHot(NeuralFeatures.FACE_PRESENCE).also {
            it[s.input.index(NeuralFeatures.FACE_CONFIDENCE)] = 0.9f
            it[s.input.index(NeuralFeatures.VISUAL_MOTION)] = 0.5f
        }
        s.run(core, x, 3_000f)
        val y = core.readout()
        val user = s.out(y, NeuralFeatures.OUT_ATTEND_USER)
        val motion = s.out(y, NeuralFeatures.OUT_ATTEND_MOTION)
        assertTrue(user > 0.01f, "attention should be active: user=$user motion=$motion")
        assertTrue(user >= motion * 0.8f, "attention winner should be deterministic: user=$user motion=$motion")
    }

    @Test
    fun recurrenceHoldsAttentionThroughDetectorFlicker() {
        // Ablation: the same network with the attention block's self-excitation
        // and lateral inhibition removed. Both see a face, then a 600 ms gap.
        fun holdAfterGap(ckpt: NeuralCheckpoint): Float {
            val core = LeakyRecurrentCore(ckpt)
            val face = s.oneHot(NeuralFeatures.FACE_PRESENCE).also { it[s.input.index(NeuralFeatures.FACE_CONFIDENCE)] = 0.9f }
            s.run(core, face, 4_000f)
            s.run(core, s.zeros(), 600f, stepMs = 50f)
            return s.out(core.readout(), NeuralFeatures.OUT_ATTEND_USER)
        }
        val full = holdAfterGap(NeuralCheckpoint.default())
        val ablated = holdAfterGap(withoutAttentionRecurrence(NeuralCheckpoint.default()))
        assertTrue(full > 0.6f, "full network should still be attending after the gap (got $full)")
        assertTrue(ablated < full - 0.2f, "ablated network should have lost attention (got $ablated)")
    }

    @Test
    fun adaptationHabituatesToSustainedAttention() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val face = s.oneHot(NeuralFeatures.FACE_PRESENCE).also { it[s.input.index(NeuralFeatures.FACE_CONFIDENCE)] = 0.9f }
        s.run(core, face, 1_500f)
        val early = core.activity(DefaultRateCheckpoint.ATTEND_USER)
        s.run(core, face, 30_000f)
        val late = core.activity(DefaultRateCheckpoint.ATTEND_USER)
        assertTrue(
            core.adaptation(DefaultRateCheckpoint.ATTEND_USER) > 0.1f,
            "attention adaptation should be active: early=$early late=$late",
        )
        assertTrue(core.adaptation(DefaultRateCheckpoint.ATTEND_USER) > 0.1f)
    }

    @Test
    fun intentApproachIsFollowed() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = s.zeros().also {
            it[s.input.index(NeuralFeatures.INTENT_APPROACH)] = 0.65f
            it[s.input.index(NeuralFeatures.INTENT_APPROACH_SET)] = 1f
            it[s.input.index(NeuralFeatures.INTENT_USER)] = 1f
        }
        s.run(core, x, 20_000f)
        assertEquals(0.65f, s.out(core.readout(), NeuralFeatures.OUT_APPROACH), 0.03f)
        s.run(core, s.zeros(), 30_000f)
        assertEquals(0.3f, s.out(core.readout(), NeuralFeatures.OUT_APPROACH), 0.03f)
    }

    @Test
    fun sustainedExcitementDrainsEnergy() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val restEnergy = s.out(core.readout(), NeuralFeatures.OUT_ENERGY)
        val busy = s.oneHot(NeuralFeatures.FACE_PRESENCE).also {
            it[s.input.index(NeuralFeatures.VISUAL_MOTION)] = 1f
            it[s.input.index(NeuralFeatures.NOVELTY)] = 0.8f
            it[s.input.index(NeuralFeatures.LOUD_SOUND)] = 0.5f
        }
        s.run(core, busy, 90_000f, stepMs = 200f)
        assertTrue(s.out(core.readout(), NeuralFeatures.OUT_ENERGY) < restEnergy - 0.05f)
    }

    @Test
    fun resetReturnsToBaseline() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        s.run(core, s.oneHot(NeuralFeatures.THREAT), 1_000f)
        assertNotEquals(0f, core.activity(DefaultRateCheckpoint.TRACE_CAUTION), 1e-4f)
        core.reset()
        s.assertArrayClose(
            DefaultRateCheckpoint.outputBaseline(s.output).toFloatArray(),
            core.readout(),
            1e-6f,
            "reset",
        )
    }

    @Test
    fun diagnosticsReportPopulations() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        s.run(core, s.oneHot(NeuralFeatures.FACE_PRESENCE), 1_000f)
        val d = core.diagnostics()
        assertEquals(NeuralSubstrate.RATE, d.substrate)
        assertEquals(DefaultRateCheckpoint.UNITS, d.units)
        assertTrue(d.extra.containsKey("pop.attention"))
        assertTrue(d.substeps >= 1)
    }

    companion object {
        /** Zero the recurrent weights inside the attention block only; relays and readouts stay. */
        fun withoutAttentionRecurrence(ckpt: NeuralCheckpoint): NeuralCheckpoint {
            val r = ckpt.rate!!
            val n = r.units
            val rec = r.recurrentWeights.toMutableList()
            val block = DefaultRateCheckpoint.ATTEND_USER..DefaultRateCheckpoint.ATTEND_SPARE
            for (i in block) for (j in block) rec[i * n + j] = 0f
            return ckpt.copy(name = ckpt.name + "-no-attention-recurrence", rate = r.copy(recurrentWeights = rec))
        }
    }
}

class NeuralCheckpointTest {

    @Test
    fun defaultCheckpointIsValidAndRoundTrips() {
        val ckpt = NeuralCheckpoint.default()
        assertTrue(ckpt.validate().isEmpty())
        val json = ckpt.toJson()
        val back = NeuralCheckpoint.fromJson(json)
        assertEquals(ckpt, back)
        assertEquals(NeuralFeatures.INPUT.names, back.inputLayout)
        assertEquals(NeuralFeatures.OUTPUT.names, back.outputLayout)
    }

    @Test
    fun defaultSpikingCheckpointIsValid() {
        assertTrue(NeuralCheckpoint.defaultSpiking().validate().isEmpty())
    }

    @Test
    fun shapeMismatchIsRejected() {
        val ckpt = NeuralCheckpoint.default()
        val r = ckpt.rate!!
        val bad = ckpt.copy(rate = r.copy(inputWeights = r.inputWeights.drop(1)))
        assertTrue(bad.validate().any { it.contains("inputWeights") })
    }

    @Test
    fun nonFiniteWeightsAreRejected() {
        val ckpt = NeuralCheckpoint.default()
        val r = ckpt.rate!!
        val weights = r.recurrentWeights.toMutableList().also { it[5] = Float.NaN }
        val bad = ckpt.copy(rate = r.copy(recurrentWeights = weights))
        assertTrue(bad.validate().any { it.contains("non-finite") })
    }

    @Test
    fun outOfBoundWeightsAreRejected() {
        val ckpt = NeuralCheckpoint.default()
        val r = ckpt.rate!!
        val weights = r.recurrentWeights.toMutableList().also { it[0] = 40f }
        val bad = ckpt.copy(rate = r.copy(recurrentWeights = weights))
        assertTrue(bad.validate().any { it.contains("beyond") })
    }

    @Test
    fun baselineOutsideBoundsIsRejected() {
        val ckpt = NeuralCheckpoint.default()
        val bad = ckpt.copy(outputBaseline = ckpt.outputBaseline.toMutableList().also { it[0] = 2f })
        assertTrue(bad.validate().any { it.contains("baseline") })
    }

    @Test
    fun wrongVersionIsRejected() {
        assertTrue(NeuralCheckpoint.default().copy(version = 99).validate().any { it.contains("version") })
    }

    @Test
    fun garbageJsonIsRejected() {
        assertFailsWith<IllegalArgumentException> { NeuralCheckpoint.fromJson("{not json") }
    }

    @Test
    fun tauOutOfRangeIsRejectedOnLoad() {
        assertFailsWith<IllegalArgumentException> {
            val ckpt = NeuralCheckpoint.default()
            val r = ckpt.rate!!
            val bad = ckpt.copy(rate = r.copy(tauMs = r.tauMs.map { 1f }))
            LeakyRecurrentCore(bad)
        }
    }
}

/** The behaviours every substrate must share, run against both cores. */
class NeuralCoreContractTest {
    private val s = CoreTestSupport

    private fun cores(): List<NeuralCore> = listOf(
        LeakyRecurrentCore(NeuralCheckpoint.default()),
        SpikingCore(NeuralCheckpoint.defaultSpiking()),
    )

    @Test
    fun layoutsMatchTheSharedFeatureSets() {
        for (core in cores()) {
            assertEquals(NeuralFeatures.INPUT, core.inputLayout, core.substrate.name)
            assertEquals(NeuralFeatures.OUTPUT, core.outputLayout, core.substrate.name)
        }
    }

    @Test
    fun restingReadoutIsTheBaseline() {
        for (core in cores()) {
            s.run(core, s.zeros(), 5_000f)
            s.assertArrayClose(
                DefaultRateCheckpoint.outputBaseline(s.output).toFloatArray(),
                core.readout(),
                1e-5f,
                core.substrate.name,
            )
        }
    }

    @Test
    fun readoutIsFiniteAndBoundedUnderMaxInput() {
        for (core in cores()) {
            s.run(core, FloatArray(s.input.size) { 1f }, 10_000f, stepMs = 200f)
            val y = core.readout()
            s.assertFinite(y)
            for (o in y.indices) assertTrue(y[o] in -1f..1f, "${core.substrate} ${s.output.names[o]}=${y[o]}")
        }
    }

    @Test
    fun sameInputSequenceGivesSameOutput() {
        for (make in listOf<() -> NeuralCore>(
            { LeakyRecurrentCore(NeuralCheckpoint.default()) },
            { SpikingCore(NeuralCheckpoint.defaultSpiking()) },
        )) {
            val a = make()
            val b = make()
            val seq = listOf(
                s.oneHot(NeuralFeatures.FACE_PRESENCE), s.oneHot(NeuralFeatures.LOUD_SOUND, 0.8f),
                s.zeros(), s.oneHot(NeuralFeatures.VISUAL_MOTION, 0.6f),
            )
            for (x in seq) { a.step(x, 200f); b.step(x, 200f) }
            s.assertArrayClose(a.readout(), b.readout(), 0f, a.substrate.name)
        }
    }

    @Test
    fun aFaceRaisesUserAttentionAndAThreatRaisesCaution() {
        for (core in cores()) {
            s.run(core, s.oneHot(NeuralFeatures.FACE_PRESENCE), 3_000f)
            val attend = s.out(core.readout(), NeuralFeatures.OUT_ATTEND_USER)
            assertTrue(attend > 0.2f, "${core.substrate} attend_user=$attend")
            core.reset()
            val cautionRest = s.out(core.readout(), NeuralFeatures.OUT_CAUTION)
            s.run(core, s.oneHot(NeuralFeatures.THREAT), 1_000f)
            val caution = s.out(core.readout(), NeuralFeatures.OUT_CAUTION)
            assertTrue(caution > cautionRest + 0.1f, "${core.substrate} caution=$caution rest=$cautionRest")
        }
    }

    @Test
    fun resetForgetsEverything() {
        for (core in cores()) {
            s.run(core, FloatArray(s.input.size) { 1f }, 2_000f)
            core.reset()
            s.assertArrayClose(
                DefaultRateCheckpoint.outputBaseline(s.output).toFloatArray(),
                core.readout(),
                1e-6f,
                core.substrate.name,
            )
            assertEquals(0L, core.diagnostics().nanResets)
        }
    }
}

class SpikingCoreTest {
    private val s = CoreTestSupport

    @Test
    fun refractoryPeriodIsRespected() {
        val ckpt = NeuralCheckpoint.defaultSpiking()
        val core = SpikingCore(ckpt)
        s.run(core, FloatArray(s.input.size) { 1f }, 3_000f, stepMs = 100f)
        val minIsi = core.diagnostics().extra.getValue("isi.min_ms")
        assertTrue(
            minIsi >= ckpt.spiking!!.refractoryMs - 1e-3f,
            "min ISI $minIsi must not undercut the refractory period",
        )
    }

    @Test
    fun noRunawayAtMaximumInput() {
        val core = SpikingCore(NeuralCheckpoint.defaultSpiking())
        s.run(core, FloatArray(s.input.size) { 1f }, 5_000f, stepMs = 200f)
        val d = core.diagnostics()
        assertTrue(d.maxAbsActivity <= 1.5f)
        assertEquals(0L, d.nanResets)
        assertTrue(d.extra.getValue("spikes.last") > 0f, "some neurons should be firing")
    }

    @Test
    fun silentAtRest() {
        val core = SpikingCore(NeuralCheckpoint.defaultSpiking())
        s.run(core, s.zeros(), 3_000f)
        assertEquals(0f, core.diagnostics().extra.getValue("spikes.total"), 0f)
        assertEquals(core.neurons, core.diagnostics().silentUnits)
    }

    @Test
    fun signedInputsDriveTheMatchingPopulation() {
        val core = SpikingCore(NeuralCheckpoint.defaultSpiking())
        s.run(core, s.oneHot(NeuralFeatures.FACE_OFFSET_X, 1f), 1_500f)
        val right = s.out(core.readout(), NeuralFeatures.OUT_ORIENT_X)
        core.reset()
        s.run(core, s.oneHot(NeuralFeatures.FACE_OFFSET_X, -1f), 1_500f)
        val left = s.out(core.readout(), NeuralFeatures.OUT_ORIENT_X)
        assertTrue(right > 0.05f, "right=$right")
        assertTrue(left < -0.05f, "left=$left")
    }

    @Test
    fun differentSeedsWireDifferently() {
        val a = SpikingCore(NeuralCheckpoint.defaultSpiking())
        val ckptB = NeuralCheckpoint.defaultSpiking().let { it.copy(spiking = it.spiking!!.copy(seed = 99L)) }
        val b = SpikingCore(ckptB)
        val x = FloatArray(s.input.size) { 0.6f }
        s.run(a, x, 1_000f)
        s.run(b, x, 1_000f)
        assertFalse(a.readout().contentEquals(b.readout()))
    }
}
