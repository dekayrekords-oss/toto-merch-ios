package com.meowmba.shared.core

/**
 * Port-only exact decimal arithmetic, replacing `java.math.BigDecimal`, which is
 * a JVM type with no common equivalent.
 *
 * Only what the port actually needs is implemented: parse, add, and render
 * without an exponent. That is deliberate — a general decimal would be a large
 * amount of untested arithmetic, and the caller (`CsvSummary`) is explicitly
 * "deterministic arithmetic, not a language model's guess at a business total".
 * So the operations that exist are exact, and the ones that do not exist cannot
 * silently round.
 *
 * **The scale is significant and is preserved, not normalised.** `BigDecimal`
 * tracks a scale, `toPlainString()` prints it, and addition takes the *larger* of
 * the two scales — so `0.10 + 0.20` renders as `"0.30"`, not `"0.3"`. The ported
 * test asserts exactly that, and a "tidy" implementation that strips trailing
 * zeros would fail it while looking correct.
 *
 * Representation: value = `±digits × 10^-scale`, with `digits` carrying no
 * leading zeros (`"0"` for zero). `digits` is a digit string rather than a
 * `Long`, because the accepted input is up to 100 characters and the *sum* of an
 * unbounded number of rows grows past any fixed width — a `Long`-scaled decimal
 * would overflow where `BigDecimal` did not, and the original's tests would still
 * pass on small inputs.
 */
internal class Decimal private constructor(
    private val digits: String,
    private val scale: Int,
    private val negative: Boolean,
) {
    operator fun plus(other: Decimal): Decimal {
        val resultScale = maxOf(scale, other.scale)
        val a = digits.padEnd(digits.length + (resultScale - scale), '0')
        val b = other.digits.padEnd(other.digits.length + (resultScale - other.scale), '0')
        if (negative == other.negative) {
            return of(addDigits(a, b), resultScale, negative)
        }
        return when (compareMagnitudes(a, b)) {
            // The scale still follows the max rule, so 1.50 + -1.5 is "0.00".
            0 -> of("0", resultScale, false)
            1 -> of(subtractDigits(a, b), resultScale, negative)
            else -> of(subtractDigits(b, a), resultScale, other.negative)
        }
    }

    /** Plain notation: never scientific, always the stored scale. */
    override fun toString(): String {
        val sign = if (negative && digits != "0") "-" else ""
        if (scale == 0) return "$sign$digits"
        val padded = digits.padStart(scale + 1, '0')
        val cut = padded.length - scale
        return "$sign${padded.substring(0, cut)}.${padded.substring(cut)}"
    }

    /** Scale-sensitive, matching `BigDecimal.equals` (`0.3 != 0.30`). */
    override fun equals(other: Any?): Boolean =
        other is Decimal && digits == other.digits && scale == other.scale && negative == other.negative

    override fun hashCode(): Int = (digits.hashCode() * 31 + scale) * 31 + negative.hashCode()

    companion object {
        val ZERO = Decimal("0", 0, false)

        /**
         * Parses `[-+]?\d+(\.\d+)?` exactly as the original's regex allowed. The
         * shape is enforced here as well as by the caller so a malformed literal
         * can never be silently reinterpreted.
         */
        fun parse(raw: String): Decimal {
            require(raw.isNotEmpty()) { "empty number" }
            val negative = raw.startsWith('-')
            val body = if (negative || raw.startsWith('+')) raw.substring(1) else raw
            val dot = body.indexOf('.')
            val intPart = if (dot < 0) body else body.substring(0, dot)
            val fracPart = if (dot < 0) "" else body.substring(dot + 1)
            require(intPart.isNotEmpty() && intPart.all { it in '0'..'9' }) { "not a decimal: $raw" }
            require(fracPart.all { it in '0'..'9' }) { "not a decimal: $raw" }
            if (dot >= 0) require(fracPart.isNotEmpty()) { "not a decimal: $raw" }
            return of(intPart + fracPart, fracPart.length, negative)
        }

        /**
         * Drops leading zeros but keeps the scale, so a parsed `0.00` still
         * prints as `0.00`. Zero also loses its sign — `BigDecimal` has no `-0`.
         */
        private fun of(digits: String, scale: Int, negative: Boolean): Decimal {
            val trimmed = digits.trimStart('0')
            if (trimmed.isEmpty()) return Decimal("0", scale, false)
            return Decimal(trimmed, scale, negative)
        }

        private fun addDigits(a: String, b: String): String {
            val n = maxOf(a.length, b.length)
            val x = a.padStart(n, '0')
            val y = b.padStart(n, '0')
            val out = StringBuilder(n + 1)
            var carry = 0
            for (i in n - 1 downTo 0) {
                val sum = (x[i] - '0') + (y[i] - '0') + carry
                out.append('0' + sum % 10)
                carry = sum / 10
            }
            if (carry > 0) out.append('1')
            return out.reverse().toString()
        }

        /** Requires `a >= b`; both are magnitude digit strings. */
        private fun subtractDigits(a: String, b: String): String {
            val n = maxOf(a.length, b.length)
            val x = a.padStart(n, '0')
            val y = b.padStart(n, '0')
            val out = StringBuilder(n)
            var borrow = 0
            for (i in n - 1 downTo 0) {
                var diff = (x[i] - '0') - (y[i] - '0') - borrow
                if (diff < 0) {
                    diff += 10; borrow = 1
                } else {
                    borrow = 0
                }
                out.append('0' + diff)
            }
            return out.reverse().toString()
        }

        /** -1, 0 or 1. Equal-length padding makes lexicographic order numeric. */
        private fun compareMagnitudes(a: String, b: String): Int {
            val n = maxOf(a.length, b.length)
            return a.padStart(n, '0').compareTo(b.padStart(n, '0'))
        }
    }
}
