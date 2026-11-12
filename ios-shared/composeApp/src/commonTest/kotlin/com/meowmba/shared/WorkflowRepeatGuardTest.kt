package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.core.TextStorage
import com.meowmba.shared.workflows.BusinessAgent
import com.meowmba.shared.workflows.WorkDecision
import com.meowmba.shared.workflows.WorkflowBrain
import com.meowmba.shared.workflows.WorkflowEngine
import com.meowmba.shared.workflows.WorkflowStore
import com.meowmba.shared.workflows.WorkflowTools
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The repeat guard: a model that loops on the same successful call is stopped,
 * not allowed to burn the battery and the user's approval budget.
 *
 * Ported from the original's `BusinessRepeatGuardTest`. It could not be ported
 * earlier for the same reason the two `WorkflowEngineTest` cases could not: it
 * builds the registry with `WorkflowTools.local(root)` and asserts on the files
 * that tool writes. With the workspace seam in place it ports unchanged apart
 * from the file types.
 *
 * The three cases are the two halves of the guard and its deliberate exception:
 * a repeated write is blocked, a repeated read is blocked, and a read that
 * follows an intervening *write* is allowed — because the write genuinely
 * changed what the next read would observe, which is the distinction the guard
 * has to make to avoid deadlocking a legitimate verify loop.
 */
class WorkflowRepeatGuardTest {

    private fun store(storage: TextStorage = MemoryTextStorage()) = WorkflowStore(storage)

    private fun workspaceRoot() =
        ModelFile("${PlatformTemp.createDirectory("repeat-guard")}/workspace").also { it.mkdirs() }

    private fun agent(id: String, instructions: String, tools: Set<String>) =
        BusinessAgent(id = id, name = id, instructions = instructions, tools = tools)

    @Test
    fun repeatedSuccessfulWriteStopsBeforeAnotherApprovalOrVersion() = runBlocking {
        val root = workspaceRoot()
        val store = store()
        store.saveAgent(agent("writer", "Write once", setOf("files.write")))
        val runId = store.enqueue("writer", "Write a report")
        val engine = WorkflowEngine(
            store, WorkflowTools.local(root),
            WorkflowBrain { _, _, _, _, _ ->
                WorkDecision("tool", "files.write", mapOf("path" to "report.md", "content" to "Observed report"))
            },
        )

        engine.tick(runId)
        store.approve(runId, store.run(runId)!!.steps.last().id, true)
        engine.tick(runId); engine.tick(runId)

        val result = store.run(runId)!!
        assertEquals("BLOCKED", result.status)
        assertTrue(result.note.contains("repeated"), result.note)
        // One step, so the second attempt never reached the approval prompt either.
        assertEquals(1, result.steps.size)
        assertEquals("Observed report", ModelFile("${root.path}/report.md").readText())
    }

    @Test
    fun repeatedReadStopsUntilAnInterveningObservedActionChangesContext() = runBlocking {
        val store = store()
        store.saveAgent(agent("reader", "Read once", setOf("files.list")))
        val runId = store.enqueue("reader", "Read files")
        val engine = WorkflowEngine(
            store, WorkflowTools.local(workspaceRoot()),
            WorkflowBrain { _, _, _, _, _ -> WorkDecision("tool", "files.list") },
        )

        engine.tick(runId); engine.tick(runId); engine.tick(runId)

        assertEquals("BLOCKED", store.run(runId)!!.status)
        assertEquals(1, store.run(runId)!!.steps.size)
    }

    @Test
    fun readWriteReadVerificationRemainsAllowed() = runBlocking {
        val root = workspaceRoot()
        ModelFile("${root.path}/report.md").writeBytesAtomic("Original".encodeToByteArray())
        val store = store()
        store.saveAgent(agent("editor", "Update and verify", setOf("files.read", "files.write")))
        val runId = store.enqueue("editor", "Update report")
        val engine = WorkflowEngine(
            store, WorkflowTools.local(root),
            WorkflowBrain { _, _, run, _, _ ->
                when (run.steps.count { it.status == "SUCCEEDED" }) {
                    0, 2 -> WorkDecision("tool", "files.read", mapOf("path" to "report.md"))
                    1 -> WorkDecision("tool", "files.write", mapOf("path" to "report.md", "content" to "Changed"))
                    else -> WorkDecision("finish", summary = "Read updated report", evidence = listOf(run.steps.last().id))
                }
            },
        )

        repeat(8) {
            engine.tick(runId)
            if (store.run(runId)!!.status == "APPROVAL") {
                store.approve(runId, store.run(runId)!!.steps.last().id, true)
            }
        }

        assertEquals("COMPLETE", store.run(runId)!!.status)
        assertEquals(2, store.run(runId)!!.steps.count { it.tool == "files.read" })
        // The write really landed: the second read was allowed because the file
        // changed, so if it had not changed, the guard would be reading its own
        // bookkeeping rather than the workspace.
        assertEquals("Changed", ModelFile("${root.path}/report.md").readText())
    }
}
