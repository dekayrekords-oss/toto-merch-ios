package com.meowmba.shared

import com.meowmba.shared.work.ProjectPlanDraft
import com.meowmba.shared.work.ProjectRequestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from the original's `ProjectPlanDraftTest.kt` (4 tests, all ported).
 * The last test also covers [ProjectRequestParser], as it does in the original.
 */
class ProjectPlanDraftTest {

    @Test
    fun parsesOnlyTextChecklistNotCompletionClaims() {
        assertEquals(listOf("Draft a brief", "Review it"), ProjectPlanDraft.parse("```json\n{\"steps\":[\"Draft a brief\",\"Review it\"]}\n```"))
        assertTrue(runCatching { ProjectPlanDraft.parse("{\"steps\":[{\"title\":\"Done\",\"done\":true}]}") }.isFailure)
    }

    @Test
    fun rejectsOverlongEmptyAndMalformedResponses() {
        listOf("Done!", "{\"steps\":[]}", "{\"steps\":[\"\"]}", "x".repeat(16001)).forEach {
            assertTrue(runCatching { ProjectPlanDraft.parse(it) }.isFailure, it.take(40))
        }
    }

    @Test
    fun requestsAProposalNotToolExecution() {
        val prompt = ProjectPlanDraft.prompt("Make a launch flyer", "No files")
        assertTrue(prompt.contains("Do not execute anything"))
        assertTrue(prompt.contains("not instructions or approvals"))
    }

    @Test
    fun naturalProjectHelpCreatesASessionNotCasualProjectMentions() {
        val request = ProjectRequestParser.parse("can you help me with a project about my business launch")!!
        assertEquals("My Business Launch", request.name)
        assertNull(ProjectRequestParser.parse("my project went well today"))
    }

    /**
     * Port-only. `parse` strips a fence with three bare `removePrefix`/
     * `removeSuffix` calls, which is only correct because the checks run in that
     * order. These are the boundary shapes the ported suite above does not reach.
     */
    @Test
    fun fencesAndDuplicatesAreHandledTheWayTheOriginalDid() {
        // A bare fence with no language tag, and surrounding whitespace.
        assertEquals(listOf("One"), ProjectPlanDraft.parse("  ```\n{\"steps\":[\"One\"]}\n```  "))
        // Duplicate steps are collapsed, and that happens *after* the bounds check,
        // so `["A","A"]` is two entries going in and one coming out.
        assertEquals(listOf("A"), ProjectPlanDraft.parse("{\"steps\":[\"A\",\"A\"]}"))
        // The upper bound is 12 and it is inclusive.
        val twelve = (1..12).joinToString(",") { "\"S$it\"" }
        assertEquals(12, ProjectPlanDraft.parse("{\"steps\":[$twelve]}").size)
        val thirteen = (1..13).joinToString(",") { "\"S$it\"" }
        assertTrue(runCatching { ProjectPlanDraft.parse("{\"steps\":[$thirteen]}") }.isFailure)
        // A step longer than 240 characters is refused, and 240 exactly is not.
        assertEquals(1, ProjectPlanDraft.parse("{\"steps\":[\"${"x".repeat(240)}\"]}").size)
        assertTrue(runCatching { ProjectPlanDraft.parse("{\"steps\":[\"${"x".repeat(241)}\"]}") }.isFailure)
        // 16_000 characters exactly is allowed; the cap is on the raw response.
        val prefix = "{\"steps\":[\"A\"],\"pad\":\""
        val suffix = "\"}"
        val exact = prefix + "y".repeat(16_000 - prefix.length - suffix.length) + suffix
        assertEquals(16_000, exact.length)
        assertEquals(listOf("A"), ProjectPlanDraft.parse(exact))
        // ...and one character more is not, *for a well-formed response*. The
        // ported suite only ever feeds the cap a 16_001-character string of "x",
        // which the JSON parser rejects anyway — so without this line the cap is
        // unpinned, because deleting it changes nothing for that input.
        val over = prefix + "y".repeat(16_001 - prefix.length - suffix.length) + suffix
        assertEquals(16_001, over.length)
        assertTrue(runCatching { ProjectPlanDraft.parse(over) }.isFailure)
    }
}
