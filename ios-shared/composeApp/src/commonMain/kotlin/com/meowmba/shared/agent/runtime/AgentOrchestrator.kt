package com.meowmba.shared.agent.runtime

import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.work.ProjectExecutionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The companion's agent orchestrator (ported from
 * `core/agent/runtime/AgentOrchestrator.kt`; the JVM-only
 * ConcurrentHashMap/ConcurrentLinkedQueue are replaced by Mutex-guarded
 * collections — behavior unchanged).
 *
 * Owns the full task lifecycle: permission checks, per-agent concurrency
 * limits, approval gates (with auto-deny timeout), execution timeouts,
 * cancellation, queue draining, and audit emission. Agents never run tools
 * they don't own; users always have the final say on risky actions.
 *
 * Pure Kotlin + coroutines: fully testable with a TestScope.
 *
 * ## The task scope, and why there are two `submit` overloads
 *
 * A submitted task carries the project it belongs to, and the original took that
 * from `ProjectExecutionContext` twice: as the default value of `submit`'s
 * `projectId`/`conversationId` parameters, and again when installing the scope on
 * the task's coroutine in [launchTask] so the tools that run later can find it.
 * Both are restored here.
 *
 * The original's two default arguments become two overloads, split on arity.
 * That is forced, not stylistic: **Kotlin rejects a suspend call in a default
 * parameter value** — `Suspend function call in default parameter value is
 * unsupported` — and reading the ambient scope is now a suspend read, because
 * the scope lives in the coroutine context rather than in a `ThreadLocal`. So
 * the four-argument form is the original's omitted-arguments form (it reads the
 * ambient scope) and the six-argument form is the explicit one.
 *
 * The split also preserves the original's one subtlety exactly: an explicitly
 * passed `null` means "this task belongs to no project", which is *not* the same
 * as omitting the argument and inheriting whichever project happens to be open.
 * A single function with a `?:` fallback could not tell those apart.
 *
 * Installing the element on the *launched* coroutine — rather than setting
 * something before the launch and clearing it after — is what makes the scope
 * belong to the task instead of to the thread that submitted it: two tasks in
 * different projects can be in flight at once and each sees its own.
 */

/** A tool call that needs the user's explicit decision before it may run. */
data class ApprovalRequest(
    val id: String = PlatformId.shortId(),
    val taskId: String,
    val toolId: String,
    val title: String,
    val riskLevel: RiskLevel,
    val detail: String
)

/** Where audit events go. Implementations must not throw. */
interface AuditSink {
    suspend fun log(action: String, agentId: String, taskId: String?, detail: String)
}

sealed class SubmitResult {
    data class Started(val taskId: String, val queued: Boolean) : SubmitResult()
    data class Rejected(val reason: String) : SubmitResult()
}

class AgentOrchestrator(
    private val scope: CoroutineScope,
    private val toolRegistry: ToolRegistry,
    private val auditSink: AuditSink,
    // 5 minutes: long enough to auto-deny anything the user didn't answer
    // while the phone was in their pocket — and the task then reports what
    // actually happened ("no answer on the approval card"), not "denied".
    private val approvalTimeoutMs: Long = 300_000
) {
    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    /** Fired when a task reaches a terminal state (for voice feedback). */
    var onTaskCompleted: ((AgentTask) -> Unit)? = null

    private val lock = Mutex()
    private val approvals = LinkedHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val jobs = LinkedHashMap<String, Job>()
    private val approvalQueue = ArrayDeque<ApprovalRequest>()

    // ── Submission ──────────────────────────────────────────────────────────

    /**
     * Submit a task that belongs to the ambient project scope, if there is one.
     *
     * This is the original's `submit(...)` called with `projectId` and
     * `conversationId` omitted — the form every production caller uses. See the
     * class note for why it is an overload rather than two default values.
     */
    suspend fun submit(
        agentId: String,
        toolId: String,
        args: Map<String, String>,
        title: String
    ): SubmitResult = submit(
        agentId, toolId, args, title,
        ProjectExecutionContext.projectId(), ProjectExecutionContext.conversationId()
    )

    fun submit(
        agentId: String,
        toolId: String,
        args: Map<String, String>,
        title: String,
        projectId: Long?,
        conversationId: Long?
    ): SubmitResult {
        val agent = AgentRegistry.byId(agentId)
            ?: return SubmitResult.Rejected("Unknown agent '$agentId'")
        if (!agent.available) {
            return SubmitResult.Rejected("${agent.name} isn't available: ${agent.statusNote ?: "no adapter"}")
        }
        if (toolId !in agent.allowedTools) {
            return SubmitResult.Rejected("${agent.name} isn't allowed to use $toolId")
        }
        val tool = toolRegistry.byId(toolId)
            ?: return SubmitResult.Rejected("Tool '$toolId' is not installed")

        // A desktop goal reserves one slot for its approved child action.
        // A second parent waiting on approval would otherwise occupy that slot.
        val desktopGoals = setOf("desktop.run_goal", "logic.run_goal")
        if (toolId in desktopGoals && _tasks.value.any { it.toolId in desktopGoals && !it.isTerminal }) {
            return SubmitResult.Rejected("Another desktop goal is active. Stop or finish it first.")
        }

        val task = AgentTask(
            agentId = agentId, toolId = toolId, title = title, args = args,
            projectId = projectId, conversationId = conversationId
        )
        _tasks.value = listOf(task) + _tasks.value

        val running = runningCount(agentId)
        return if (running < agent.maxConcurrentTasks) {
            launchTask(task)
            audit("task.submit", agentId, task.id, title)
            SubmitResult.Started(task.id, queued = false)
        } else {
            audit("task.queued", agentId, task.id, "$title (limit ${agent.maxConcurrentTasks})")
            SubmitResult.Started(task.id, queued = true)
        }
    }

    // ── Approval ────────────────────────────────────────────────────────────

    /** User decision on the currently displayed approval request. */
    fun resolveApproval(approved: Boolean) {
        val request = _pendingApproval.value ?: return
        scope.launch {
            lock.withLock {
                approvalQueue.remove(request)
                approvals.remove(request.taskId)?.complete(approved)
            }
            showNextApproval()
        }
    }

    // ── Cancellation ────────────────────────────────────────────────────────

    fun cancel(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.isTerminal) return
        scope.launch {
            lock.withLock {
                approvals.remove(taskId)?.complete(false)
                approvalQueue.removeAll { it.taskId == taskId }
            }
            if (_pendingApproval.value?.taskId == taskId) showNextApproval()
            jobs.remove(taskId)?.cancel()
            updateTask(taskId) {
                it.copy(status = TaskStatus.CANCELLED, completedAt = PlatformTime.currentMillis())
            }
            audit("task.cancelled", task.agentId, taskId, task.title)
            drainQueue(task.agentId)
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun launchTask(task: AgentTask) {
        // Reserve the slot before dispatch: multiple submits can happen before
        // the dispatcher runs the first coroutine (and otherwise exceed the limit).
        updateTask(task.id) {
            it.copy(status = TaskStatus.RUNNING, startedAt = PlatformTime.currentMillis())
        }
        jobs[task.id] = scope.launch(ProjectExecutionContext.element(task.projectId, task.conversationId)) {
            try {
                runTask(task)
            } finally {
                scope.launch { lock.withLock { jobs.remove(task.id) } }
            }
        }
    }

    private suspend fun runTask(task: AgentTask) {
        val tool = toolRegistry.byId(task.toolId) ?: run {
            failTask(task, "Tool '${task.toolId}' disappeared")
            return
        }

        updateTask(task.id) {
            it.copy(status = TaskStatus.RUNNING, startedAt = PlatformTime.currentMillis())
        }
        audit("task.start", task.agentId, task.id, task.title)

        // Approval gate: risky tools wait for the user's explicit decision
        if (tool.definition.confirmationPolicy == ConfirmationPolicy.ALWAYS) {
            updateTask(task.id) { it.copy(status = TaskStatus.WAITING_APPROVAL) }
            val argsSummary = task.args.entries.joinToString("\n") {
                val limit = if (task.toolId in setOf("logic.edit", "logic.act")) 1000
                else if (task.toolId == "project.plan") 8000
                else if (task.toolId == "desktop.control" && it.key in setOf("value", "text")) 8000
                else if (it.key == "goal") 3000 else 160
                "${it.key}=${it.value.take(limit)}${if (it.value.length > limit) " … (truncated; review before approving)" else ""}"
            }
            val request = ApprovalRequest(
                taskId = task.id,
                toolId = task.toolId,
                title = task.title,
                riskLevel = tool.definition.riskLevel,
                detail = (task.projectId?.let {
                    "Project #$it · Files/projects/project-$it\n" +
                        "This approval stays with that project even if another is open.\n\n"
                } ?: "") +
                    "${tool.definition.description}\n$argsSummary"
            )
            val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
            lock.withLock {
                approvals[task.id] = decision
                approvalQueue.add(request)
            }
            if (_pendingApproval.value == null) showNextApproval()
            audit("task.approval_requested", task.agentId, task.id, task.title)

            var timedOut = false
            val approved = withTimeoutOrNull(approvalTimeoutMs) { decision.await() } ?: run {
                timedOut = true
                audit("task.approval_timeout", task.agentId, task.id, "auto-denied")
                false
            }
            lock.withLock { approvals.remove(task.id) }
            // If this request is still the displayed one (timeout path), advance the queue
            if (lock.run { approvalQueue.remove(request) } && _pendingApproval.value?.id == request.id) {
                showNextApproval()
            }

            if (!approved) {
                failTask(
                    task,
                    if (timedOut) {
                        "No answer on the approval card after ${approvalTimeoutMs / 1000}s, " +
                            "so it wasn't run. Ask again to retry."
                    } else {
                        "Denied by user"
                    }
                )
                return
            }
            audit("task.approved", task.agentId, task.id, task.title)
            updateTask(task.id) { it.copy(status = TaskStatus.RUNNING) }
        }

        // Execute with the tool's own timeout.
        //
        // A tool that THROWS rather than returning ToolResult.Failure used to
        // take the whole process with it. Tools are the least trusted code in
        // the app (several parse model-authored arguments), so the boundary
        // belongs here rather than in each implementation. CancellationException
        // is deliberately not caught: swallowing it would break the timeout
        // above and leave tasks un-cancellable.
        val result = try {
            withTimeoutOrNull(tool.definition.timeoutMs) {
                tool.execute(task.args)
            } ?: ToolResult.Failure("Tool timed out after ${tool.definition.timeoutMs}ms")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            runCatching {
                println("AgentOrchestrator: tool ${tool.definition.id} threw: ${e.message}")
            }
            ToolResult.Failure(
                "${tool.definition.displayName} failed: ${e.message ?: e::class.simpleName ?: "unknown error"}"
            )
        }

        when (result) {
            is ToolResult.Success -> completeTask(task, result.output)
            is ToolResult.Failure -> failTask(task, result.error)
        }
    }

    private fun completeTask(task: AgentTask, output: String) {
        val finalTask = updateTask(task.id) {
            it.copy(
                status = TaskStatus.COMPLETED,
                result = output,
                completedAt = PlatformTime.currentMillis()
            )
        }
        audit("task.complete", task.agentId, task.id, output.take(120))
        finalTask?.let { onTaskCompleted?.invoke(it) }
        drainQueue(task.agentId)
    }

    private fun failTask(task: AgentTask, error: String) {
        val finalTask = updateTask(task.id) {
            it.copy(
                status = TaskStatus.FAILED,
                result = error,
                completedAt = PlatformTime.currentMillis()
            )
        }
        audit("task.fail", task.agentId, task.id, error.take(120))
        finalTask?.let { onTaskCompleted?.invoke(it) }
        drainQueue(task.agentId)
    }

    /** Start the oldest queued task when a concurrency slot frees up. */
    private fun drainQueue(agentId: String) {
        val agent = AgentRegistry.byId(agentId) ?: return
        if (runningCount(agentId) >= agent.maxConcurrentTasks) return
        val next = _tasks.value
            .filter { it.agentId == agentId && it.status == TaskStatus.QUEUED }
            .minByOrNull { it.createdAt }   // oldest queued genuinely goes first
            ?: return
        launchTask(next)
    }

    private fun runningCount(agentId: String): Int =
        _tasks.value.count {
            it.agentId == agentId &&
                (it.status == TaskStatus.RUNNING || it.status == TaskStatus.WAITING_APPROVAL)
        }

    private fun showNextApproval() {
        scope.launch {
            lock.withLock { _pendingApproval.value = approvalQueue.firstOrNull() }
        }
    }

    private fun updateTask(id: String, transform: (AgentTask) -> AgentTask): AgentTask? {
        var updated: AgentTask? = null
        _tasks.value = _tasks.value.map { task ->
            if (task.id == id) {
                transform(task).also { updated = it }
            } else {
                task
            }
        }
        return updated
    }

    /** Fire-and-forget audit emission; never blocks or throws into callers. */
    private fun audit(action: String, agentId: String, taskId: String?, detail: String) {
        scope.launch {
            runCatching { auditSink.log(action, agentId, taskId, detail) }
        }
    }
}
