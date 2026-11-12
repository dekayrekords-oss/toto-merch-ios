package com.meowmba.shared.workflows

import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.core.PlatformTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
data class WorkDecision(
    val action: String,
    val tool: String = "",
    val args: Map<String, String> = emptyMap(),
    val agentId: String = "",
    val goal: String = "",
    val summary: String = "",
    val evidence: List<String> = emptyList(),
)

fun interface WorkflowBrain {
    suspend fun decide(
        agent: BusinessAgent,
        frame: WorkFrame,
        run: BusinessRun,
        tools: List<ToolDefinition>,
        team: List<BusinessAgent>,
    ): WorkDecision
}

/**
 * One durable transition per tick. Runs share a worker, never a model per avatar.
 * Every action is constrained against the current agent/tool catalog. A crashed
 * mutation is held for review, never silently replayed.
 *
 * Ported from `core/workflows/WorkflowEngine.kt`. The logic is unchanged; the
 * only translation is `System.currentTimeMillis()` → [PlatformTime], which
 * matters here because approval expiry is a real deadline rather than a stamp.
 */
class WorkflowEngine(
    private val store: WorkflowStore,
    private val registry: ToolRegistry,
    private val brain: WorkflowBrain,
    private val allowedToWork: () -> Boolean = { true },
    private val hasPermission: (String) -> Boolean = { false },
) {
    suspend fun tick(id: String): Boolean {
        val run = store.run(id) ?: return false
        if (run.status in WorkflowStore.TERMINAL) return false
        if (run.status == "APPROVAL") {
            val expiry = run.steps.lastOrNull()?.approvalExpiresAt ?: 0
            if (expiry > 0 && PlatformTime.currentMillis() >= expiry) {
                block(id, "Approval expired; no action was taken")
            }
            return false
        }
        if (!allowedToWork()) {
            store.transition(run) {
                it.copy(status = "PAUSED", note = "Waiting for the phone to cool or leave power-saving mode")
            }
            return false
        }
        val frame = run.frames.lastOrNull() ?: return block(id, "No active agent")
        val agent = store.agent(frame.agentId)?.takeIf { it.enabled } ?: return block(id, "Agent disabled or removed")
        // Parent permissions can be revoked while a delegate is running.
        run.frames.zipWithNext().forEach { (parent, child) ->
            val owner = store.agent(parent.agentId)
            if (owner?.enabled != true || child.agentId !in owner.delegates) {
                return block(id, "Delegation permission was removed")
            }
        }
        val pending = run.steps.lastOrNull()?.takeIf { it.status in setOf("PENDING", "APPROVED", "EXECUTING") }
        if (pending != null) return execute(run, agent, pending)
        if (run.steps.size >= 32 || run.attempts >= 40) {
            return block(id, "Step budget reached; review the results before starting another job")
        }
        if (!store.transition(run) {
                it.copy(status = "RUNNING", attempts = it.attempts + 1, note = "${agent.name} is planning the next step")
            }
        ) {
            return false
        }
        val planning = store.run(id) ?: return false
        try {
            val decision = cancellableJob(id) {
                withTimeout(90_000) {
                    brain.decide(
                        agent, frame, run,
                        registry.definitions().filter { it.id in agent.tools },
                        store.state.value.agents.filter {
                            it.id in agent.delegates && it.enabled && !delegateCompleted(run, frame, it.id)
                        },
                    )
                }
            }
            if (store.run(id) != planning) return false
            if (store.agent(agent.id) != agent) {
                return block(id, "Agent configuration changed; start a new job with the updated permissions")
            }
            when (decision.action) {
                "tool" -> {
                    val tool = registry.byId(decision.tool) ?: return block(id, "Tool unavailable: ${decision.tool}")
                    if (decision.tool !in agent.tools) {
                        return block(id, "${agent.name} is not allowed to use ${decision.tool}")
                    }
                    if (!tool.definition.requiredPermissions.all(hasPermission)) {
                        return block(id, "Required phone permission is missing")
                    }
                    // A repeated successful mutation is not new progress. Stop
                    // before another approval card or external dispatch; the
                    // previous action's saved evidence is already available.
                    // A read may repeat after other successful work changed the
                    // state (e.g. read -> write -> verify), but not in a loop.
                    val observed = run.steps.drop(frame.startedAtStep).filter {
                        it.agentId == agent.id && it.status == "SUCCEEDED" && it.tool != "agent.result"
                    }
                    val repeated = observed.filter { it.tool == decision.tool && it.args == decision.args }
                    if (repeated.isNotEmpty() && (tool.definition.riskLevel != RiskLevel.SAFE || observed.lastOrNull() in repeated)) {
                        return block(
                            id,
                            "Stopped a repeated ${decision.tool} action. Its previous result is saved; review it before starting another job.",
                        )
                    }
                    require(decision.args.values.sumOf { it.length } <= 100_000) { "Arguments too large" }
                    try {
                        (tool as? WorkflowArgumentValidator)?.validateArguments(decision.args)
                    } catch (e: Exception) {
                        return rejectCompletion(
                            planning, agent,
                            "Invalid tool arguments: ${e.message}. Correct them before requesting approval. No service call was made.",
                        )
                    }
                    val approval = tool.definition.confirmationPolicy == ConfirmationPolicy.ALWAYS ||
                        tool.definition.riskLevel == RiskLevel.DESTRUCTIVE
                    val step = WorkStep(
                        id = "s${planning.steps.size + 1}",
                        agentId = agent.id,
                        tool = decision.tool,
                        args = decision.args,
                        approvalRequired = approval,
                        approvalExpiresAt = if (tool.definition.riskLevel == RiskLevel.DESTRUCTIVE) {
                            PlatformTime.currentMillis() + 60_000
                        } else {
                            0
                        },
                    )
                    store.transition(planning) {
                        it.copy(
                            steps = it.steps + step,
                            status = if (approval) "APPROVAL" else "QUEUED",
                            note = if (approval) "Review ${tool.definition.displayName}" else "Next: ${tool.definition.displayName}",
                        )
                    }
                    return !approval
                }
                "delegate" -> {
                    if (decision.agentId !in agent.delegates || run.frames.any { it.agentId == decision.agentId } || run.frames.size >= 4) {
                        return block(id, "Delegation is not permitted or would create a cycle")
                    }
                    if (delegateCompleted(run, frame, decision.agentId)) {
                        return block(
                            id,
                            "That delegate already finished this job. Use its recorded results; start a new job for another assignment.",
                        )
                    }
                    require(decision.goal.isNotBlank())
                    val child = store.agent(decision.agentId)?.takeIf { it.enabled }
                        ?: return block(id, "Delegate unavailable")
                    store.transition(planning) {
                        it.copy(
                            frames = it.frames + WorkFrame(child.id, decision.goal.take(8000), it.steps.size),
                            note = "Delegated to ${child.name}",
                        )
                    }
                }
                "finish" -> {
                    val successful = run.steps.drop(frame.startedAtStep).filter { it.status == "SUCCEEDED" }.map { it.id }.toSet()
                    val executed = run.steps.drop(frame.startedAtStep)
                        .filter { it.agentId == agent.id && it.status == "SUCCEEDED" }.map { it.tool }.toSet()
                    val missing = agent.completionTools - executed
                    if (missing.isNotEmpty()) {
                        return rejectCompletion(
                            planning, agent,
                            "You have not completed the required actions: ${missing.joinToString()}. Use the appropriate tool before finishing.",
                        )
                    }
                    if (decision.summary.isBlank() || decision.evidence.isEmpty() || !successful.containsAll(decision.evidence)) {
                        return if (successful.isEmpty()) {
                            block(id, "Agent reported completion without valid tool evidence")
                        } else {
                            rejectCompletion(
                                planning, agent,
                                "Use exact successful evidence IDs: ${successful.joinToString()}. The attempted finish was rejected. Continue the current goal if more work remains.",
                            )
                        }
                    }
                    store.transition(planning) {
                        // Pass actual cited observations with the interpretation.
                        // A small model's weak summary must not erase the exact
                        // totals or verified artifact paths its tools returned.
                        val evidenceText = run.steps.filter { it.id in decision.evidence }.joinToString("\n") { step ->
                            "${step.id} ${step.tool}: ${step.output.take(2500)}" +
                                if (step.output.length > 2500) " [excerpt; see step for full result]" else ""
                        }.let { if (it.length <= 8000) it else it.take(8000) + " [evidence excerpt; see steps]" }
                        val summary = "Agent summary: ${decision.summary.take(3000)}\nRecorded tool evidence:\n$evidenceText"
                        val result = WorkStep(
                            id = "s${it.steps.size + 1}", agentId = agent.id, tool = "agent.result",
                            args = emptyMap(), status = "SUCCEEDED", output = summary,
                        )
                        it.copy(
                            steps = it.steps + result,
                            frames = it.frames.dropLast(1),
                            status = if (it.frames.size == 1) "COMPLETE" else "QUEUED",
                            note = summary,
                        )
                    }
                }
                "blocked" -> return block(id, decision.summary.ifBlank { "The agent needs your input" })
                else -> return block(id, "Unrecognized agent action; no tool was executed")
            }
        } catch (_: StoppedByUser) {
            return false
        } catch (_: WorkPaused) {
            store.updateRun(id) {
                if (it.status == "CANCELLED") it else it.copy(status = "PAUSED", note = "Planning paused to limit heat or power use")
            }
            return false
        } catch (e: TimeoutCancellationException) {
            return block(id, "Model timed out; no new action was executed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return block(id, e.message ?: "Agent failed")
        }
        return store.run(id)?.status !in WorkflowStore.TERMINAL
    }

    private suspend fun execute(run: BusinessRun, agent: BusinessAgent, step: WorkStep): Boolean {
        val tool = registry.byId(step.tool) ?: return block(run.id, "Tool was disconnected")
        if (step.agentId != agent.id || step.tool !in agent.tools) return block(run.id, "Tool permission was removed")
        if (!tool.definition.requiredPermissions.all(hasPermission)) return block(run.id, "Required phone permission is missing")
        val needsApproval = step.approvalRequired ||
            tool.definition.confirmationPolicy == ConfirmationPolicy.ALWAYS ||
            tool.definition.riskLevel == RiskLevel.DESTRUCTIVE
        if (step.status == "EXECUTING") {
            // A process death can occur between external success and checkpoint.
            // Human review is safer than repeating a possibly completed action.
            return block(
                run.id,
                "Interrupted during ${step.tool}. Check its result before starting again; this action was not replayed.",
            )
        }
        if (needsApproval && step.status != "APPROVED") {
            store.transition(run) {
                it.copy(
                    status = "APPROVAL", note = "Review ${step.tool}",
                    steps = it.steps.dropLast(1) + step.copy(
                        approvalRequired = true,
                        approvalExpiresAt = if (tool.definition.riskLevel == RiskLevel.DESTRUCTIVE) {
                            PlatformTime.currentMillis() + 60_000
                        } else {
                            0
                        },
                    ),
                )
            }
            return false
        }
        if (step.approvalExpiresAt > 0 && PlatformTime.currentMillis() >= step.approvalExpiresAt) {
            return block(run.id, "Approval expired; no action was taken")
        }
        if (!store.transition(run) {
                it.copy(steps = it.steps.dropLast(1) + step.copy(status = "EXECUTING"), status = "RUNNING")
            }
        ) {
            return false
        }
        try {
            val result = cancellableJob(run.id) { withTimeout(tool.definition.timeoutMs) { tool.execute(step.args) } }
            val output = when (result) {
                is ToolResult.Success -> result.output
                is ToolResult.Failure -> result.error
            }
            val safeRead = step.tool in setOf("files.list", "files.read", "files.csv_summary") ||
                step.tool.startsWith("mcp.") && tool.definition.riskLevel == RiskLevel.SAFE
            val canCorrectRead = safeRead && run.steps.count { it.status == "FAILED" } < 3
            store.updateRun(run.id) {
                it.copy(
                    steps = it.steps.dropLast(1) + step.copy(
                        status = if (result is ToolResult.Success) "SUCCEEDED" else "FAILED",
                        output = output.take(12_000),
                    ),
                    status = if (it.status == "CANCELLED") {
                        "CANCELLED"
                    } else if (result is ToolResult.Success || canCorrectRead) {
                        "QUEUED"
                    } else {
                        "BLOCKED"
                    },
                    note = output.take(12_000),
                )
            }
        } catch (_: StoppedByUser) {
            return false
        } catch (_: WorkPaused) {
            return block(
                run.id,
                "Stopped during a tool action to limit heat. Check its result before retrying; it will not be replayed.",
            )
        } catch (e: TimeoutCancellationException) {
            return block(
                run.id,
                "${step.tool} timed out. It may have completed externally; check before retrying. It will not be replayed.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return block(run.id, "${step.tool} failed: ${e.message}")
        }
        return store.run(run.id)?.status == "QUEUED"
    }

    private fun block(id: String, reason: String): Boolean {
        store.updateRun(id) { if (it.status == "CANCELLED") it else it.copy(status = "BLOCKED", note = reason.take(12_000)) }
        return false
    }

    private fun delegateCompleted(run: BusinessRun, frame: WorkFrame, agentId: String) =
        run.steps.drop(frame.startedAtStep).any { it.agentId == agentId && it.tool == "agent.result" && it.status == "SUCCEEDED" }

    private fun rejectCompletion(run: BusinessRun, agent: BusinessAgent, reason: String): Boolean {
        if (run.steps.count { it.tool == "agent.validation" } >= 3) {
            return block(run.id, "Completion could not be verified after three corrections. $reason")
        }
        return store.transition(run) {
            it.copy(
                status = "QUEUED", note = "Checking the requested outcome before completion",
                steps = it.steps + WorkStep(
                    id = "s${it.steps.size + 1}", agentId = agent.id, tool = "agent.validation",
                    args = emptyMap(), status = "REJECTED", output = reason,
                ),
            )
        }
    }

    private class StoppedByUser : CancellationException("Stopped by user")

    private class WorkPaused : CancellationException("Resource guard paused this job")

    private suspend fun <T> cancellableJob(id: String, action: suspend () -> T): T = coroutineScope {
        val work = async { action() }
        val cancelled = async { store.state.first { snapshot -> snapshot.runs.find { it.id == id }?.status == "CANCELLED" } }
        val thermal = async { while (allowedToWork()) delay(1000) }
        try {
            select {
                work.onAwait { it }
                cancelled.onAwait { throw StoppedByUser() }
                thermal.onAwait { throw WorkPaused() }
            }
        } finally {
            work.cancel(); cancelled.cancel(); thermal.cancel()
        }
    }
}
