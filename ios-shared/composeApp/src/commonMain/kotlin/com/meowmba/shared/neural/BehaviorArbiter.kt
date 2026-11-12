package com.meowmba.shared.neural

import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.SocialTarget
import com.meowmba.shared.core.PlatformLock

/** Who currently has the right to direct the character's attention. */
enum class AttentionOwner { NONE, CONVERSATION, EXPLICIT_COMMAND }

data class AttentionOwnership(
    val owner: AttentionOwner = AttentionOwner.NONE,
    val target: SocialTarget = SocialTarget.NONE,
) {
    companion object {
        val FREE = AttentionOwnership()
    }
}

/** Where to look and how hard, -1..1 in screen space; strength 0 means "no opinion". */
data class Orientation(
    val x: Float = 0f,
    val y: Float = 0f,
    val target: SocialTarget = SocialTarget.NONE,
    val strength: Float = 0f,
) {
    companion object {
        val NONE = Orientation()
    }
}

/** The arbiter's decision for this tick. */
data class ArbitratedBehavior(
    val bias: BehaviorBias,
    val action: BehaviorAction,
    val orientation: Orientation,
    val owner: AttentionOwner,
    val reason: String,
    /** When the current action started, controller clock. */
    val sinceMs: Long,
)

/**
 * Deterministic rules between what the network wants and what the motor layer
 * is allowed to do. The neural layer proposes; this decides. Nothing here is
 * learned, so the guarantees hold no matter what a checkpoint contains:
 *
 * - A conversation or an explicit command owns attention. While it does, the
 *   character orients to the owner's target and startle, orient and inspect
 *   proposals toward anything else are ignored.
 * - Startle may interrupt idle behaviour, at most once per [startleWindowMs],
 *   and holds for [startleHoldMs].
 * - Every accepted action holds for at least [minDwellMs] before a different
 *   one may replace it, so the character does not flicker between intentions.
 * - Expired proposals are ignored. Feedback from the motor layer can end an
 *   action early.
 * - The bias passes through untouched: the arbiter chooses *what to do*, the
 *   drives still say *how*.
 *
 * ## Port notes
 *
 * The original is `core/neural/BehaviorArbiter.kt` and is unchanged in
 * behaviour. Its three `@Synchronized` members become [PlatformLock], because
 * `kotlin.jvm.Synchronized` does not exist in common Kotlin.
 *
 * One structural difference is forced by the seam. `PlatformLock.withLock` is a
 * member of an `expect class`, and `expect` members cannot be `inline` — so a
 * non-local `return` from inside the lambda will not compile. [arbitrate] has
 * six early returns that *are* its control flow, so the body moves verbatim
 * into the private, unsynchronised [arbitrateLocked] and the public entry point
 * is a one-line lock acquisition around it. The alternative — rewriting the
 * returns as a labelled result — would have obscured the very ordering the
 * original documents.
 *
 * The original has no test file. Its guarantees are therefore pinned here by
 * `BehaviorArbiterTest` rather than inherited.
 */
class BehaviorArbiter(
    private val minDwellMs: Long = 600L,
    private val startleWindowMs: Long = 4_000L,
    private val startleHoldMs: Long = 700L,
    private val orientHoldMs: Long = 1_200L,
    private val inspectHoldMs: Long = 1_500L,
) {
    private val lock = PlatformLock()

    private var current: BehaviorAction = BehaviorAction.REST
    private var currentTarget: SocialTarget = SocialTarget.NONE
    private var currentSinceMs: Long = 0L
    private var currentHoldMs: Long = 0L
    private var ownedAction: Boolean = false
    private var lastStartleMs: Long = Long.MIN_VALUE / 2
    private var pendingFeedback: ActionFeedback? = null

    private var acceptedStartles: Long = 0L
    private var suppressedStartles: Long = 0L

    /** Read under the lock, unlike the original's bare `var` — see [PlatformLock]. */
    val startlesAccepted: Long get() = lock.withLock { acceptedStartles }
    val startlesSuppressed: Long get() = lock.withLock { suppressedStartles }

    fun current(): BehaviorAction = lock.withLock { current }

    /** Motor layer reports on the running action; INTERRUPTED/FAILED release it on the next arbitration. */
    fun acknowledge(feedback: ActionFeedback) {
        lock.withLock { pendingFeedback = feedback }
    }

    fun arbitrate(
        proposals: List<BehaviorProposal>,
        bias: BehaviorBias,
        ownership: AttentionOwnership,
        nowMs: Long,
    ): ArbitratedBehavior = lock.withLock {
        arbitrateLocked(proposals, bias, ownership, nowMs)
    }

    /**
     * The original body. **Caller must hold [lock]** — this exists only because
     * `withLock` cannot host non-local returns.
     */
    private fun arbitrateLocked(
        proposals: List<BehaviorProposal>,
        bias: BehaviorBias,
        ownership: AttentionOwnership,
        nowMs: Long,
    ): ArbitratedBehavior {
        pendingFeedback?.let { fb ->
            pendingFeedback = null
            // Whatever the outcome, the running action is over once the motor layer says so.
            if (fb.action == current) release(nowMs)
        }

        val live = proposals.filter { !it.isExpired(nowMs) && it.action in ALLOWED }

        // Ownership overrides everything about *where* to look.
        if (ownership.owner != AttentionOwner.NONE) {
            val ownerOrientation = proposals
                .firstOrNull { it.action == BehaviorAction.ORIENT && it.target == ownership.target && !it.isExpired(nowMs) }
                ?.let { Orientation(it.dirX, it.dirY, it.target, it.intensity) }
                ?: Orientation(0f, 0f, ownership.target, 1f)
            if (!ownedAction || current != BehaviorAction.ORIENT || currentTarget != ownership.target) {
                start(BehaviorAction.ORIENT, ownership.target, nowMs, 0L)
                ownedAction = true
            }
            return ArbitratedBehavior(bias, BehaviorAction.ORIENT, ownerOrientation, ownership.owner, "owned", currentSinceMs)
        }

        // Owner released: an owned orient must not outlive the owner.
        if (ownedAction) {
            ownedAction = false
            release(nowMs)
        }

        val elapsed = nowMs - currentSinceMs
        val holdExpired = elapsed >= currentHoldMs
        val dwellSatisfied = elapsed >= minDwellMs

        // Startle: may interrupt anything except an owned action, rate limited.
        live.firstOrNull { it.action == BehaviorAction.STARTLE }?.let { s ->
            if (nowMs - lastStartleMs >= startleWindowMs) {
                lastStartleMs = nowMs
                acceptedStartles++
                start(BehaviorAction.STARTLE, s.target, nowMs, startleHoldMs)
                return ArbitratedBehavior(
                    bias, BehaviorAction.STARTLE, Orientation(s.dirX, s.dirY, s.target, s.intensity),
                    AttentionOwner.NONE, "startle", currentSinceMs,
                )
            } else {
                suppressedStartles++
            }
        }

        if (!holdExpired || !dwellSatisfied) {
            if (current != BehaviorAction.REST) {
                val refreshed = live.firstOrNull { it.action == current && it.target == currentTarget }
                val orientation = refreshed?.let { Orientation(it.dirX, it.dirY, it.target, it.intensity) }
                    ?: Orientation(0f, 0f, currentTarget, 0.5f)
                return ArbitratedBehavior(bias, current, orientation, AttentionOwner.NONE, "dwell", currentSinceMs)
            }
        }

        val next = live.firstOrNull { it.action == BehaviorAction.ORIENT }
            ?: live.firstOrNull { it.action == BehaviorAction.INSPECT }
            ?: live.firstOrNull { it.action == BehaviorAction.SETTLE }
            ?: live.firstOrNull { it.action == BehaviorAction.REST }
        if (next == null) {
            release(nowMs)
            return ArbitratedBehavior(bias, BehaviorAction.REST, Orientation.NONE, AttentionOwner.NONE, "no-proposal", currentSinceMs)
        }
        val hold = when (next.action) {
            BehaviorAction.ORIENT -> orientHoldMs
            BehaviorAction.INSPECT -> inspectHoldMs
            BehaviorAction.SETTLE -> inspectHoldMs
            else -> 0L
        }
        if (next.action != current || next.target != currentTarget) start(next.action, next.target, nowMs, hold)
        val orientation = when (next.action) {
            BehaviorAction.ORIENT -> Orientation(next.dirX, next.dirY, next.target, next.intensity)
            BehaviorAction.INSPECT -> Orientation(next.dirX, next.dirY, next.target, next.intensity * 0.6f)
            else -> Orientation.NONE
        }
        return ArbitratedBehavior(bias, next.action, orientation, AttentionOwner.NONE, next.action.name.lowercase(), currentSinceMs)
    }

    fun reset() {
        lock.withLock {
            release(0L)
            ownedAction = false
            lastStartleMs = Long.MIN_VALUE / 2
            pendingFeedback = null
        }
    }

    private fun start(action: BehaviorAction, target: SocialTarget, nowMs: Long, holdMs: Long) {
        current = action
        currentTarget = target
        currentSinceMs = nowMs
        currentHoldMs = holdMs
    }

    private fun release(nowMs: Long) {
        current = BehaviorAction.REST
        currentTarget = SocialTarget.NONE
        currentSinceMs = nowMs
        currentHoldMs = 0L
    }

    companion object {
        /** The closed action set. There is no privacy or robot action to accept. */
        val ALLOWED: Set<BehaviorAction> = BehaviorAction.entries.toSet()
    }
}
