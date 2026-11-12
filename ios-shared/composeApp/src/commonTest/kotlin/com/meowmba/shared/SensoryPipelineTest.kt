package com.meowmba.shared

import com.meowmba.shared.behavior.SocialTarget
import com.meowmba.shared.neural.ChannelKey
import com.meowmba.shared.neural.FeatureEncoder
import com.meowmba.shared.neural.IntentEnvelope
import com.meowmba.shared.neural.NeuralFeatures
import com.meowmba.shared.neural.SensoryBatch
import com.meowmba.shared.neural.SensoryEvent
import com.meowmba.shared.neural.SensoryEventBus
import com.meowmba.shared.neural.SensoryKind
import com.meowmba.shared.neural.SensorySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from the original's `app/src/test/.../neural/SensoryPipelineTest.kt`.
 *
 * This is the *second* neural test file in the Android repo and it was missed
 * during the first pass, so none of its 16 tests were covered. It exercises the
 * two halves of the sensory pipeline that sit either side of the network:
 * `SensoryEventBus` (coalescing, bounded FIFO, staleness, synthesised
 * `SENSOR_LOST`) and `FeatureEncoder` (decay, clamping, signed affect, intent
 * gating). Both were already ported; these tests are what proves they still
 * behave.
 *
 * Only the assertion dialect changed — `org.junit` to `kotlin.test`, whose
 * tolerance overloads exist for `Float` and whose `assertTrue`/`assertFalse`
 * take the message *last*.
 */
class SensoryEventBusTest {

    private fun face(presence: Float, t: Long) = SensoryEvent(
        SensoryKind.FACE_PRESENCE, SensorySource.FACE,
        floatArrayOf(presence, 0.9f, 0.5f, 0f, 0f, 0f, 0.33f), 1f, t,
    )

    @Test
    fun continuousChannelsCoalesceToLatestValue() {
        val bus = SensoryEventBus()
        for (i in 0 until 30) bus.offer(face(i / 30f, 1000L + i), 1000L + i)
        val batch = bus.drain(1030L)
        assertEquals(1, batch.continuous.size)
        val key = ChannelKey(SensorySource.FACE, SensoryKind.FACE_PRESENCE)
        assertEquals(29 / 30f, batch.continuous.getValue(key).values[0], 1e-6f)
        assertEquals(30L, bus.accepted)
    }

    @Test
    fun transitionsKeepOrderAndDropOldestOnOverflow() {
        val bus = SensoryEventBus(transitionCapacity = 4)
        for (i in 0 until 6) {
            bus.offer(SensoryEvent.impulse(SensoryKind.TOUCH, (i + 1) / 10f, SensorySource.TOUCH), 100L + i)
        }
        val batch = bus.drain(200L)
        assertEquals(4, batch.transitions.size)
        assertEquals(listOf(0.3f, 0.4f, 0.5f, 0.6f), batch.transitions.map { it.magnitude })
        assertEquals(2L, bus.droppedTransitions)
        assertEquals(0, bus.pendingTransitions())
    }

    @Test
    fun staleEventsAreRejectedOnArrival() {
        val bus = SensoryEventBus()
        val old = SensoryEvent.impulse(SensoryKind.LOUD_SOUND, 1f, SensorySource.VOICE, timestampMs = 0L)
        assertFalse(bus.offer(old, 5_000L))
        assertEquals(1L, bus.rejectedStale)
        assertTrue(bus.drain(5_000L).isEmpty)
    }

    @Test
    fun unstampedEventsAreStampedOnArrival() {
        val bus = SensoryEventBus()
        assertTrue(bus.offer(SensoryEvent.impulse(SensoryKind.PRAISE, 1f, SensorySource.INTERACTION), 42L))
        assertEquals(42L, bus.drain(42L).transitions.single().timestampMs)
    }

    @Test
    fun staleContinuousChannelSynthesisesSensorLostOnce() {
        val bus = SensoryEventBus()
        bus.offer(face(1f, 1000L), 1000L)
        val fresh = bus.drain(1500L)
        assertEquals(1, fresh.continuous.size)
        assertTrue(fresh.transitions.isEmpty())

        val stale = bus.drain(3000L)
        assertTrue(stale.continuous.isEmpty())
        assertEquals(listOf(SensoryKind.SENSOR_LOST), stale.transitions.map { it.kind })
        assertEquals(SensorySource.FACE, stale.transitions.single().source)
        assertEquals(1, stale.lostChannels.size)

        val again = bus.drain(4000L)
        assertTrue(again.transitions.isEmpty(), "SENSOR_LOST must not repeat while the channel stays silent")

        bus.offer(face(1f, 4500L), 4500L)
        bus.drain(4500L)
        val lostAgain = bus.drain(9000L)
        assertEquals(1, lostAgain.transitions.size, "a channel that came back and went away again reports loss again")
    }

    @Test
    fun nonFiniteValuesAreSanitisedToZero() {
        val bus = SensoryEventBus()
        bus.offer(SensoryEvent.impulse(SensoryKind.TOUCH, Float.NaN, SensorySource.TOUCH), 10L)
        bus.offer(SensoryEvent(SensoryKind.VOICE_ACTIVITY, SensorySource.VOICE, floatArrayOf(Float.POSITIVE_INFINITY, 0.5f)), 10L)
        val batch = bus.drain(10L)
        assertEquals(0f, batch.transitions.single().magnitude, 0f)
        val voice = batch.continuous.getValue(ChannelKey(SensorySource.VOICE, SensoryKind.VOICE_ACTIVITY))
        assertEquals(0f, voice.values[0], 0f)
        assertEquals(0.5f, voice.values[1], 0f)
    }

    @Test
    fun clearDropsEverything() {
        val bus = SensoryEventBus()
        bus.offer(face(1f, 1L), 1L)
        bus.offer(SensoryEvent.impulse(SensoryKind.TOUCH, 1f, SensorySource.TOUCH), 1L)
        bus.clear()
        assertTrue(bus.drain(1L).isEmpty)
    }

    @Test
    fun arityIsEnforced() {
        assertFailsWith<IllegalArgumentException> {
            SensoryEvent(SensoryKind.FACE_PRESENCE, SensorySource.FACE, floatArrayOf(1f))
        }
    }
}

class FeatureEncoderTest {

    private val layout = NeuralFeatures.INPUT

    private fun touchBatch(magnitude: Float = 1f) = SensoryBatch(
        emptyMap(),
        listOf(SensoryEvent.impulse(SensoryKind.TOUCH, magnitude, SensorySource.TOUCH, timestampMs = 0L)),
        emptyList(),
    )

    @Test
    fun impulseDecayIsFrameRateIndependent() {
        val coarse = FeatureEncoder()
        val fine = FeatureEncoder()
        coarse.encode(touchBatch(), null, 0f, 0L)
        fine.encode(touchBatch(), null, 0f, 0L)

        val coarseX = coarse.encode(SensoryBatch.EMPTY, null, 200f, 200L)
        var fineX = FloatArray(0)
        for (i in 1..20) fineX = fine.encode(SensoryBatch.EMPTY, null, 10f, (10 * i).toLong())

        val i = layout.index(NeuralFeatures.TOUCH)
        assertTrue(coarseX[i] > 0.5f)
        assertEquals(coarseX[i], fineX[i], 1e-5f)
    }

    @Test
    fun impulsesAccumulateButClampToOne() {
        val enc = FeatureEncoder()
        val batch = SensoryBatch(
            emptyMap(),
            List(5) { SensoryEvent.impulse(SensoryKind.PRAISE, 0.5f, SensorySource.INTERACTION, timestampMs = 0L) },
            emptyList(),
        )
        val x = enc.encode(batch, null, 0f, 0L)
        assertEquals(1f, x[layout.index(NeuralFeatures.PRAISE)], 0f)
    }

    @Test
    fun affectIsSignedAndOtherImpulsesAreNot() {
        val enc = FeatureEncoder()
        val batch = SensoryBatch(
            emptyMap(),
            listOf(SensoryEvent.impulse(SensoryKind.AFFECT, -0.7f, SensorySource.INTERACTION, timestampMs = 0L)),
            emptyList(),
        )
        val x = enc.encode(batch, null, 0f, 0L)
        assertEquals(-0.7f, x[layout.index(NeuralFeatures.AFFECT)], 1e-6f)
    }

    @Test
    fun continuousChannelsReadZeroWhenAbsent() {
        val enc = FeatureEncoder()
        val present = SensoryBatch(
            mapOf(
                ChannelKey(SensorySource.FACE, SensoryKind.FACE_PRESENCE) to SensoryEvent(
                    SensoryKind.FACE_PRESENCE, SensorySource.FACE, floatArrayOf(1f, 0.8f, 0.6f, 0.2f, -0.1f, 1f, 0.33f), 1f, 0L,
                ),
            ),
            emptyList(), emptyList(),
        )
        val x1 = enc.encode(present, null, 100f, 100L)
        assertEquals(1f, x1[layout.index(NeuralFeatures.FACE_PRESENCE)], 0f)
        assertEquals(0.2f, x1[layout.index(NeuralFeatures.FACE_OFFSET_X)], 1e-6f)
        val x2 = enc.encode(SensoryBatch.EMPTY, null, 100f, 200L)
        assertEquals(0f, x2[layout.index(NeuralFeatures.FACE_PRESENCE)], 0f)
    }

    @Test
    fun confidenceScalesContinuousValues() {
        val enc = FeatureEncoder()
        val batch = SensoryBatch(
            mapOf(
                ChannelKey(SensorySource.VOICE, SensoryKind.VOICE_ACTIVITY) to SensoryEvent(
                    SensoryKind.VOICE_ACTIVITY, SensorySource.VOICE, floatArrayOf(1f, 0.8f), 0.5f, 0L,
                ),
            ),
            emptyList(), emptyList(),
        )
        val x = enc.encode(batch, null, 0f, 0L)
        assertEquals(0.5f, x[layout.index(NeuralFeatures.VOICE_ACTIVITY)], 1e-6f)
        assertEquals(0.4f, x[layout.index(NeuralFeatures.VOICE_LOUDNESS)], 1e-6f)
    }

    @Test
    fun intentIsEncodedOnlyWhileLive() {
        val enc = FeatureEncoder()
        val intent = IntentEnvelope(target = SocialTarget.USER, approach = 0.65f, issuedAtMs = 0L, ttlMs = 1_000L)
        val live = enc.encode(SensoryBatch.EMPTY, intent, 0f, 500L)
        assertEquals(1f, live[layout.index(NeuralFeatures.INTENT_USER)], 0f)
        assertEquals(0.65f, live[layout.index(NeuralFeatures.INTENT_APPROACH)], 1e-6f)
        assertEquals(1f, live[layout.index(NeuralFeatures.INTENT_APPROACH_SET)], 0f)

        val expired = enc.encode(SensoryBatch.EMPTY, intent, 0f, 2_000L)
        assertEquals(0f, expired[layout.index(NeuralFeatures.INTENT_USER)], 0f)
        assertEquals(0f, expired[layout.index(NeuralFeatures.INTENT_APPROACH_SET)], 0f)

        val noApproach = enc.encode(SensoryBatch.EMPTY, intent.copy(approach = null), 0f, 500L)
        assertEquals(0f, noApproach[layout.index(NeuralFeatures.INTENT_APPROACH_SET)], 0f)
    }

    @Test
    fun invalidIntentIsIgnored() {
        val enc = FeatureEncoder()
        val bad = IntentEnvelope(target = SocialTarget.USER, approach = Float.NaN, issuedAtMs = 0L)
        val x = enc.encode(SensoryBatch.EMPTY, bad, 0f, 0L)
        assertEquals(0f, x[layout.index(NeuralFeatures.INTENT_USER)], 0f)
    }

    @Test
    fun outputIsAlwaysFinite() {
        val enc = FeatureEncoder()
        val batch = SensoryBatch(
            mapOf(
                ChannelKey(SensorySource.FACE, SensoryKind.FACE_PRESENCE) to SensoryEvent(
                    SensoryKind.FACE_PRESENCE, SensorySource.FACE,
                    floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY, 0f, 0f, 0f, 0f), Float.NaN, 0L,
                ),
            ),
            emptyList(), emptyList(),
        )
        val x = enc.encode(batch, null, 0f, 0L)
        assertTrue(x.all { it.isFinite() })
    }
}
