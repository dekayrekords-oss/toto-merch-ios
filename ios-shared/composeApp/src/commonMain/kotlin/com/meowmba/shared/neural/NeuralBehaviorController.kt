package com.meowmba.shared.neural

import com.meowmba.shared.behavior.BehaviorBias
import com.meowmba.shared.behavior.DriveVector
import com.meowmba.shared.behavior.NervousSystem
import com.meowmba.shared.behavior.SocialTarget
import com.meowmba.shared.behavior.Stimulus
import com.meowmba.shared.core.MonotonicClock
import com.meowmba.shared.core.PlatformLock
import com.meowmba.shared.diagnostics.LoopTelemetry

/** How much authority the neural layer has over the avatar. */
enum class NeuralMode {
    /** The hand-authored `NervousSystem` drives the avatar; the core is never stepped. */
    LEGACY,
    /** Both run; legacy drives the avatar; divergence is measured and logged. */
    SHADOW,
    /** The arbitrated neural bias drives the avatar; legacy stays warm as the fallback. */
    NEURAL,
}

/** Why the controller is (or last was) publishing the legacy bias while in NEURAL mode. */
enum class FallbackReason { NONE, NON_FINITE_OUTPUT, EXCEPTION, DEADLINE, THERMAL_SUSPENDED }

/** Where CPU time is charged. The default reports into [LoopTelemetry]; tests inject a recorder. */
fun interface NeuralTelemetry {
    fun step(feature: String, busyNanos: Long)

    companion object {
        const val FEATURE_STEP = "neural.step"
        const val FEATURE_LEGACY = "neural.legacy"

        val LOOP: NeuralTelemetry = NeuralTelemetry { feature, nanos ->
            LoopTelemetry.hit(feature)
            LoopTelemetry.addBusyNanos(feature, nanos)
        }
        val NONE: NeuralTelemetry = NeuralTelemetry { _, _ -> }
    }
}

/** Periodic shadow-mode report: how far the neural bias is from the legacy one. */
data class DivergenceReport(
    val atMs: Long,
    val samples: Long,
    val meanDivergence: Float,
    val maxDivergence: Float,
    val recentDivergence: Float,
    val legacyDominant: String,
    val neuralDominant: String,
)

/** Snapshot for the Settings section and logs. Never used for control. */
data class NeuralDiagnostics(
    val mode: NeuralMode,
    val substrate: NeuralSubstrate,
    val fallback: FallbackReason,
    val thermalTier: Int,
    val simTimeMs: Long,
    val steps: Long,
    val lastStepMicros: Long,
    val p95StepMicros: Long,
    val deadlineMisses: Long,
    val busDropped: Long,
    val busRejectedStale: Long,
    val divergenceMean: Float,
    val divergenceMax: Float,
    val neuralDrives: DriveVector,
    val legacyDrives: DriveVector,
    val currentAction: BehaviorAction,
    val core: CoreDiagnostics,
)

/**
 * The drop-in replacement for `NervousSystem` in the ViewModel.
 *
 * It keeps the legacy controller's public surface (`onStimulus`, `setIntent`,
 * `clearIntent`, `noteAffect`, `tick`, `bias`, `snapshot`, `dominant`) so every
 * existing call site keeps working, and adds the neural path beside it:
 * typed events in, a [NeuralCore] stepped once per tick, a decoder, and the
 * deterministic [BehaviorArbiter].
 *
 * Modes and safety, in order of what the motor layer sees:
 * - LEGACY: the core is never stepped; zero extra cost.
 * - SHADOW (default): both run, legacy is published bit-for-bit, and
 *   [DivergenceReport]s go to [divergenceSink] at a bounded rate.
 * - NEURAL: the arbitrated neural bias is published unless a fallback is active.
 *
 * Fallback to legacy is automatic and sticky on a non-finite readout, an
 * exception inside the neural step, or [maxDeadlineMisses] consecutive steps
 * over [deadlineMs]; it is non-sticky for the thermal SURVIVAL tier, which
 * suspends the core outright. ECONOMY halves the neural step rate. After any
 * suspension the input backlog is discarded, not replayed: reacting late is
 * worse than not reacting.
 *
 * Time is the controller's own simulation clock, advanced by [tick]; senses
 * that do not share it leave events unstamped and the bus stamps them on
 * arrival. Public methods are synchronized like the legacy controller: senses
 * arrive on any thread, the tick runs on one.
 *
 * ## Port notes
 *
 * The original marks nine members `@Synchronized` and four fields `@Volatile`.
 * Both annotations are JVM-only, so this port routes them through a single
 * [PlatformLock]. The lock is held for *reads as well as writes*, which is
 * stronger than the volatile fields it replaces: a lock gives the same
 * happens-before guarantee a volatile field does, and it also makes the
 * compound read-modify-write sequences in [tick] genuinely atomic rather than
 * merely visible.
 *
 * Seven of those members contain early `return`s that *are* their control flow
 * — most of all [tick], which has four. `PlatformLock.withLock` is a member of
 * an `expect class` and so cannot be `inline`, which means a non-local return
 * from inside it will not compile. Each of those bodies therefore moves verbatim
 * into a private `…Locked` function that documents the caller must hold the
 * lock, and the public entry point is a one-line acquisition around it.
 *
 * `System::nanoTime` becomes [MonotonicClock.nanos]; the two are both monotonic
 * nanosecond sources and only differences are ever used.
 *
 * `lock` is deliberately declared first, before anything whose initialiser could
 * touch it.
 */
class NeuralBehaviorController(
    private val legacy: NervousSystem = NervousSystem(),
    private val coreFactory: (NeuralSubstrate) -> NeuralCore = { substrate ->
        when (substrate) {
            NeuralSubstrate.RATE -> LeakyRecurrentCore(NeuralCheckpoint.default())
            NeuralSubstrate.SPIKING -> SpikingCore(NeuralCheckpoint.defaultSpiking())
        }
    },
    initialMode: NeuralMode = NeuralMode.SHADOW,
    initialSubstrate: NeuralSubstrate = NeuralSubstrate.RATE,
    private val bus: SensoryEventBus = SensoryEventBus(),
    private val encoder: FeatureEncoder = FeatureEncoder(),
    private val decoder: BehaviorDecoder = BehaviorDecoder(),
    private val arbiter: BehaviorArbiter = BehaviorArbiter(),
    /** 0 = normal, 1 = economy, 2 = survival; wired to `ThermalGovernor.currentTier().ordinal`. */
    private val thermalTier: () -> Int = { 0 },
    /** Monotonic clock for deadline measurement; injectable so tests can simulate slow steps. */
    private val monotonicNanos: () -> Long = MonotonicClock::nanos,
    private val telemetry: NeuralTelemetry = NeuralTelemetry.LOOP,
    private val divergenceSink: (DivergenceReport) -> Unit = {},
    private val traceSink: ((FeatureTraceSample) -> Unit)? = null,
    private val deadlineMs: Float = 20f,
    private val maxDeadlineMisses: Int = 5,
    private val divergenceReportIntervalMs: Long = 5_000L,
) {
    private val lock = PlatformLock()

    private var modeValue: NeuralMode = initialMode
    private var substrateValue: NeuralSubstrate = initialSubstrate
    private var fallbackValue: FallbackReason = FallbackReason.NONE

    /** Read under the lock, unlike the original's bare fields — see the port notes. */
    val mode: NeuralMode get() = lock.withLock { modeValue }
    val substrate: NeuralSubstrate get() = lock.withLock { substrateValue }
    val fallback: FallbackReason get() = lock.withLock { fallbackValue }

    private var core: NeuralCore = coreFactory(substrateValue)

    private var published: BehaviorBias = legacy.bias()
    private var neuralDrives: DriveVector = DriveVector()
    private var lastArbitrated: ArbitratedBehavior? = null
    private var lastReadout: FloatArray = FloatArray(0)

    private var simTimeMs: Long = 0L
    private var intent: IntentEnvelope? = null
    private var ownership: AttentionOwnership = AttentionOwnership.FREE
    private var consecutiveMisses = 0
    private var suspended = false
    private var economyAccumulatedMs = 0f
    private var economyParity = false

    private var steps = 0L
    private var deadlineMisses = 0L
    private var lastStepNanos = 0L
    private val stepWindow = LongArray(64)
    private var stepWindowCount = 0

    private var divergenceSamples = 0L
    private var divergenceSum = 0.0
    private var divergenceMax = 0f
    private var divergenceRecent = 0f
    private var lastDivergenceReportMs = 0L
    private var currentTier = 0

    // ----- Legacy-compatible surface --------------------------------------------

    val socialTarget: SocialTarget get() = legacy.socialTarget

    fun setIntent(target: SocialTarget, approach: Float? = null) {
        lock.withLock {
            legacy.setIntent(target, approach)
            intent = IntentEnvelope(target = target, approach = approach, issuedAtMs = simTimeMs).validated()
            ownership = ownershipFor(target, AttentionOwner.CONVERSATION)
        }
    }

    fun clearIntent() {
        lock.withLock {
            legacy.clearIntent()
            intent = null
            ownership = AttentionOwnership.FREE
        }
    }

    fun noteAffect(valence: Float) {
        lock.withLock {
            legacy.noteAffect(valence)
            if (valence.isFinite()) {
                bus.offer(SensoryEvent.impulse(SensoryKind.AFFECT, valence.coerceIn(-1f, 1f), SensorySource.INTERACTION), simTimeMs)
            }
        }
    }

    fun onStimulus(kind: Stimulus, magnitude: Float = 1f) {
        lock.withLock { onStimulusLocked(kind, magnitude) }
    }

    /** Caller must hold [lock]. */
    private fun onStimulusLocked(kind: Stimulus, magnitude: Float) {
        legacy.onStimulus(kind, magnitude)
        if (!magnitude.isFinite() || magnitude <= 0f) return
        val m = magnitude.coerceIn(0f, 1f)
        val (eventKind, source) = when (kind) {
            Stimulus.FACE_SEEN -> SensoryKind.FACE_ARRIVED to SensorySource.FACE
            Stimulus.FACE_LOST -> SensoryKind.FACE_LOST to SensorySource.FACE
            Stimulus.LOUD_SOUND -> SensoryKind.LOUD_SOUND to SensorySource.VOICE
            Stimulus.MOTION -> SensoryKind.NOVELTY to SensorySource.VISUAL_MOTION
            Stimulus.TOUCH -> SensoryKind.TOUCH to SensorySource.TOUCH
            Stimulus.SPOKEN_TO -> SensoryKind.SPOKEN_TO to SensorySource.INTERACTION
            Stimulus.PRAISE -> SensoryKind.PRAISE to SensorySource.INTERACTION
            Stimulus.NOVELTY -> SensoryKind.NOVELTY to SensorySource.SYSTEM
            Stimulus.THREAT -> SensoryKind.THREAT to SensorySource.SYSTEM
            Stimulus.TASK_DONE -> SensoryKind.TASK_SUCCESS to SensorySource.INTERACTION
        }
        bus.offer(SensoryEvent.impulse(eventKind, m, source), simTimeMs)
    }

    /** Current bias for the motor layer. Safe to read every frame. */
    fun bias(): BehaviorBias = lock.withLock { published }

    /** Drives behind the published bias. */
    fun snapshot(): DriveVector = lock.withLock {
        if (modeValue == NeuralMode.NEURAL && fallbackValue == FallbackReason.NONE) neuralDrives else legacy.snapshot()
    }

    fun dominant(): String = dominantOf(snapshot())

    // ----- Neural surface -----------------------------------------------------------

    /** A typed observation from a sense adapter. Unstamped events are stamped now. */
    fun onEvent(event: SensoryEvent) {
        lock.withLock { onEventLocked(event) }
    }

    /** Caller must hold [lock]. */
    private fun onEventLocked(event: SensoryEvent) {
        if (modeValue == NeuralMode.LEGACY) return
        bus.offer(event, simTimeMs)
    }

    fun onEvents(events: List<SensoryEvent>) {
        lock.withLock { onEventsLocked(events) }
    }

    /** Caller must hold [lock]. */
    private fun onEventsLocked(events: List<SensoryEvent>) {
        if (modeValue == NeuralMode.LEGACY) return
        for (e in events) bus.offer(e, simTimeMs)
    }

    /** Richer intent from the reasoning layer; expires on its own. */
    fun setIntentEnvelope(envelope: IntentEnvelope, owner: AttentionOwner = AttentionOwner.CONVERSATION) {
        lock.withLock { setIntentEnvelopeLocked(envelope, owner) }
    }

    /** Caller must hold [lock]. */
    private fun setIntentEnvelopeLocked(envelope: IntentEnvelope, owner: AttentionOwner) {
        val valid = envelope.validated() ?: return
        legacy.setIntent(valid.target, valid.approach)
        intent = valid
        ownership = ownershipFor(valid.target, owner)
    }

    fun onActionFeedback(feedback: ActionFeedback) {
        lock.withLock { arbiter.acknowledge(feedback) }
    }

    /** Where the neural layer wants to look; [Orientation.NONE] when it has no opinion or is not in control. */
    fun orientationNow(): Orientation = lock.withLock {
        if (modeValue == NeuralMode.NEURAL && fallbackValue == FallbackReason.NONE) {
            lastArbitrated?.orientation ?: Orientation.NONE
        } else {
            Orientation.NONE
        }
    }

    fun currentAction(): BehaviorAction = lock.withLock { lastArbitrated?.action ?: BehaviorAction.REST }

    fun lastReadout(): FloatArray = lock.withLock { lastReadout.copyOf() }

    fun setMode(newMode: NeuralMode) {
        lock.withLock { setModeLocked(newMode) }
    }

    /** Caller must hold [lock]. */
    private fun setModeLocked(newMode: NeuralMode) {
        if (newMode == modeValue) return
        val wasOff = modeValue == NeuralMode.LEGACY
        modeValue = newMode
        if (newMode != NeuralMode.LEGACY && wasOff) {
            // Coming back from off: nothing queued is current.
            bus.clear()
            encoder.reset()
        }
        if (newMode == NeuralMode.NEURAL) clearFallbackLocked()
        if (newMode == NeuralMode.LEGACY) published = legacy.bias()
    }

    fun setSubstrate(newSubstrate: NeuralSubstrate) {
        lock.withLock { setSubstrateLocked(newSubstrate) }
    }

    /** Caller must hold [lock]. */
    private fun setSubstrateLocked(newSubstrate: NeuralSubstrate) {
        if (newSubstrate == substrateValue) return
        substrateValue = newSubstrate
        core = coreFactory(newSubstrate)
        encoder.reset()
        arbiter.reset()
        bus.clear()
        clearFallbackLocked()
    }

    /** Forget the neural state (not the legacy drives) and clear any sticky fallback. */
    fun resetNeuralState() {
        lock.withLock {
            core.reset()
            encoder.reset()
            arbiter.reset()
            bus.clear()
            clearFallbackLocked()
        }
    }

    fun clearFallback() {
        lock.withLock { clearFallbackLocked() }
    }

    /** Caller must hold [lock]. */
    private fun clearFallbackLocked() {
        fallbackValue = FallbackReason.NONE
        consecutiveMisses = 0
    }

    // ----- Tick -------------------------------------------------------------------------

    fun tick(dtMs: Long) {
        lock.withLock { tickLocked(dtMs) }
    }

    /** Caller must hold [lock]. */
    private fun tickLocked(dtMs: Long) {
        if (dtMs <= 0L) return
        simTimeMs += dtMs

        val t0 = monotonicNanos()
        legacy.tick(dtMs)
        val legacyBias = legacy.bias()
        telemetry.step(NeuralTelemetry.FEATURE_LEGACY, monotonicNanos() - t0)

        if (modeValue == NeuralMode.LEGACY) {
            bus.clear()
            published = legacyBias
            return
        }

        // Expired intent releases ownership and lets the drives relax.
        intent?.let { if (it.isExpired(simTimeMs)) { intent = null; ownership = AttentionOwnership.FREE } }

        currentTier = thermalTier()
        if (currentTier >= TIER_SURVIVAL) {
            suspended = true
            if (modeValue == NeuralMode.NEURAL) fallbackValue = FallbackReason.THERMAL_SUSPENDED
            bus.clear()
            published = legacyBias
            return
        }
        if (suspended) {
            // Resuming: drop the backlog rather than replaying stale evidence.
            suspended = false
            bus.clear()
            encoder.reset()
            if (fallbackValue == FallbackReason.THERMAL_SUSPENDED) fallbackValue = FallbackReason.NONE
        }

        var stepDt = dtMs.toFloat()
        if (currentTier == TIER_ECONOMY) {
            economyAccumulatedMs += dtMs
            economyParity = !economyParity
            if (economyParity) {
                published = if (modeValue == NeuralMode.NEURAL && fallbackValue == FallbackReason.NONE) lastArbitrated?.bias ?: legacyBias else legacyBias
                return
            }
            stepDt = economyAccumulatedMs
            economyAccumulatedMs = 0f
        } else {
            economyAccumulatedMs = 0f
        }

        val arbitrated = neuralStep(stepDt, legacyBias)

        if (modeValue == NeuralMode.SHADOW) {
            published = legacyBias
            if (arbitrated != null) trackDivergence(legacyBias, arbitrated.bias)
        } else {
            published = if (arbitrated != null && fallbackValue == FallbackReason.NONE) arbitrated.bias else legacyBias
        }

        traceSink?.invoke(
            FeatureTraceSample(
                tMs = simTimeMs,
                mode = modeValue.name,
                input = lastInput.copyOf(),
                legacyBias = DriveBias.toArray(legacyBias),
                neuralReadout = lastReadout.copyOf(),
                neuralBias = arbitrated?.let { DriveBias.toArray(it.bias) },
                legacyDrives = legacy.snapshot().toArray(),
            ),
        )
    }

    private var lastInput: FloatArray = FloatArray(encoder.layout.size)

    /** One neural step under the deadline guard. Null when a fallback prevented a result. */
    private fun neuralStep(dtMs: Float, legacyBias: BehaviorBias): ArbitratedBehavior? {
        val t0 = monotonicNanos()
        var result: ArbitratedBehavior? = null
        try {
            val batch = bus.drain(simTimeMs)
            val x = encoder.encode(batch, intent, dtMs, simTimeMs)
            lastInput = x
            core.step(x, dtMs)
            val y = core.readout()
            lastReadout = y
            if (!allFinite(y)) {
                enterFallback(FallbackReason.NON_FINITE_OUTPUT)
                core.reset()
            } else {
                val decoded = decoder.decode(y)
                neuralDrives = decoded.drives
                val proposals = decoder.proposals(decoded, simTimeMs)
                result = arbiter.arbitrate(proposals, decoded.bias, ownership, simTimeMs)
                lastArbitrated = result
            }
        } catch (e: Exception) {
            enterFallback(FallbackReason.EXCEPTION)
            try { core.reset() } catch (_: Exception) { }
        }
        val elapsed = monotonicNanos() - t0
        steps++
        lastStepNanos = elapsed
        stepWindow[(stepWindowCount++ % stepWindow.size)] = elapsed
        telemetry.step(NeuralTelemetry.FEATURE_STEP, elapsed)
        if (elapsed > (deadlineMs * 1_000_000f).toLong()) {
            deadlineMisses++
            consecutiveMisses++
            if (consecutiveMisses >= maxDeadlineMisses) enterFallback(FallbackReason.DEADLINE)
        } else {
            consecutiveMisses = 0
        }
        return result
    }

    private fun enterFallback(reason: FallbackReason) {
        // Sticky reasons are not overwritten by later, milder ones.
        if (fallbackValue == FallbackReason.NONE || fallbackValue == FallbackReason.THERMAL_SUSPENDED) fallbackValue = reason
    }

    private fun trackDivergence(legacyBias: BehaviorBias, neuralBias: BehaviorBias) {
        val d = DriveBias.divergence(legacyBias, neuralBias)
        if (!d.isFinite()) return
        divergenceSamples++
        divergenceSum += d
        if (d > divergenceMax) divergenceMax = d
        divergenceRecent = if (divergenceSamples == 1L) d else divergenceRecent * 0.9f + d * 0.1f
        if (simTimeMs - lastDivergenceReportMs >= divergenceReportIntervalMs) {
            lastDivergenceReportMs = simTimeMs
            divergenceSink(
                DivergenceReport(
                    atMs = simTimeMs,
                    samples = divergenceSamples,
                    meanDivergence = (divergenceSum / divergenceSamples).toFloat(),
                    maxDivergence = divergenceMax,
                    recentDivergence = divergenceRecent,
                    legacyDominant = legacy.dominant(),
                    neuralDominant = dominantOf(neuralDrives),
                ),
            )
        }
    }

    fun diagnostics(): NeuralDiagnostics = lock.withLock {
        val n = minOf(stepWindowCount, stepWindow.size)
        val p95 = if (n == 0) 0L else {
            val sorted = stepWindow.copyOf(n).sortedArray()
            sorted[((n - 1) * 0.95f).toInt()]
        }
        NeuralDiagnostics(
            mode = modeValue,
            substrate = substrateValue,
            fallback = fallbackValue,
            thermalTier = currentTier,
            simTimeMs = simTimeMs,
            steps = steps,
            lastStepMicros = lastStepNanos / 1_000L,
            p95StepMicros = p95 / 1_000L,
            deadlineMisses = deadlineMisses,
            busDropped = bus.droppedTransitions,
            busRejectedStale = bus.rejectedStale,
            divergenceMean = if (divergenceSamples == 0L) 0f else (divergenceSum / divergenceSamples).toFloat(),
            divergenceMax = divergenceMax,
            neuralDrives = neuralDrives,
            legacyDrives = legacy.snapshot(),
            currentAction = lastArbitrated?.action ?: BehaviorAction.REST,
            core = core.diagnostics(),
        )
    }

    private fun ownershipFor(target: SocialTarget, owner: AttentionOwner): AttentionOwnership =
        if (target == SocialTarget.NONE) AttentionOwnership.FREE else AttentionOwnership(owner, target)

    private fun allFinite(v: FloatArray): Boolean {
        for (x in v) if (!x.isFinite()) return false
        return true
    }

    companion object {
        const val TIER_ECONOMY = 1
        const val TIER_SURVIVAL = 2

        fun dominantOf(d: DriveVector): String = listOf(
            "approach" to d.approach, "social" to d.social, "curiosity" to d.curiosity,
            "caution" to d.caution, "tired" to (1f - d.energy),
        ).maxBy { it.second }.first
    }
}

private fun DriveVector.toArray(): FloatArray = floatArrayOf(approach, social, curiosity, caution, energy)
