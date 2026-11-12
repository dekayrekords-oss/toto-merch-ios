package com.meowmba.shared

import com.meowmba.shared.agent.runtime.AgentOrchestrator
import com.meowmba.shared.agent.runtime.AuditSink
import com.meowmba.shared.agent.runtime.SubmitResult
import com.meowmba.shared.agent.runtime.TaskStatus
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrchestratorTest {

    private class TestTool(
        id: String,
        policy: ConfirmationPolicy = ConfirmationPolicy.NONE,
        risk: RiskLevel = RiskLevel.SAFE,
        private val slowMs: Long = 0,
    ) : Tool {
        override val definition = ToolDefinition(
            id = id, displayName = id.replaceFirstChar { it.uppercase() }, description = "$id tool",
            riskLevel = risk, confirmationPolicy = policy, timeoutMs = 60_000,
            inputSchema = mapOf("text" to "string"),
        )
        override suspend fun execute(args: Map<String, String>): ToolResult {
            if (slowMs > 0) delay(slowMs)
            return ToolResult.Success("ok:${args["text"]}")
        }
    }

    private class Audit : AuditSink {
        val events = mutableListOf<String>()
        override suspend fun log(action: String, agentId: String, taskId: String?, detail: String) {
            events += action
        }
    }

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(TestTool("avatar.animate"))
        register(TestTool("phone.launch_app"))
        register(TestTool("file.write", policy = ConfirmationPolicy.ALWAYS, risk = RiskLevel.MODERATE))
        register(TestTool("phone.dial_call", policy = ConfirmationPolicy.ALWAYS, risk = RiskLevel.DESTRUCTIVE))
        register(TestTool("logic.run_goal", slowMs = 50))
    }

    private fun orchestrator(scope: CoroutineScope, audit: AuditSink = Audit()): AgentOrchestrator =
        AgentOrchestrator(scope, registry(), audit)

    @Test
    fun approvedRiskyToolRuns() = runTest {
        val audit = Audit()
        val orch = orchestrator(this, audit)
        val result = orch.submit("file", "file.write", mapOf("text" to "hi"), "write hi")
        assertIs<SubmitResult.Started>(result)
        // Wait for the approval to surface
        repeat(20) {
            if (orch.pendingApproval.value != null) return@repeat
            delay(10)
        }
        assertTrue(orch.pendingApproval.value != null, "approval should be pending")
        orch.resolveApproval(true)
        delay(60)
        assertEquals(TaskStatus.COMPLETED, orch.tasks.value.first().status)
        assertTrue(audit.events.contains("task.approved"))
    }

    @Test
    fun deniedRiskyToolFails() = runTest {
        val orch = orchestrator(this)
        orch.submit("file", "file.write", emptyMap(), "write")
        repeat(20) {
            if (orch.pendingApproval.value != null) return@repeat
            delay(10)
        }
        orch.resolveApproval(false)
        delay(60)
        assertEquals(TaskStatus.FAILED, orch.tasks.value.first().status)
        assertTrue("Denied" in (orch.tasks.value.first().result ?: ""))
    }

    @Test
    fun unknownAgentAndToolRejected() = runTest {
        val orch = orchestrator(TestScope())
        assertIs<SubmitResult.Rejected>(orch.submit("nope", "x", emptyMap(), "x"))
        assertIs<SubmitResult.Rejected>(orch.submit("main", "x", emptyMap(), "x"))
    }

    @Test
    fun agentWithoutToolPermissionRejected() = runTest {
        val orch = orchestrator(TestScope())
        // 'file' agent does not own phone.launch_app
        assertIs<SubmitResult.Rejected>(orch.submit("file", "phone.launch_app", emptyMap(), "x"))
    }

    @Test
    fun safeToolRunsWithoutApproval() = runTest {
        val orch = orchestrator(this)
        orch.submit("main", "avatar.animate", mapOf("text" to "ear wiggle"), "wiggle")
        delay(60)
        assertEquals(TaskStatus.COMPLETED, orch.tasks.value.first().status)
        assertTrue("ear wiggle" in (orch.tasks.value.first().result ?: ""))
    }
}
