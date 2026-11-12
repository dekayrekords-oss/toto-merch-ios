package com.meowmba.shared

import com.meowmba.shared.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The port-only exact decimal that replaced `java.math.BigDecimal`.
 *
 * Every expectation here was computed **outside this implementation** — with
 * Python's `decimal` module at 400 digits of precision, matching `BigDecimal`'s
 * scale semantics — rather than read back out of the code under test. That
 * matters more than usual for this file: the whole reason `CsvSummary` uses a
 * decimal is that a model's (or a `Double`'s) arithmetic is not trustworthy, so a
 * test that asked the implementation what the answer was would be testing
 * nothing.
 *
 * One deliberate divergence from Python, matching Java: **`BigDecimal` has no
 * negative zero.** `"-0.0"` prints as `"0.0"`. Python prints `"-0.0"`.
 *
 * Two mutations were run against this file, and both were caught by the
 * assertion written for them rather than by an unrelated one:
 *
 * - `plus`'s `maxOf(scale, other.scale)` → `minOf` failed
 *   [scaleIsPreservedRatherThanNormalised] (`0.10 + 0.20` became `"0.3"`).
 * - `of`'s zero case returning `negative` instead of `false` failed
 *   [equalsIsScaleSensitiveLikeBigDecimal] at its `-0.0 == 0.0` line, which is
 *   the assertion that exists to pin exactly that rule.
 */
class DecimalTest {

    private fun sum(a: String, b: String) = (Decimal.parse(a) + Decimal.parse(b)).toString()

    @Test
    fun scaleIsPreservedRatherThanNormalised() {
        // BigDecimal takes the LARGER of the two scales, and toPlainString()
        // prints it — so a "tidy" implementation that strips trailing zeros
        // produces "0.3" here and fails. The ported CsvSummary test pins "0.30".
        assertEquals("0.30", sum("0.10", "0.20"))
        assertEquals("9.33", sum("7.11", "2.22"))
        assertEquals("25.25", sum("20.20", "5.05"))
        assertEquals("0.3", sum("0.1", "0.2"))
        assertEquals("0.0", sum("1.5", "-1.5"))
        assertEquals("0.00", sum("1.50", "-1.5"))
        assertEquals("0.00", sum("0.00", "0.00"))
        assertEquals("0.00", sum("0", "0.00"))
        assertEquals("0.00", sum("5.05", "-5.05"))
        assertEquals("100.001", sum("100", "0.001"))
        assertEquals("1.0", sum("-0.0", "1"))
        assertEquals("1.0", sum("+1.5", "-0.5"))
        assertEquals("0", sum("-10", "10"))
    }

    @Test
    fun parsesAndPrintsLiteralsExactly() {
        for ((input, expected) in listOf(
            "0" to "0",
            "1" to "1",
            "-1" to "-1",
            "0.30" to "0.30",
            "1.5" to "1.5",
            "-2.50" to "-2.50",
            "0.00" to "0.00",
            "0.000" to "0.000",
            "+1.5" to "1.5",
            "0012.34" to "12.34",
            "0000" to "0",
        )) {
            assertEquals(expected, Decimal.parse(input).toString(), "parsing $input")
        }
        // No negative zero — Java's behaviour, not Python's.
        assertEquals("0.0", Decimal.parse("-0.0").toString())
        assertEquals("0", Decimal.parse("-0").toString())
        assertEquals("0.00", Decimal.parse("-0.00").toString())
    }

    /**
     * The reason this is a digit string and not a scaled `Long`. The original's
     * regex accepts up to 100 characters, so amounts and sums exceed any fixed
     * width — and a `Long`-backed port would overflow silently while every
     * small-input test still passed.
     */
    @Test
    fun sumsBeyondLongRangeStayExact() {
        assertEquals(
            "123456789012345678901234567891",
            sum("123456789012345678901234567890", "1"),
        )
        // Carry propagates across all 32 digits.
        assertEquals(
            "100000000000000000000000000000000",
            sum("99999999999999999999999999999999", "1"),
        )
        // 30 nines + 1e-30, exactly, with the scale preserved.
        assertEquals(
            "1.000000000000000000000000000000",
            sum("0.999999999999999999999999999999", "0.000000000000000000000000000001"),
        )
        // A value far past Long.MAX_VALUE, as a single literal.
        assertEquals(
            "9223372036854775808",
            Decimal.parse("9223372036854775807").plus(Decimal.parse("1")).toString(),
        )
    }

    @Test
    fun rejectsAnythingTheOriginalRegexWouldHaveRejected() {
        for (bad in listOf("", ".", ".5", "1.", "1e5", "abc", "1,000", "--1", "1.2.3", " ")) {
            assertFailsWith<IllegalArgumentException>("expected rejection: '$bad'") { Decimal.parse(bad) }
        }
    }

    @Test
    fun equalsIsScaleSensitiveLikeBigDecimal() {
        assertEquals(Decimal.parse("0.30"), Decimal.parse("0.30"))
        // BigDecimal.equals is scale-sensitive: 0.3 != 0.30.
        assertFailsWith<AssertionError> { assertEquals(Decimal.parse("0.3"), Decimal.parse("0.30")) }
        assertEquals(Decimal.parse("-0.0"), Decimal.parse("0.0"))
    }
}
