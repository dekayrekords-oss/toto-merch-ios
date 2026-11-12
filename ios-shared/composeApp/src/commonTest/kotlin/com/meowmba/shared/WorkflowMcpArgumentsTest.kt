package com.meowmba.shared

import com.meowmba.shared.workflows.WorkflowMcpArguments
import com.meowmba.shared.workflows.isIntegralJsonNumber
import com.meowmba.shared.workflows.isJsonNumber
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The MCP argument preflight: the pure check that runs before an approval card
 * is offered, so malformed arguments are corrected without ever contacting a
 * service.
 *
 * Ported from the first two tests of the original's `BusinessMcpTest` (the third
 * drives `WorkflowMcpTools`, which is blocked on the MCP transport). The
 * assertions are unchanged in substance; only the JSON accessors differ, because
 * `decode` returns a kotlinx `JsonObject` rather than an `org.json.JSONObject`.
 *
 * The last two tests are port-only: they cover the two helpers that replaced
 * `java.math.BigDecimal`, which does not exist on Kotlin/Native. That is exactly
 * the code the ported suite cannot reach, so it is pinned here against values
 * worked out from the JSON number grammar rather than read back out of the
 * implementation.
 */
class WorkflowMcpArgumentsTest {

    private val schema = """{"type":"object","properties":{"limit":{"type":"integer"}},"required":["limit"]}"""

    @Test
    fun namedArgumentsPreserveServerTypesAndNestedValues() {
        val typed =
            """{"type":"object","properties":{"code":{"type":"string"},"limit":{"type":"integer"},"enabled":{"type":"boolean"},"filters":{"type":"object","properties":{"status":{"type":"string","enum":["open"]}},"required":["status"]}},"required":["code","limit"],"additionalProperties":false}"""
        val args = mapOf("code" to "0012", "limit" to "3", "enabled" to "false", "filters" to "{\"status\":\"open\"}")
        val decoded = WorkflowMcpArguments.decode(typed, args)
        assertEquals("0012", decoded["code"]!!.jsonPrimitive.content)
        assertEquals(3, decoded["limit"]!!.jsonPrimitive.int)
        assertFalse(decoded["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("open", decoded["filters"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("integer", WorkflowMcpArguments.describe(typed)["limit"])
        assertTrue(runCatching { WorkflowMcpArguments.decode(typed, args + ("limit" to "three")) }.isFailure)
        assertTrue(runCatching { WorkflowMcpArguments.decode(typed, args + ("filters" to "{\"status\":\"closed\"}")) }.isFailure)
        assertTrue(runCatching { WorkflowMcpArguments.decode(typed, args + ("invented" to "x")) }.isFailure)
    }

    @Test
    fun jsonEnvelopeIsStrictAndDoesNotStealNativeArgumentsField() {
        assertEquals(3, WorkflowMcpArguments.decode(schema, mapOf("arguments" to "{\"limit\":3}"))["limit"]!!.jsonPrimitive.int)
        assertTrue(runCatching { WorkflowMcpArguments.decode(schema, mapOf("arguments" to "{\"limit\":\"3\"}")) }.isFailure)
        assertTrue(runCatching { WorkflowMcpArguments.decode(schema, mapOf("arguments" to "{\"limit\":3} garbage")) }.isFailure)
        val native = """{"type":"object","properties":{"arguments":{"type":"string"}},"required":["arguments"]}"""
        assertEquals(
            "text",
            WorkflowMcpArguments.decode(native, mapOf("arguments" to "text"))["arguments"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun describeFallsBackToAnOpaqueJsonObjectForUnusableSchemas() {
        assertEquals(mapOf("arguments" to "JSON object string"), WorkflowMcpArguments.describe("""{"type":"object"}"""))
        assertEquals(mapOf("arguments" to "JSON object string"), WorkflowMcpArguments.describe("""{"properties":[]}"""))
        assertEquals(mapOf("note" to "string"), WorkflowMcpArguments.describe("""{"properties":{"note":{}}}"""))
    }

    /**
     * `BigDecimal(content).stripTrailingZeros().scale() <= 0` — "has no fractional
     * part". Reference values are the decimal expansions, worked out by hand.
     */
    @Test
    fun integralityMatchesTheDecimalExpansionNotTheDoubleConversion() {
        for (integral in listOf(
            "0", "5", "-5", "20", "5.0", "5.00", "0.0", "-0.0", "20.00", "-2", "10.0",
            "5e2", "5E2", "5e+2", "10e-1", "1e400", "1.5e400", "0e0",
        )) {
            assertTrue(isIntegralJsonNumber(integral), "expected integral: $integral")
        }
        for (fractional in listOf(
            "0.5", "5.5", "-2.5", "-2.50", "2.50", "0.1", "1e-1", "1e-2", "1.5", "0.01",
        )) {
            assertFalse(isIntegralJsonNumber(fractional), "expected fractional: $fractional")
        }
        // The trap this helper exists to avoid: a Double round-trip reports both
        // of these as "not a number", and `1e400` as Infinity.
        assertTrue(isIntegralJsonNumber("1e400"))
        assertTrue(isIntegralJsonNumber("123456789012345678901234567890"))
    }

    @Test
    fun numberDetectionExcludesBooleansStringsAndJunk() {
        assertTrue(isJsonNumber(JsonPrimitive(5)))
        assertTrue(isJsonNumber(JsonPrimitive(5.5)))
        assertTrue(isJsonNumber(JsonPrimitive(-0.0)))
        assertFalse(isJsonNumber(JsonPrimitive(true)))
        assertFalse(isJsonNumber(JsonPrimitive(false)))
        assertFalse(isJsonNumber(JsonPrimitive("5")))
        assertFalse(isJsonNumber(JsonPrimitive("abc")))
        // Not reachable through `decode` (JsonNull is matched earlier), but the
        // predicate is used directly elsewhere, so pin the edge.
        assertFalse(isIntegralJsonNumber(""))
        assertFalse(isIntegralJsonNumber("e5"))
        assertFalse(isIntegralJsonNumber("1e"))
    }
}
