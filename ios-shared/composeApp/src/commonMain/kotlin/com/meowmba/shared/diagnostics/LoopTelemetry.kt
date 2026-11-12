package com.meowmba.shared.diagnostics

import com.meowmba.shared.core.MonotonicClock
import com.meowmba.shared.core.PlatformLock

/**
 * Per-feature loop-rate and busy-time accounting.
 *
 * Perfetto callstack sampling could only attribute one of seven busy
 * `DefaultDispatch` workers: everything else unwound to no symbol at all,
 * because a debug build is interpreted and most frames carry no name. Rather
 * than guess again, each loop reports its own cost. If a worker is hot and is
 * not in this list, the problem is a loop nobody instrumented yet — which is
 * itself the answer.
 *
 * `busyNanos` is time spent inside the loop's real work. It deliberately
 * EXCLUDES time spent blocked in `delay()` or in a blocking read: those are
 * idle, and counting them would report wall time as if it were CPU.
 *
 * ## Port notes
 *
 * Three substitutions, none of which change what the numbers mean:
 *
 * - `System.nanoTime` → [MonotonicClock.nanos]. Both are monotonic nanosecond
 *   sources; the latter is simply the one available in common code.
 * - `ConcurrentHashMap` + `AtomicLong` → a plain `HashMap` behind a
 *   [PlatformLock]. The originals are JVM-only. A single lock is also *more*
 *   correct here than the originals were: `hit` and `addBusyNanos` each
 *   read-modify-write a counter, and the atomics made those individual
 *   increments safe but did nothing for the compound reads in [drain], which
 *   now see a consistent snapshot of every accumulator.
 * - `dump()` and `ThreadDump` are **not ported**. `dump()` formats a table for
 *   logcat via `android.util.Log`; `ThreadDump` enumerates
 *   `Thread.getAllStackTraces()`, which has no iOS equivalent at all. Both are
 *   Android debugging tools rather than product behaviour — [snapshot] and
 *   [drain] are the parts a diagnostics screen actually needs, and they are
 *   pure data.
 */
object LoopTelemetry {

    private class Acc {
        var iterations = 0L
        var busyNanos = 0L
        var startedNanos = 0L

        /**
         * Marks for the rolling window, advanced only by [drain].
         *
         * Without them every reading is a process-lifetime average, and a loop
         * that stopped minutes ago still reports a slowly decaying percentage
         * and rate that look exactly like a loop that is running right now.
         * That misreading cost a whole profiling session: a dead ASR loop
         * printed "4.0% core 3.94 Hz" for twenty minutes.
         */
        var markIters = 0L
        var markBusyNanos = 0L
        var markAtNanos = 0L
    }

    private val lock = PlatformLock()
    private val accs = HashMap<String, Acc>()

    /** Caller must hold [lock]. */
    private fun accLocked(feature: String): Acc = accs.getOrPut(feature) {
        Acc().also {
            val now = MonotonicClock.nanos()
            it.startedNanos = now
            it.markAtNanos = now
        }
    }

    /** Count one pass of [feature]'s loop. */
    fun hit(feature: String) {
        lock.withLock { accLocked(feature).iterations++ }
    }

    /** Charge already-measured busy time to [feature]. */
    fun addBusyNanos(feature: String, nanos: Long) {
        if (nanos > 0) lock.withLock { accLocked(feature).busyNanos += nanos }
    }

    /**
     * Run [block], charge its wall time to [feature] and count one pass. Only
     * wrap work that actually burns CPU — never a suspending or blocking call.
     *
     * The iteration count is not optional decoration: a feature driven only by
     * [measure] (rather than [hit]) otherwise reports 0 iterations and 0.00 Hz
     * while sitting at the top of the table, which is exactly the row a
     * regression hunt needs to read. Observed on device with `asr.decode`.
     */
    inline fun <T> measure(feature: String, block: () -> T): T {
        val t0 = MonotonicClock.nanos()
        try {
            return block()
        } finally {
            addBusyNanos(feature, MonotonicClock.nanos() - t0)
            // hit(), not a private accessor: a public inline function cannot
            // reach non-public members.
            hit(feature)
        }
    }

    data class Stat(
        val feature: String,
        val iterations: Long,
        val busyMs: Long,
        val windowMs: Long,
        val hz: Double,
        /** Busy time as a percentage of one core over the window. */
        val corePct: Double,
        // --- rolling window: only what happened since the previous drain() ---
        val recentIters: Long,
        val recentBusyMs: Long,
        val recentWindowMs: Long,
        val recentHz: Double,
        val recentCorePct: Double,
    ) {
        /** Ran at least once since the last drain. False means: this is not your heat. */
        val isLive: Boolean get() = recentIters > 0
    }

    /** Every feature over its whole lifetime, busiest first. */
    fun snapshot(): List<Stat> = lock.withLock {
        val now = MonotonicClock.nanos()
        accs.map { (name, a) ->
            val windowNs = (now - a.startedNanos).coerceAtLeast(1)
            val windowMs = windowNs / 1_000_000L
            val busyMs = a.busyNanos / 1_000_000L
            val iters = a.iterations
            Stat(
                feature = name,
                iterations = iters,
                busyMs = busyMs,
                windowMs = windowMs,
                hz = iters * 1_000_000_000.0 / windowNs,
                corePct = busyMs * 100.0 / windowMs.coerceAtLeast(1),
                recentIters = iters,
                recentBusyMs = busyMs,
                recentWindowMs = windowMs,
                recentHz = iters * 1_000_000_000.0 / windowNs,
                recentCorePct = busyMs * 100.0 / windowMs.coerceAtLeast(1),
            )
        }.sortedByDescending { it.busyMs }
    }

    /**
     * Every feature over the window since the previous call, busiest first.
     *
     * This is the one to read when hunting CPU: it is the only view that
     * distinguishes a loop that is burning the device right now from one that
     * burned it twenty minutes ago and stopped. Advancing the marks is a side
     * effect, so call it from exactly one place.
     */
    fun drain(): List<Stat> = lock.withLock {
        val now = MonotonicClock.nanos()
        accs.map { (name, a) ->
            val iters = a.iterations
            val busyNanos = a.busyNanos
            val markIters = a.markIters.also { a.markIters = iters }
            val markBusy = a.markBusyNanos.also { a.markBusyNanos = busyNanos }
            val markAt = a.markAtNanos.also { a.markAtNanos = now }

            val rIters = (iters - markIters).coerceAtLeast(0)
            val rBusyMs = ((busyNanos - markBusy) / 1_000_000L).coerceAtLeast(0)
            val rWindowNs = (now - markAt).coerceAtLeast(1)
            val rWindowMs = rWindowNs / 1_000_000L

            val lifeNs = (now - a.startedNanos).coerceAtLeast(1)
            val lifeMs = lifeNs / 1_000_000L
            val lifeBusyMs = busyNanos / 1_000_000L

            Stat(
                feature = name,
                iterations = iters,
                busyMs = lifeBusyMs,
                windowMs = lifeMs,
                hz = iters * 1_000_000_000.0 / lifeNs,
                corePct = lifeBusyMs * 100.0 / lifeMs.coerceAtLeast(1),
                recentIters = rIters,
                recentBusyMs = rBusyMs,
                recentWindowMs = rWindowMs,
                recentHz = rIters * 1_000_000_000.0 / rWindowNs,
                recentCorePct = rBusyMs * 100.0 / rWindowMs.toDouble().coerceAtLeast(1.0),
            )
        }.sortedWith(
            compareByDescending<Stat> { it.recentBusyMs }.thenByDescending { it.busyMs }
        )
    }

    /** Forget everything and restart the windows. */
    fun reset() {
        lock.withLock { accs.clear() }
    }
}
