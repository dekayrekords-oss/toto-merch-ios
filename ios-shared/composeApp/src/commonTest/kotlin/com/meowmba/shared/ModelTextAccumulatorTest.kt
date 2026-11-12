package com.meowmba.shared

import com.meowmba.shared.agent.ModelTextAccumulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelTextAccumulatorTest {
    @Test
    fun retainsJsonDeltasInOrder() {
        val stream = ModelTextAccumulator()
        listOf("{", "\"action\"", ":", "\"delegate\"", ",\"agentId\":\"analyst\"")
            .forEach(stream::append)
        assertEquals("""{"action":"delegate","agentId":"analyst"}""", stream.append("}"))
    }

    @Test
    fun repeatedTokensAndEmptyChunksAreRetained() {
        val stream = ModelTextAccumulator()
        stream.append("ha")
        stream.append("ha")
        stream.append("")
        assertEquals("hahaha", stream.append("ha"))
    }

    @Test
    fun outputLimitFailsClearly() {
        assertFailsWith<IllegalStateException> {
            ModelTextAccumulator(2).append("abc")
        }
    }
}