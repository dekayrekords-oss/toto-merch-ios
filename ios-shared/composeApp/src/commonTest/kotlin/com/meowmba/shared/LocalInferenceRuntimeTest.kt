package com.meowmba.shared

import com.meowmba.shared.agent.LocalInferenceRuntime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalInferenceRuntimeTest {
    private class Resource(
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        override fun close() = onClose()
    }

    @Test
    fun retainsOneResidentResourcePerKeyAndReleasesWhenIdle() = runTest {
        var created = 0
        var closed = 0
        var now = 10L
        val runtime = LocalInferenceRuntime(clock = { now })

        runtime.withModel("chat", { created++; Resource { closed++ } }) { "one" }
        runtime.withModel("chat", { created++; Resource { closed++ } }) { "two" }

        assertEquals(1, created)
        assertEquals(0, closed)
        assertTrue(runtime.isLoaded)
        assertEquals(0, runtime.idleMs())

        now = 100L
        assertTrue(runtime.releaseIdle(minIdleMs = 50))
        assertEquals(1, closed)
        assertFalse(runtime.isLoaded)
    }

    @Test
    fun switchingKeysClosesThePreviousResource() = runTest {
        var closed = 0
        val runtime = LocalInferenceRuntime(clock = { 100L })

        runtime.withModel("first", { Resource { closed++ } }) { }
        runtime.withModel("second", { Resource { closed++ } }) { }

        assertEquals(1, closed)
        assertTrue(runtime.isLoaded)
        assertTrue(runtime.release())
        assertEquals(2, closed)
    }
}