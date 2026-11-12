package com.meowmba.shared

import com.meowmba.shared.behavior.DriveVector
import com.meowmba.shared.behavior.NervousSystem
import com.meowmba.shared.behavior.Stimulus
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Concurrency coverage for [NervousSystem] — JVM-only, because `kotlin.concurrent.thread`
 * does not exist in common Kotlin and the iOS targets have no equivalent.
 *
 * This is the one thing the original's suite could not test. On Android,
 * `@Volatile` + `@Synchronized` are free; the port replaces them with an explicit
 * [com.meowmba.shared.core.PlatformLock], so the lock is now load-bearing and
 * worth asserting directly.
 *
 * The scenario mirrors production: the drive tick runs on a background
 * dispatcher while stimuli arrive on the main thread, and both are
 * read-modify-write on the same [DriveVector].
 */
class NervousSystemConcurrencyTest {

    @Test
    fun `concurrent stimuli do not lose updates`() {
        // Magnitude is chosen so the sum lands at 0.72 rather than saturating:
        // 8 threads x 200 impulses x 0.45 x 0.001. A lost update — the classic
        // failure mode of an unsynchronised read-modify-write — shows up as a
        // final value below this.
        val threads = 8
        val perThread = 200
        val magnitude = 0.001f
        val expected = threads * perThread * 0.45f * magnitude

        val n = NervousSystem(
            initial = DriveVector(curiosity = 0f),
            baseline = DriveVector(curiosity = 0f),
        )

        val workers = List(threads) {
            thread {
                repeat(perThread) { n.onStimulus(Stimulus.NOVELTY, magnitude) }
            }
        }
        workers.forEach { it.join() }

        // Tolerance covers float non-associativity: the lock serialises the
        // updates but does not fix their order.
        assertEquals(expected, n.snapshot().curiosity, 0.01f, "lost updates under contention")
    }

    @Test
    fun `ticking while stimuli arrive keeps the vector valid`() {
        // Every read must observe a whole, in-range vector. DriveVector is
        // immutable and replaced wholesale, and reads now hold the lock, so a
        // torn value is impossible by construction — this asserts it stays true.
        val n = NervousSystem()

        // Assertions thrown on a worker thread would be swallowed, so the reader
        // records its failure here and the test thread re-throws it.
        val failure = AtomicReference<Throwable?>(null)
        val stop = AtomicReference(false)

        val ticker = thread {
            while (!stop.get()) n.tick(16)
        }
        val stimulator = thread {
            var i = 0
            val kinds = Stimulus.values()
            while (!stop.get()) {
                n.onStimulus(kinds[i % kinds.size], 0.7f)
                i++
            }
        }
        val reader = thread {
            try {
                repeat(5_000) {
                    val d = n.snapshot()
                    check(d.isFinite()) { "torn read: $d" }
                    check(
                        d.approach in 0f..1f && d.social in 0f..1f && d.curiosity in 0f..1f &&
                            d.caution in 0f..1f && d.energy in 0f..1f,
                    ) { "out-of-range drive: $d" }
                    n.bias()
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                stop.set(true)
            }
        }

        reader.join()
        ticker.join()
        stimulator.join()

        assertNull(failure.get(), "reader thread observed an invalid state")
    }
}
