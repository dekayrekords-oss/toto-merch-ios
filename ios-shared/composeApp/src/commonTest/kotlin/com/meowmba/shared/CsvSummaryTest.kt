package com.meowmba.shared

import com.meowmba.shared.workflows.CsvSummary
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The four CSV tests from the original's `BusinessWorkflowTest`, ported verbatim
 * apart from the import.
 *
 * They are the reason [com.meowmba.shared.core.Decimal] exists: the last one
 * asserts that `0.10 + 0.20` prints as `0.30`, which `BigDecimal` does because it
 * preserves scale. That single assertion is what rules out a "tidy" decimal that
 * normalises trailing zeros, and it is also why the port could not simply use a
 * `Double` — `Double` prints `0.30000000000000004` for that sum.
 *
 * The assertions are left as `contains` rather than tightened to whole-output
 * equality, deliberately: the point of a ported test is to pin the original's
 * observable behaviour, and tightening it would make a later divergence from the
 * Android app invisible instead of failing.
 */
class CsvSummaryTest {

    @Test
    fun csvCaseAliasesAreUnambiguousAndMatchingModeIsReported() {
        val text = "id,amount,status\nA,7.11,unpaid\nB,2.22,Unpaid\nC,90.00,paid\n"
        val result = CsvSummary.summarize(text, "Amount", "Status", "Unpaid")
        assertTrue(result.contains("Selected rows: 2"))
        assertTrue(result.contains("Sum of amount: 9.33"))
        assertTrue(result.contains("case-insensitive"))
        val exact = CsvSummary.summarize(text, "amount", "status", "Unpaid", caseSensitive = true)
        assertTrue(exact.contains("Selected rows: 1"))
        assertTrue(exact.contains("Sum of amount: 2.22"))
        assertTrue(exact.contains("case-sensitive"))
    }

    @Test
    fun csvRejectsAmbiguousOrInventedColumnsWithActualChoices() {
        val text = "amount,Amount,status\n1,2,paid\n"
        assertTrue(runCatching { CsvSummary.summarize(text, "AMOUNT") }.exceptionOrNull()!!.message!!.contains("ambiguous"))
        assertTrue(CsvSummary.summarize(text, "Amount").contains("Sum of Amount: 2"))
        val error = runCatching { CsvSummary.summarize(text, "amount", "payment_status", "paid") }.exceptionOrNull()!!.message!!
        assertTrue(error.contains("amount, Amount, status"))
        assertTrue(error.contains("payment_status"))
    }

    @Test
    fun csvDoesNotFuzzyMatchOrTrimFilterValues() {
        val text = "amount,status\n1,unpaid\n2, unpaid\n4,paid\n"
        assertTrue(CsvSummary.summarize(text, "amount", "status", "Unpaid").contains("Sum of amount: 1"))
        assertTrue(CsvSummary.summarize(text, "amount", "status", "unpai").contains("Selected rows: 0"))
    }

    @Test
    fun csvHandlesQuotedFieldsNewlinesAndExactDecimals() {
        val text = "id,amount,note\r\n1,0.10,\"a,b\"\r\n2,0.20,\"a\nline\"\r\n"
        assertTrue(CsvSummary.summarize(text, "amount").contains("0.30"))
        for (invalid in listOf("id,amount\na,10USD", "id,amount\na,1,2", "id,amount\na,\"1")) {
            assertTrue(runCatching { CsvSummary.summarize(invalid, "amount") }.isFailure)
        }
    }
}
