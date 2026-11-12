package com.meowmba.shared

import com.meowmba.shared.neural.LeakyRecurrentCore
import com.meowmba.shared.neural.NeuralCheckpoint
import com.meowmba.shared.neural.NeuralFeatures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two tests from the original `NeuralCoreTest.kt` that cannot live in
 * `commonTest`: one needs wall-clock nanoseconds, the other needs `java.io.File`.
 *
 * The second is the most valuable test in the whole neural port, so it is worth
 * being explicit about what it proves. `scripts/neural/default_core.json` is not
 * a hand-written expectation — it is the JSON that the **Android build actually
 * exported** (`app/build/neural/default_core.json`). Asserting that the ported
 * `DefaultRateCheckpoint.build()` reproduces it byte-for-byte means:
 *
 * - the 64-unit wiring, all 4,096 recurrent weights, every time constant and
 *   every readout gain match the original exactly, and
 * - [com.meowmba.shared.core.JavaLcgRandom] really does reproduce
 *   `java.util.Random`'s stream, because the 24 reservoir units' time constants
 *   and sparse connectivity are drawn from it. The Android export's reservoir
 *   taus begin `1329.5461, 1938.672, 348.39062, …`; if the LCG diverged by a
 *   single bit those would not match.
 *
 * A `kotlin.random.Random` substitution would fail this test loudly, which is
 * exactly why it exists.
 */
class NeuralCoreJvmTest {

    /**
     * The original used a repo-relative path that assumed the Android module's
     * working directory. Resolve against both plausible roots so the test does
     * not silently pass by not finding the fixture.
     */
    private fun fixture(name: String): File? = listOf(
        File("../scripts/neural/$name"),
        File("scripts/neural/$name"),
    ).firstOrNull { it.isFile }

    @Test
    fun exportedDefaultFixtureHasNotDrifted() {
        val fresh = NeuralCheckpoint.default()
        val freshSpiking = NeuralCheckpoint.defaultSpiking()

        // Re-export on every run so the artifact is always current.
        val exportDir = File("build/neural").also { it.mkdirs() }
        File(exportDir, "default_core.json").writeText(fresh.toJson())
        File(exportDir, "default_spiking.json").writeText(freshSpiking.toJson())

        val coreFixture = fixture("default_core.json")
        assertTrue(
            coreFixture != null,
            "scripts/neural/default_core.json is missing; the drift check cannot run",
        )
        val pinned = NeuralCheckpoint.fromJson(coreFixture.readText())
        assertEquals(
            pinned,
            fresh,
            "the ported default checkpoint has drifted from the Android build's export",
        )

        fixture("default_spiking.json")?.let { spiking ->
            assertEquals(
                NeuralCheckpoint.fromJson(spiking.readText()),
                freshSpiking,
                "the ported default spiking checkpoint has drifted",
            )
        }
    }

    @Test
    fun reservoirWeightsAreBitIdenticalToTheAndroidExport() {
        // Belt and braces on the part most likely to break: the seeded reservoir.
        // These are literal values read out of the Android export, so this test
        // still fails meaningfully if the fixture itself is ever lost.
        val coreFixture = fixture("default_core.json") ?: return
        val pinned = NeuralCheckpoint.fromJson(coreFixture.readText())
        val fresh = NeuralCheckpoint.default()

        val pinnedRate = pinned.rate ?: error("pinned checkpoint carries no rate weights")
        val freshRate = fresh.rate ?: error("default checkpoint carries no rate weights")

        val a = pinnedRate.tauMs
        val b = freshRate.tauMs
        for (i in 40 until 64) {
            assertEquals(a[i], b[i], 0f, "reservoir unit $i time constant")
        }
        assertEquals(pinnedRate.recurrentWeights, freshRate.recurrentWeights, "recurrent weights")
        assertEquals(pinnedRate.inputWeights, freshRate.inputWeights, "input weights")
        assertEquals(pinnedRate.outputWeights, freshRate.outputWeights, "output weights")
    }

    @Test
    fun stepCostFitsThePhoneBudget() {
        val core = LeakyRecurrentCore(NeuralCheckpoint.default())
        val x = FloatArray(NeuralFeatures.INPUT.size).also { it[NeuralFeatures.INPUT.index(NeuralFeatures.FACE_PRESENCE)] = 1f }
        repeat(50) { core.step(x, 200f) }
        val t0 = System.nanoTime()
        repeat(200) { core.step(x, 200f); core.readout() }
        val perStepMicros = (System.nanoTime() - t0) / 200 / 1_000
        assertTrue(perStepMicros < 5_000, "rate core step took $perStepMicros us on the host")
    }
}
