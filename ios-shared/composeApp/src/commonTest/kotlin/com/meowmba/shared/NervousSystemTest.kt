package com.meowmba.shared

import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.DriveVector
import com.meowmba.shared.behavior.NervousSystem
import com.meowmba.shared.behavior.SocialTarget
import com.meowmba.shared.behavior.Stimulus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ported from `app/src/test/java/com/example/meowmba/behavior/NervousSystemTest.kt`
 * (19 tests) — the original's own suite, not a rewrite, so a passing run is
 * evidence the port is behaviourally faithful rather than merely compiling.
 *
 * Two mechanical changes: `org.junit.Assert` became `kotlin.test`, which puts the
 * failure message *last* instead of first; and the package moved to
 * `com.meowmba.shared.behavior`.
 *
 * Runs on `jvmTest` and, because the class is pure common Kotlin, on the iOS
 * targets too.
 */
class NervousSystemTest {

    // baseline defaults to the standard resting drives, NOT to `drives`: making
    // the starting state the resting state is what stops anything from moving.
    private fun nervous(
        drives: DriveVector = DriveVector(),
        baseline: DriveVector = DriveVector(),
    ) = NervousSystem(initial = drives, baseline = baseline)

    @Test
    fun `drives relax toward baseline over time`() {
        val n = nervous(drives = DriveVector(curiosity = 1f))
        repeat(200) { n.tick(50) } // 10s, ~3 time constants
        val curiosity = n.snapshot().curiosity
        assertTrue(curiosity < 0.45f, "curiosity should fall toward 0.40, was $curiosity")
    }

    @Test
    fun `one long step equals many short steps`() {
        val coarse = nervous(drives = DriveVector(curiosity = 1f))
        coarse.tick(1_000)

        val fine = nervous(drives = DriveVector(curiosity = 1f))
        repeat(100) { fine.tick(10) }

        assertEquals(
            coarse.snapshot().curiosity,
            fine.snapshot().curiosity,
            1e-3f,
            "frame-rate independence",
        )
    }

    @Test
    fun `novelty raises curiosity and clamps at one`() {
        val n = nervous(drives = DriveVector(curiosity = 0.9f))
        n.onStimulus(Stimulus.NOVELTY, 1f)
        n.onStimulus(Stimulus.NOVELTY, 1f)
        assertEquals(1f, n.snapshot().curiosity, 1e-5f)
    }

    @Test
    fun `threat raises caution and suppresses approach`() {
        val before = nervous(drives = DriveVector(approach = 0.8f, caution = 0.0f))
        val n = nervous(drives = DriveVector(approach = 0.8f, caution = 0.0f))
        n.onStimulus(Stimulus.THREAT, 1f)
        assertTrue(n.snapshot().caution > before.snapshot().caution)
        assertTrue(n.snapshot().approach < before.snapshot().approach)
    }

    @Test
    fun `invalid stimulus magnitude is ignored`() {
        val n = nervous(drives = DriveVector(curiosity = 0.5f))
        n.onStimulus(Stimulus.NOVELTY, -3f)
        n.onStimulus(Stimulus.NOVELTY, Float.NaN)
        n.onStimulus(Stimulus.NOVELTY, Float.POSITIVE_INFINITY)
        assertEquals(0.5f, n.snapshot().curiosity, 1e-5f)
    }

    @Test
    fun `reasoning layer intent sets target and eases approach toward it`() {
        val n = nervous(drives = DriveVector(approach = 0.1f))
        n.setIntent(SocialTarget.USER, approach = 0.9f)
        assertEquals(SocialTarget.USER, n.socialTarget)
        repeat(400) { n.tick(50) } // 20s ~ 5 time constants
        val approach = n.snapshot().approach
        assertTrue(approach > 0.85f, "approach should ease toward 0.9, was $approach")
    }

    @Test
    fun `clearing intent returns approach to baseline`() {
        val n = nervous(drives = DriveVector(approach = 0.2f))
        n.setIntent(SocialTarget.USER, approach = 0.9f)
        repeat(100) { n.tick(50) }
        n.clearIntent()
        assertEquals(SocialTarget.NONE, n.socialTarget)
        repeat(400) { n.tick(50) }
        // Back to the documented resting approach of 0.30, not to the 0.2 it started at.
        assertEquals(0.30f, n.snapshot().approach, 0.02f)
    }

    @Test
    fun `sustained arousal drains energy and rest restores it`() {
        // Drives relax in seconds, so arousal has to be re-fed to stay high:
        // a single stimulus cannot tire the character out over minutes.
        val n = nervous()
        repeat(600) {
            n.onStimulus(Stimulus.THREAT, 1f)
            n.onStimulus(Stimulus.NOVELTY, 1f)
            n.tick(200)
        }
        val drained = n.snapshot().energy
        assertTrue(drained < 0.8f, "energy should drain, was $drained")

        repeat(600) { n.tick(200) } // 2 min of calm
        val rested = n.snapshot().energy
        assertTrue(rested > drained, "energy should recover, was $rested")
    }

    @Test
    fun `bias stays finite and within range`() {
        val n = nervous(drives = DriveVector())
        n.onStimulus(Stimulus.LOUD_SOUND, 1f)
        val b: BehaviorBias = n.bias()
        assertTrue(b.glanceGain.isFinite() && b.glanceGain >= 0f)
        assertTrue(b.followGain.isFinite() && b.followGain >= 0f)
        assertTrue(b.arousal in 0f..1f)
        assertTrue(b.valence in -1f..1f)
        assertTrue(b.blinkRateScale in 0.5f..2f)
        assertTrue(b.restWeight >= 0f && b.inspectWeight >= 0f && b.verticalWeight >= 0f)
    }

    @Test
    fun `caution quiets the character down`() {
        val calm = nervous(drives = DriveVector(caution = 0f, curiosity = 0.8f))
        val wary = nervous(drives = DriveVector(caution = 0.9f, curiosity = 0.8f))
        assertTrue(wary.bias().glanceGain < calm.bias().glanceGain)
        assertTrue(wary.bias().arousal > calm.bias().arousal)
    }

    @Test
    fun `drives never leave zero to one`() {
        val n = nervous(drives = DriveVector(approach = 0.5f, caution = 0.5f))
        repeat(50) { n.onStimulus(Stimulus.THREAT, 1f) }
        repeat(50) { n.onStimulus(Stimulus.SPOKEN_TO, 1f) }
        n.tick(1_000)
        val d = n.snapshot()
        listOf(d.approach, d.social, d.curiosity, d.caution, d.energy).forEach {
            assertTrue(it in 0f..1f, "drive out of range: $d")
        }
    }

    @Test
    fun `resting bias is a safe divisor for the motor layer`() {
        // LumenRig divides live gains by these. If a retune ever pushed one to
        // zero the ratios would become Infinity and quietly NaN the animation,
        // so the resting values must stay strictly positive and finite.
        val b = NervousSystem.REST_BIAS
        listOf(
            "glanceGain" to b.glanceGain, "followGain" to b.followGain,
            "earGain" to b.earGain, "blinkRateScale" to b.blinkRateScale,
            "restWeight" to b.restWeight, "inspectWeight" to b.inspectWeight,
            "verticalWeight" to b.verticalWeight,
        ).forEach { (name, value) ->
            assertTrue(value.isFinite() && value > 0f, "$name must be finite and > 0, was $value")
        }
    }

    @Test
    fun `a resting character reproduces the authored animation`() {
        // The whole point of normalising against REST_BIAS: at rest every ratio
        // is 1.0, so the hand-tuned idle score is untouched.
        val resting = nervous().bias()
        assertEquals(resting.glanceGain, NervousSystem.REST_BIAS.glanceGain, 1e-5f)
        assertEquals(resting.followGain, NervousSystem.REST_BIAS.followGain, 1e-5f)
        assertEquals(resting.earGain, NervousSystem.REST_BIAS.earGain, 1e-5f)
        assertEquals(resting.blinkRateScale, NervousSystem.REST_BIAS.blinkRateScale, 1e-5f)
    }

    @Test
    fun `being touched raises social and settles the character`() {
        val before = nervous()
        val n = nervous()
        n.onStimulus(Stimulus.TOUCH, 1f)
        assertTrue(n.snapshot().social > before.snapshot().social)
        // A petted animal settles rather than springs around, so the small
        // caution rise is the intended reading, not a bug.
        assertTrue(n.bias().glanceGain < before.bias().glanceGain, "touch should quiet the character")
    }

    @Test
    fun `affect lifts valence and then fades back to neutral`() {
        val n = nervous()
        val neutral = n.bias().valence
        n.noteAffect(0.9f)
        val lifted = n.bias().valence
        assertTrue(lifted > neutral + 0.5f, "affect should lift valence, was $lifted")

        repeat(600) { n.tick(200) } // 2 min, ~10 time constants
        val settled = n.bias().valence
        assertEquals(neutral, settled, 0.02f, "valence should settle back to neutral")
    }

    @Test
    fun `affect survives the drive tick re-issuing intent`() {
        // Regression: valence used to be a field on setIntent, and the 5 Hz drive
        // tick calls setIntent with no valence every 200 ms. A reply's affect was
        // therefore erased within one tick and never reached the renderer at all.
        val n = nervous()
        n.noteAffect(0.8f)
        val lifted = n.bias().valence
        repeat(5) {
            n.setIntent(SocialTarget.USER, approach = 0.65f) // exactly what the tick does
            n.tick(200)
        }
        val survived = n.bias().valence
        assertTrue(
            survived > lifted - 0.15f,
            "valence must outlive a re-issued intent: $lifted -> $survived",
        )
    }

    @Test
    fun `non finite affect is ignored`() {
        val n = nervous()
        val before = n.bias().valence
        n.noteAffect(Float.NaN)
        n.noteAffect(Float.POSITIVE_INFINITY)
        n.noteAffect(Float.NEGATIVE_INFINITY)
        assertEquals(before, n.bias().valence, 1e-5f)
    }

    @Test
    fun `repeated warmth compounds but stays bounded`() {
        val n = nervous()
        repeat(20) { n.noteAffect(0.5f) }
        val v = n.bias().valence
        assertTrue(v in -1f..1f, "valence must stay in range, was $v")
        assertTrue(v.isFinite(), "valence must stay finite, was $v")
    }

    @Test
    fun `non finite drives are clamped on construction`() {
        val n = nervous(drives = DriveVector(curiosity = Float.NaN, energy = 5f))
        val d = n.snapshot()
        assertTrue(d.isFinite())
        assertTrue(d.energy <= 1f)
    }

    // --- port-only coverage -------------------------------------------------
    // The original could not test this: it relied on @Volatile/@Synchronized,
    // which the JVM gives for free. The port routes every read and write through
    // PlatformLock, so the lock itself is the thing worth asserting.

    @Test
    fun `social target reads back through the lock`() {
        val n = nervous()
        assertEquals(SocialTarget.NONE, n.socialTarget)
        n.setIntent(SocialTarget.SOUND)
        assertEquals(SocialTarget.SOUND, n.socialTarget)
        n.clearIntent()
        assertEquals(SocialTarget.NONE, n.socialTarget)
    }
}
