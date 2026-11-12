package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.openWrite
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.agent.use
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.workflows.WorkflowTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The review outbox: durable, deduplicated, and with no way to send anything.
 *
 * Ported from the drafts half of the original's `WorkflowStartersTest`
 * (`duplicateEventDoesNotOverwriteDraftOrSend`,
 * `eventIdentityIncludesAccountAndRejectsMissingIds`), plus new tests for the
 * parts the original only exercised indirectly — the JSON the draft records, the
 * NUL separator in the dedup key, and the wording of the "no draft does not
 * prove nothing was sent" answer.
 *
 * Every test goes through `WorkflowTools.local(root)` rather than
 * `WorkflowDrafts.register` directly, because that call is also the wiring: if
 * `local` ever stopped registering the outbox tools, these would fail rather
 * than quietly testing an unreachable API.
 *
 * The case where the `drafts` folder is a symlink out of the workspace needs
 * `java.nio.file.Files`, so it lives in `WorkflowToolsSymlinkTest`.
 */
class WorkflowDraftsTest {

    private fun root(): ModelFile = ModelFile(PlatformTemp.createDirectory("wf-drafts"))

    private fun ToolRegistry.outcome(id: String, args: Map<String, String>): ToolResult =
        runBlocking { byId(id)!!.execute(args) }

    private fun draftFiles(root: ModelFile): List<ModelFile> = ModelFile("${root.path}/drafts").listFiles()

    private val event = mapOf(
        "source" to "mail/test-account",
        "event_id" to "message-123",
        "recipient" to "test@example.invalid",
        "subject" to "Hello",
        "body" to "Draft text",
    )

    // -- ported -----------------------------------------------------------

    @Test
    fun duplicateEventDoesNotOverwriteDraftOrSend() {
        val root = root()
        val tools = WorkflowTools.local(root)

        val first = tools.outcome("drafts.prepare", event) as ToolResult.Success
        assertTrue(first.output.contains("NOT sent"), first.output)

        // A second registry over the same directory, so the deduplication being
        // tested is the one on disk rather than anything held in memory.
        val second = WorkflowTools.local(root).outcome("drafts.prepare", event + ("body" to "changed")) as
            ToolResult.Success
        assertTrue(second.output.contains("nothing sent or overwritten"), second.output)

        val files = draftFiles(root)
        assertEquals(1, files.size)
        assertTrue(files.single().readText()!!.contains("Draft text"))
        assertFalse(files.single().readText()!!.contains("changed"))

        assertTrue(
            (tools.outcome("events.check", event) as ToolResult.Success).output.contains("Already drafted"),
        )
    }

    @Test
    fun eventIdentityIncludesAccountAndRejectsMissingIds() {
        val root = root()
        fun prepare(args: Map<String, String>) = WorkflowTools.local(root).outcome("drafts.prepare", args)
        val args = mapOf(
            "source" to "account-one",
            "event_id" to "123",
            "recipient" to "test@example.invalid",
            "body" to "test",
        )

        assertTrue(prepare(args) is ToolResult.Success)
        assertTrue(prepare(args + ("source" to "account-two")) is ToolResult.Success)
        assertEquals(2, draftFiles(root).size)
        assertTrue(prepare(args - "event_id") is ToolResult.Failure)
    }

    // -- the draft's recorded state ---------------------------------------

    @Test
    fun theDraftRecordsItsReviewStateAndIsNeverMarkedSent() {
        val root = root()
        val before = PlatformTime.currentMillis()
        WorkflowTools.local(root).outcome("drafts.prepare", event)

        val draft = draftFiles(root).single()
        val json = Json.parseToJsonElement(draft.readText()!!).jsonObject
        assertEquals("DRAFT_REQUIRES_REVIEW", json.getValue("status").jsonPrimitive.content)
        assertEquals("false", json.getValue("sent").jsonPrimitive.content)
        assertTrue(json.getValue("createdAt").jsonPrimitive.content.toLong() >= before)
        for (key in listOf("source", "event_id", "recipient", "subject", "body")) {
            assertEquals(event.getValue(key), json.getValue(key).jsonPrimitive.content, key)
        }
        // The filename is the dedup key and a hash rather than the raw event id,
        // so two services using the same numeric id cannot collide on one file.
        assertTrue(draft.name.matches(Regex("[0-9a-f]{64}\\.json")), draft.name)
    }

    @Test
    fun draftIdentityKeepsSourceAndEventApartAtTheSeparator() {
        val root = root()
        val tools = WorkflowTools.local(root)
        fun prepare(source: String, eventId: String, body: String) = tools.outcome(
            "drafts.prepare",
            mapOf("source" to source, "event_id" to eventId, "recipient" to "r@example.invalid", "body" to body),
        )

        assertTrue(prepare("a", "bc", "first") is ToolResult.Success)
        // Without the NUL separator in the identity, ("a","bc") and ("ab","c") both
        // hash "abc" and the second event would be silently reported as already
        // drafted -- a customer who never gets a reply, and no error anywhere.
        val second = prepare("ab", "c", "second") as ToolResult.Success
        assertTrue(second.output.contains("NOT sent"), second.output)
        assertEquals(2, draftFiles(root).size)
    }

    // -- refusals ---------------------------------------------------------

    @Test
    fun prepareRefusesMissingRecipientBodyAndOversizedDrafts() {
        val root = root()
        val tools = WorkflowTools.local(root)
        val args = mapOf("source" to "s", "event_id" to "e", "recipient" to "r", "body" to "b")

        assertTrue(tools.outcome("drafts.prepare", args - "recipient") is ToolResult.Failure)
        assertTrue(tools.outcome("drafts.prepare", args - "body") is ToolResult.Failure)
        assertTrue(tools.outcome("drafts.prepare", args + ("body" to "z".repeat(30_001))) is ToolResult.Failure)
        assertTrue(tools.outcome("drafts.prepare", args + ("source" to "s".repeat(501))) is ToolResult.Failure)

        assertEquals(0, draftFiles(root).size, "a refused draft must leave nothing behind")
    }

    @Test
    fun anExistingDraftIsKeptEvenWhenTheRetryIsItselfInvalid() {
        val root = root()
        val tools = WorkflowTools.local(root)
        assertTrue(tools.outcome("drafts.prepare", event) is ToolResult.Success)

        // The dedup check runs before validation on purpose: a retry that arrives
        // with an empty body must be told "already drafted", not handed an error it
        // would then try to repair by inventing content.
        val retry = tools.outcome("drafts.prepare", event + ("body" to "")) as ToolResult.Success
        assertTrue(retry.output.contains("nothing sent or overwritten"), retry.output)
        assertTrue(draftFiles(root).single().readText()!!.contains("Draft text"))
    }

    @Test
    fun theOutboxRefusesToGrowPastTwoThousandDrafts() {
        val root = root()
        val drafts = ModelFile("${root.path}/drafts").also { it.mkdirs() }
        // Plain sinks rather than atomic writes: 2,000 fsyncs would make this test
        // measure the disk rather than the guard.
        repeat(2000) { i ->
            ModelFile("${drafts.path}/f$i.json").openWrite().use { it.write("{}".encodeToByteArray(), 0, 2) }
        }

        val failure = WorkflowTools.local(root).outcome(
            "drafts.prepare",
            mapOf("source" to "s", "event_id" to "e", "recipient" to "r", "body" to "b"),
        ) as ToolResult.Failure
        assertTrue(failure.error.contains("Outbox full"), failure.error)
        assertEquals(2000, drafts.listFiles().size, "a refused draft must not be written")
    }

    // -- the answers the model is given -----------------------------------

    @Test
    fun eventsCheckNeverClaimsAnExternalReplyWasSent() {
        val root = root()
        val tools = WorkflowTools.local(root)
        val identity = mapOf("source" to "s", "event_id" to "e")

        // Absence of a local draft says nothing about the outside world, and the
        // model must not be able to read this as "no reply was sent".
        val before = (tools.outcome("events.check", identity) as ToolResult.Success).output
        assertTrue(before.contains("does not prove"), before)

        tools.outcome("drafts.prepare", identity + mapOf("recipient" to "r", "body" to "b"))
        assertTrue((tools.outcome("events.check", identity) as ToolResult.Success).output.contains("Already drafted"))
    }

    @Test
    fun draftsListShowsEveryDraftAndIgnoresNonJsonFiles() {
        val root = root()
        val tools = WorkflowTools.local(root)
        assertEquals("No drafts saved.", (tools.outcome("drafts.list", emptyMap()) as ToolResult.Success).output)

        ModelFile("${root.path}/drafts/notes.txt").also { it.writeBytesAtomic("x".encodeToByteArray()) }
        assertEquals("No drafts saved.", (tools.outcome("drafts.list", emptyMap()) as ToolResult.Success).output)

        val prepared = tools.outcome("drafts.prepare", event) as ToolResult.Success
        val name = prepared.output.substringAfterLast("drafts/")
        assertEquals("drafts/$name", (tools.outcome("drafts.list", emptyMap()) as ToolResult.Success).output)
    }
}
