package com.meowmba.shared

import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.SocialTarget
import com.meowmba.shared.neural.ActionFeedback
import com.meowmba.shared.neural.ActionResult
import com.meowmba.shared.neural.AttentionOwner
import com.meowmba.shared.neural.AttentionOwnership
import com.meowmba.shared.neural.BehaviorAction
import com.meowmba.shared.neural.BehaviorArbiter
import com.meowmba.shared.neural.BehaviorProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `BehaviorArbiter` has no test file in the Android original, so none could be
 * ported. The class documents five guarantees in its KDoc and every one of them
 * is load-bearing for how the character reads on screen — a flickering cat, or
 * one that keeps staring at a person who has walked away, is the visible failure
 * mode. These tests pin each guarantee individually.
 *
 * The arbiter is driven by an explicit `nowMs`, so every test advances a
 * synthetic clock and never sleeps.
 */
class BehaviorArbiterTest {

    // ---- helpers -----------------------------------------------------------

    private fun bias(
        glanceGain: Float = 1f,
        followGain: Float = 1f,
        restWeight: Float = 1f,
        inspectWeight: Float = 1f,
        verticalWeight: Float = 1f,
        earGain: Float = 1f,
        blinkRateScale: Float = 1f,
        arousal: Float = 0.5f,
        valence: Float = 0f,
    ) = BehaviorBias(
        glanceGain, followGain, restWeight, inspectWeight,
        verticalWeight, earGain, blinkRateScale, arousal, valence,
    )

    /** A proposal issued *now*, which is what a real controller does every tick. */
    private fun proposal(
        action: BehaviorAction,
        target: SocialTarget = SocialTarget.USER,
        intensity: Float = 0.7f,
        dirX: Float = 0f,
        dirY: Float = 0f,
        nowMs: Long,
        ttlMs: Long = 600L,
    ) = BehaviorProposal(
        action = action,
        target = target,
        intensity = intensity,
        confidence = 1f,
        dirX = dirX,
        dirY = dirY,
        issuedAtMs = nowMs,
        ttlMs = ttlMs,
    )

    private val free = AttentionOwnership.FREE
    private val b = bias()

    // ---- 1. the closed action set -----------------------------------------

    @Test
    fun allowedActionsAreExactlyTheClosedSet() {
        assertEquals(BehaviorAction.entries.toSet(), BehaviorArbiter.ALLOWED)
        assertEquals(5, BehaviorArbiter.ALLOWED.size)
        // The closed set is the point: nothing downstream can be talked into a
        // privacy or robot action by a learned readout, because none exists here.
        assertTrue(BehaviorAction.STARTLE in BehaviorArbiter.ALLOWED)
    }

    // ---- 2. ownership overrides where to look -----------------------------

    @Test
    fun ownershipForcesOrientTowardTheOwnersTargetAndIgnoresStartle() {
        val arbiter = BehaviorArbiter()

        // A startle toward MOTION is offered while the conversation owns attention.
        val out = arbiter.arbitrate(
            proposals = listOf(proposal(BehaviorAction.STARTLE, SocialTarget.MOTION, nowMs = 100L)),
            bias = b,
            ownership = AttentionOwnership(AttentionOwner.CONVERSATION, SocialTarget.USER),
            nowMs = 100L,
        )

        assertEquals(BehaviorAction.ORIENT, out.action)
        assertEquals(AttentionOwner.CONVERSATION, out.owner)
        assertEquals(SocialTarget.USER, out.orientation.target)
        assertEquals("owned", out.reason)
        // The startle was not merely outranked, it was never counted.
        assertEquals(0L, arbiter.startlesAccepted)
        assertEquals(0L, arbiter.startlesSuppressed)
    }

    @Test
    fun ownedOrientUsesAMatchingGazeProposalWhenOneExists() {
        val arbiter = BehaviorArbiter()

        val out = arbiter.arbitrate(
            proposals = listOf(
                proposal(
                    BehaviorAction.ORIENT, SocialTarget.USER,
                    intensity = 0.8f, dirX = 0.5f, dirY = -0.25f, nowMs = 100L,
                ),
            ),
            bias = b,
            ownership = AttentionOwnership(AttentionOwner.EXPLICIT_COMMAND, SocialTarget.USER),
            nowMs = 100L,
        )

        assertEquals(0.5f, out.orientation.x)
        assertEquals(-0.25f, out.orientation.y)
        assertEquals(0.8f, out.orientation.strength)
        assertEquals(AttentionOwner.EXPLICIT_COMMAND, out.owner)
    }

    @Test
    fun ownedOrientFallsBackToFullStrengthGazeWhenNoProposalMatches() {
        val arbiter = BehaviorArbiter()

        val out = arbiter.arbitrate(
            proposals = emptyList(),
            bias = b,
            ownership = AttentionOwnership(AttentionOwner.CONVERSATION, SocialTarget.USER),
            nowMs = 100L,
        )

        assertEquals(BehaviorAction.ORIENT, out.action)
        assertEquals(1f, out.orientation.strength)
        assertEquals(0f, out.orientation.x)
        assertEquals(0f, out.orientation.y)
    }

    @Test
    fun ownedOrientDoesNotOutliveItsOwner() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            proposals = emptyList(),
            bias = b,
            ownership = AttentionOwnership(AttentionOwner.CONVERSATION, SocialTarget.USER),
            nowMs = 100L,
        )
        assertEquals(BehaviorAction.ORIENT, arbiter.current())

        // The conversation ends. Nothing is proposed.
        val out = arbiter.arbitrate(
            proposals = emptyList(),
            bias = b,
            ownership = free,
            nowMs = 200L,
        )

        assertNotEquals(BehaviorAction.ORIENT, out.action)
        assertEquals(AttentionOwner.NONE, out.owner)
        assertEquals("no-proposal", out.reason)
    }

    @Test
    fun ownershipIsStableAcrossTicksWithoutRestartingTheAction() {
        val arbiter = BehaviorArbiter()
        val owned = AttentionOwnership(AttentionOwner.CONVERSATION, SocialTarget.USER)

        val first = arbiter.arbitrate(emptyList(), b, owned, nowMs = 100L)
        val second = arbiter.arbitrate(emptyList(), b, owned, nowMs = 180L)

        // Same owner, same action, same target: the orient must not restart, or
        // the character would visibly snap back to centre every frame.
        assertEquals(first.sinceMs, second.sinceMs)
        assertEquals(100L, second.sinceMs)
    }

    // ---- 3. startle rate limiting -----------------------------------------

    @Test
    fun startleInterruptsIdleBehaviourAndHolds() {
        val arbiter = BehaviorArbiter()

        val out = arbiter.arbitrate(
            proposals = listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, intensity = 0.9f, nowMs = 10_000L)),
            bias = b,
            ownership = free,
            nowMs = 10_000L,
        )

        assertEquals(BehaviorAction.STARTLE, out.action)
        assertEquals("startle", out.reason)
        assertEquals(0.9f, out.orientation.strength)
        assertEquals(1L, arbiter.startlesAccepted)
        assertEquals(0L, arbiter.startlesSuppressed)
    }

    @Test
    fun startleIsSuppressedInsideTheWindowAndAcceptedAgainAfterIt() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        assertEquals(1L, arbiter.startlesAccepted)

        // 1s later: inside the 4s window. The startle is counted as suppressed
        // and the arbiter falls through to "nothing to do".
        val suppressed = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, nowMs = 11_000L)),
            b, free, nowMs = 11_000L,
        )
        assertNotEquals(BehaviorAction.STARTLE, suppressed.action)
        assertEquals(1L, arbiter.startlesAccepted)
        assertEquals(1L, arbiter.startlesSuppressed)

        // 5s after the first: the window has passed.
        val accepted = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, nowMs = 15_000L)),
            b, free, nowMs = 15_000L,
        )
        assertEquals(BehaviorAction.STARTLE, accepted.action)
        assertEquals(2L, arbiter.startlesAccepted)
    }

    // ---- 4. expiry ---------------------------------------------------------

    @Test
    fun expiredProposalsAreIgnored() {
        val arbiter = BehaviorArbiter()

        val out = arbiter.arbitrate(
            proposals = listOf(
                proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 0L, ttlMs = 600L),
            ),
            bias = b,
            ownership = free,
            nowMs = 10_000L,
        )

        assertEquals(BehaviorAction.REST, out.action)
        assertEquals("no-proposal", out.reason)
    }

    @Test
    fun noProposalsAtAllRestAndReportHonestly() {
        val arbiter = BehaviorArbiter()

        val out = arbiter.arbitrate(emptyList(), b, free, nowMs = 10_000L)

        assertEquals(BehaviorAction.REST, out.action)
        assertEquals("no-proposal", out.reason)
        assertEquals(AttentionOwner.NONE, out.owner)
    }

    // ---- 5. dwell ----------------------------------------------------------

    @Test
    fun minimumDwellStopsTheCharacterFlickeringBetweenIntentions() {
        val arbiter = BehaviorArbiter()

        val first = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        assertEquals(BehaviorAction.ORIENT, first.action)
        assertEquals("orient", first.reason)

        // 300ms later a different intention arrives. minDwellMs is 600, so it
        // must be held off even though the proposal is perfectly valid.
        val held = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.INSPECT, SocialTarget.USER, nowMs = 10_300L)),
            b, free, nowMs = 10_300L,
        )
        assertEquals(BehaviorAction.ORIENT, held.action)
        assertEquals("dwell", held.reason)
        assertEquals(10_000L, held.sinceMs)
    }

    @Test
    fun aNewIntentionTakesOverOnceDwellAndHoldHaveExpired() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )

        // 1500ms later: past orientHoldMs (1200) and minDwellMs (600).
        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.INSPECT, SocialTarget.USER, intensity = 0.8f, nowMs = 11_500L)),
            b, free, nowMs = 11_500L,
        )

        assertEquals(BehaviorAction.INSPECT, out.action)
        assertEquals("inspect", out.reason)
        // Inspect damps the gaze to 60% — it is a lean-in, not a lock-on.
        assertEquals(0.8f * 0.6f, out.orientation.strength)
    }

    @Test
    fun settleIsHeldForTheInspectDuration() {
        val arbiter = BehaviorArbiter()

        val first = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.SETTLE, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        assertEquals("settle", first.reason)

        // 700ms in: still inside the 1500ms settle hold.
        val held = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.SETTLE, SocialTarget.USER, nowMs = 10_700L)),
            b, free, nowMs = 10_700L,
        )
        assertEquals(BehaviorAction.SETTLE, held.action)
        assertEquals("dwell", held.reason)
    }

    // ---- 6. feedback -------------------------------------------------------

    @Test
    fun feedbackNamingTheRunningActionEndsItEarly() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )

        arbiter.acknowledge(ActionFeedback(BehaviorAction.ORIENT, ActionResult.INTERRUPTED, atMs = 10_100L))

        // Without the feedback this tick would read "dwell" with sinceMs 10_000,
        // because 200ms is inside both the 1200ms hold and the 600ms dwell.
        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_200L)),
            b, free, nowMs = 10_200L,
        )

        assertEquals(BehaviorAction.ORIENT, out.action)
        assertEquals("orient", out.reason)
        assertEquals(10_200L, out.sinceMs)
    }

    @Test
    fun feedbackNamingADifferentActionIsIgnored() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )

        // ORIENT is running; the motor layer reports on an INSPECT that is not.
        arbiter.acknowledge(ActionFeedback(BehaviorAction.INSPECT, ActionResult.FAILED, atMs = 10_100L))

        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_200L)),
            b, free, nowMs = 10_200L,
        )

        assertEquals(BehaviorAction.ORIENT, out.action)
        assertEquals("dwell", out.reason)
        assertEquals(10_000L, out.sinceMs)
    }

    @Test
    fun feedbackIsConsumedExactlyOnce() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        arbiter.acknowledge(ActionFeedback(BehaviorAction.ORIENT, ActionResult.COMPLETED, atMs = 10_100L))

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_200L)),
            b, free, nowMs = 10_200L,
        )
        // Second tick: the feedback must not release the action a second time,
        // which would pin sinceMs to the current tick forever.
        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_300L)),
            b, free, nowMs = 10_300L,
        )

        assertEquals("dwell", out.reason)
        assertEquals(10_200L, out.sinceMs)
    }

    // ---- 7. bias passthrough ----------------------------------------------

    @Test
    fun biasPassesThroughUntouched() {
        val arbiter = BehaviorArbiter()
        val distinctive = bias(
            glanceGain = 0.31f, followGain = 0.42f, restWeight = 0.53f,
            inspectWeight = 0.64f, verticalWeight = 0.75f, earGain = 0.86f,
            blinkRateScale = 0.97f, arousal = 0.18f, valence = -0.29f,
        )

        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            distinctive, free, nowMs = 10_000L,
        )

        // Identity, not equality: the arbiter chooses *what* to do and must not
        // touch the drives that say *how*.
        assertSame(distinctive, out.bias)
    }

    // ---- 8. reset ----------------------------------------------------------

    @Test
    fun resetClearsTheRunningActionAndReopensTheStartleWindow() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        assertEquals(BehaviorAction.STARTLE, arbiter.current())

        arbiter.reset()

        assertEquals(BehaviorAction.REST, arbiter.current())

        // The window is reopened, so a startle 1s later is accepted rather than
        // suppressed. Counters are deliberately *not* reset — they are lifetime
        // telemetry, and the original keeps them across a reset.
        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.STARTLE, SocialTarget.SOUND, nowMs = 11_000L)),
            b, free, nowMs = 11_000L,
        )
        assertEquals(BehaviorAction.STARTLE, out.action)
        assertEquals(2L, arbiter.startlesAccepted)
    }

    @Test
    fun resetDropsDwellCarryOverSoTheNextActionStartsImmediately() {
        val arbiter = BehaviorArbiter()

        arbiter.arbitrate(
            listOf(proposal(BehaviorAction.ORIENT, SocialTarget.USER, nowMs = 10_000L)),
            b, free, nowMs = 10_000L,
        )
        arbiter.reset()

        // `release(0L)` means elapsed is measured from zero, so neither the
        // 1200ms hold nor the 600ms dwell blocks the very next tick. Without the
        // reset this same tick would read "dwell" and keep ORIENT running.
        val out = arbiter.arbitrate(
            listOf(proposal(BehaviorAction.INSPECT, SocialTarget.USER, nowMs = 10_100L)),
            b, free, nowMs = 10_100L,
        )

        assertEquals(BehaviorAction.INSPECT, out.action)
        assertEquals("inspect", out.reason)
    }
}
