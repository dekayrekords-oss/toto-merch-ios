package com.meowmba.shared.core

import kotlin.time.TimeSource

/**
 * A monotonic nanosecond clock, in common code.
 *
 * `System.nanoTime` is JVM-only, and the original reaches for it in two places
 * (`NeuralBehaviorController`'s deadline guard and `LoopTelemetry`). Rather than
 * an `expect`/`actual` pair across four source sets, this uses
 * [TimeSource.Monotonic] from the common stdlib, which maps to the same
 * underlying monotonic source on every target — `clock_gettime(CLOCK_MONOTONIC)`
 * on JVM and Android, `mach_absolute_time` on Apple platforms. Deadlines
 * measured against it therefore mean the same thing everywhere.
 *
 * The origin is arbitrary and private: only *differences* are meaningful, which
 * is all any caller here needs. A single shared origin (rather than a fresh
 * `markNow()` per call site) is what makes two readings taken through different
 * call sites comparable, which is what a deadline measurement requires.
 *
 * Deliberately **not** wall-clock time. `PlatformTime.currentMillis()` can jump
 * backwards when the user changes the clock or NTP corrects it, and a step that
 * appears to take negative time would read as a deadline miss.
 */
object MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    /** Nanoseconds since an arbitrary fixed origin. Only differences are meaningful. */
    fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}
