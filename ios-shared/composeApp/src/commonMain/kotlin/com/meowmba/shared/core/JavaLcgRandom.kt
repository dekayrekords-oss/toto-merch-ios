package com.meowmba.shared.core

/**
 * A faithful reproduction of `java.util.Random`.
 *
 * ## Why this exists instead of `kotlin.random.Random`
 *
 * `kotlin.random.Random(seed)` is a perfectly good PRNG, but it is a *different*
 * one: XorWow, not the 48-bit LCG that `java.util.Random` specifies. Substituting
 * it silently changes every seeded value.
 *
 * That matters here. `DefaultRateCheckpoint.seedReservoir` documents its output as
 * "deterministic across JVMs because it uses java.util.Random's fixed LCG" — the
 * reproducibility is the point, not an accident. Generated weights, time
 * constants and sparse connectivity all derive from this stream, so a different
 * PRNG would give the iOS build a *different neural network* from the Android
 * build at the same seed. Nothing would crash; the character would just behave
 * subtly differently on each platform, which is far worse to debug than a crash.
 *
 * The algorithm is fully specified by the JDK and reproduced bit-for-bit here, so
 * `JavaLcgRandom(seed)` yields the same sequence as `java.util.Random(seed)`.
 * `JavaLcgRandomTest` (JVM) asserts exactly that, against the real class.
 *
 * Only the methods this codebase uses are implemented; add more from the same
 * specification if needed rather than reaching for a different generator.
 */
class JavaLcgRandom(seed: Long) {

    private var seed: Long = (seed xor MULTIPLIER) and MASK

    /** The core generator: the top [bits] of the next LCG state. */
    fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    fun nextInt(): Int = next(32)

    /**
     * Uniform in `[0, bound)`, using the JDK's rejection-sampling loop for
     * non-power-of-two bounds so the result is unbiased.
     */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        if ((bound and -bound) == bound) {
            // Power of two: the top bits are already uniform, no rejection needed.
            return ((bound.toLong() * next(31).toLong()) shr 31).toInt()
        }
        var bits: Int
        var value: Int
        do {
            bits = next(31)
            value = bits % bound
        } while (bits - value + (bound - 1) < 0)
        return value
    }

    fun nextLong(): Long = (next(32).toLong() shl 32) + next(32).toLong()

    fun nextBoolean(): Boolean = next(1) != 0

    fun nextDouble(): Double =
        ((next(26).toLong() shl 27) + next(27).toLong()) * DOUBLE_UNIT

    fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = (1L shl 48) - 1

        /**
         * `2^-53`, the scale that turns a 53-bit draw into `[0, 1)`.
         *
         * Deliberately not `const`: `1.0 / (1L shl 53)` constant-folds on the JVM
         * but not for Kotlin/Native, which fails the build with "Const property has
         * no const initializer expression". A plain `val` is computed once and
         * behaves identically everywhere.
         */
        val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
