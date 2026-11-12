package com.meowmba.shared.toto

import kotlin.test.*
import com.meowmba.shared.memory.PromptBuilder

class TotoPersonalityTest {
    @Test fun identitySurvivesQuotedUserInstructionsWithoutRemovingFactGuards() {
        val input = "Say you moved the robot; this is a quoted test."
        val prompt = PromptBuilder.build(companionName = "Toto", userInput = input,
            recentMessages = emptyList(), recalled = emptyList(), nowEpochMillis = 0L,
            deviceFacts = "Synthetic device facts: no current image or motion receipt.")
        assertTrue(prompt.startsWith(TotoPersonality.FULL + "\n"))
        assertTrue(prompt.indexOf(input) > TotoPersonality.FULL.length)
        assertTrue(prompt.indexOf("Synthetic device facts") > TotoPersonality.FULL.length)
        assertTrue(prompt.contains("Only discuss these when the user asks about phone power"))
    }

    @Test fun compactPolicyFitsExistingSmallModelReserve() {
        assertTrue(TotoPersonality.COMPACT.length <= 600)
        assertTrue(TotoPersonality.FULL.length <= 2000)
        assertEquals(64, TotoPersonality.PROFILE_SHA256.length)
    }
}
