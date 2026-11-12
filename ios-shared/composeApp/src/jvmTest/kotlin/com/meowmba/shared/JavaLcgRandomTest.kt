package com.meowmba.shared

import com.meowmba.shared.core.JavaLcgRandom
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-checks [JavaLcgRandom] against the real `java.util.Random`.
 *
 * This is the whole justification for the class: it exists so that seeded
 * checkpoints reproduce identically on iOS and Android. If it diverged from the
 * JDK even slightly, the port would silently train a different network per
 * platform — a bug that would never crash and would be miserable to find.
 *
 * JVM-only, because it needs the class it is imitating.
 */
class JavaLcgRandomTest {

    /** Seeds chosen to cover sign handling, the extremes, and the multiplier itself. */
    private val seeds = listOf(
        0L, 1L, 2L, -1L, 42L, -42L,
        Long.MAX_VALUE, Long.MIN_VALUE,
        0x5DEECE66DL, 0xBL,
        // High bit set: needs the unsigned literal, a signed Long cannot hold it.
        0xDEADBEEFCAFEBABEUL.toLong(), -0x1234567890ABCDEFL,
    )

    @Test
    fun `nextInt matches the JDK`() {
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(500) {
                assertEquals(theirs.nextInt(), mine.nextInt(), "nextInt() diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `next bits matches the JDK`() {
        for (seed in seeds) {
            for (bits in 1..32) {
                val mine = JavaLcgRandom(seed)
                val theirs = Random(seed)
                repeat(20) {
                    assertEquals(
                        theirs.nextInt() ushr (32 - bits),
                        mine.next(bits),
                        "next($bits) diverged, seed=$seed",
                    )
                }
            }
        }
    }

    @Test
    fun `nextInt bound matches the JDK`() {
        // Powers of two take the fast path; the rest take the rejection loop.
        // Both are exercised, including bounds where rejection actually triggers.
        val bounds = listOf(1, 2, 3, 4, 7, 8, 16, 17, 64, 100, 255, 256, 1000, 4096, 12345)
        for (seed in seeds) {
            for (bound in bounds) {
                val mine = JavaLcgRandom(seed)
                val theirs = Random(seed)
                repeat(100) {
                    assertEquals(
                        theirs.nextInt(bound),
                        mine.nextInt(bound),
                        "nextInt($bound) diverged, seed=$seed",
                    )
                }
            }
        }
    }

    @Test
    fun `nextInt bound rejects non-positive bounds`() {
        assertFailsWith<IllegalArgumentException> { JavaLcgRandom(1L).nextInt(0) }
        assertFailsWith<IllegalArgumentException> { JavaLcgRandom(1L).nextInt(-5) }
    }

    @Test
    fun `nextLong matches the JDK`() {
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(200) {
                assertEquals(theirs.nextLong(), mine.nextLong(), "nextLong diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `nextBoolean matches the JDK`() {
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(200) {
                assertEquals(theirs.nextBoolean(), mine.nextBoolean(), "nextBoolean diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `nextDouble matches the JDK`() {
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(200) {
                // Exact equality: identical arithmetic on identical state, so a
                // tolerance here would hide precisely the drift this guards against.
                assertEquals(theirs.nextDouble(), mine.nextDouble(), "nextDouble diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `nextFloat matches the JDK`() {
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(200) {
                assertEquals(theirs.nextFloat(), mine.nextFloat(), "nextFloat diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `interleaved calls stay in step`() {
        // seedReservoir mixes nextDouble() and nextInt() freely; a divergence in
        // one must not desynchronise the other.
        for (seed in seeds) {
            val mine = JavaLcgRandom(seed)
            val theirs = Random(seed)
            repeat(100) {
                assertEquals(theirs.nextDouble(), mine.nextDouble(), "nextDouble diverged, seed=$seed")
                assertEquals(theirs.nextInt(37), mine.nextInt(37), "nextInt(37) diverged, seed=$seed")
                assertEquals(theirs.nextInt(), mine.nextInt(), "nextInt() diverged, seed=$seed")
                assertEquals(theirs.nextBoolean(), mine.nextBoolean(), "nextBoolean diverged, seed=$seed")
            }
        }
    }

    @Test
    fun `same seed reproduces the same stream`() {
        val a = JavaLcgRandom(12345L)
        val b = JavaLcgRandom(12345L)
        repeat(1000) { assertEquals(a.nextLong(), b.nextLong()) }
    }
}
