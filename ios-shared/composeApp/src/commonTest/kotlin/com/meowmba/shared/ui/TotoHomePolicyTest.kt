package com.meowmba.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class TotoHomePolicyTest {
    @Test fun legacyModeAndRobotRoutesCannotLeaveMountedHome() {
        listOf("Flow", "Desk", "flow", " Robot ", "Cozmo", "Auto").forEach {
            assertNull(TotoHomePolicy.destination(it))
        }
    }
    @Test fun appearanceCannotReenableWorkspaceThemes() {
        assertEquals("Avatar", TotoHomePolicy.destination("Appearance"))
        assertEquals(Page.AVATAR, TotoHomePolicy.page(Page.APPEARANCE))
        assertEquals(Page.SETTINGS, TotoHomePolicy.page(Page.COZMO))
    }
    @Test fun backendAndMemoryRemainReachable() {
        listOf("Chat", "Remote models", "LLM", "Memory", "Voice & behavior", "Merch table", "Settings").forEach {
            assertEquals(it, TotoHomePolicy.destination(it))
        }
        assertFalse(TotoHomePolicy.menu.any { it in listOf("Flow", "Desk", "Robot") })
    }
}
