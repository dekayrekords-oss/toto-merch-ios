package com.meowmba.shared.workflows

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformLock
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The workflow persistence layer: agent definitions, job runs and their steps,
 * and the MCP connections a job may use.
 *
 * Ported from `core/workflows/WorkflowStore.kt`. Four substitutions, all
 * mechanical and each with a consequence worth knowing:
 *
 *  - `java.util.UUID.randomUUID()` → [PlatformId.shortId]. The port already made
 *    this swap for `AgentTask.id` (the original truncated its UUID to 8 chars
 *    there anyway), so ids are 8 lowercase hex characters rather than a UUID
 *    string. They stay opaque and unique; nothing parses them.
 *  - `System.currentTimeMillis()` → [PlatformTime.currentMillis].
 *  - `java.io.File` → the [TextStorage] seam. The store now takes a flat storage
 *    *name* instead of a path, which is what `AppStorage` deals in.
 *  - `@Synchronized` → [PlatformLock]. The three annotated methods **nest** —
 *    `enqueue` calls `change`, `transition` calls `updateRun` which calls
 *    `change` — so the lock must be reentrant, which is why the seam is
 *    `ReentrantLock`/`NSRecursiveLock` and not a plain mutex.
 *
 * The durability contract is preserved rather than approximated: writes go
 * through [TextStorage.writeTextAtomic], which stages a temp file, fsyncs, then
 * renames over the target. The original did the same by hand with
 * `FileOutputStream` + `fd.sync()` + `Files.move(ATOMIC_MOVE)`.
 */
@Serializable
data class BusinessAgent(
    val id: String = PlatformId.shortId(),
    val name: String,
    val instructions: String,
    val tools: Set<String>,
    val delegates: Set<String> = emptySet(),
    val provider: String = "local",
    val model: String = "",
    val cloudConsent: Boolean = false,
    val enabled: Boolean = true,
    val intervalMinutes: Long = 0,
    val scheduledGoal: String = "",
    val lastScheduledAt: Long = 0,
    val completionTools: Set<String> = emptySet(),
)

@Serializable
data class WorkFrame(val agentId: String, val goal: String, val startedAtStep: Int = 0)

@Serializable
data class WorkStep(
    val id: String = PlatformId.shortId(),
    val agentId: String,
    val tool: String,
    val args: Map<String, String>,
    val status: String = "PENDING",
    val output: String = "",
    val approvalRequired: Boolean = false,
    val approvalExpiresAt: Long = 0,
)

@Serializable
data class BusinessRun(
    val id: String = PlatformId.shortId(),
    val agentId: String,
    val goal: String,
    val status: String = "QUEUED",
    val frames: List<WorkFrame> = listOf(WorkFrame(agentId, goal)),
    val steps: List<WorkStep> = emptyList(),
    val note: String = "Queued",
    val attempts: Int = 0,
    val createdAt: Long = PlatformTime.currentMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class WorkflowSnapshot(
    val version: Int = 1,
    val agents: List<BusinessAgent> = emptyList(),
    val runs: List<BusinessRun> = emptyList(),
    val connections: List<BusinessMcpConnection> = emptyList(),
    val schedulesPaused: Boolean = false,
)

@Serializable
data class BusinessMcpTool(
    val name: String,
    val description: String,
    val schema: String,
    val readOnlyHint: Boolean = false,
)

@Serializable
data class BusinessMcpConnection(
    val id: String = PlatformId.shortId(),
    val name: String,
    val endpoint: String,
    val tools: List<BusinessMcpTool>,
    val enabled: Boolean = true,
    val verifiedAt: Long = PlatformTime.currentMillis(),
    val automaticReadTools: Set<String> = emptySet(),
)

/**
 * Single repository per process. Writes are fsynced and atomically renamed;
 * a partial checkpoint cannot replace the last known state. Corrupt data fails
 * closed rather than resetting someone's business jobs. Credentials never live
 * here.
 */
class WorkflowStore(
    private val storage: TextStorage = DefaultTextStorage,
    private val name: String = DEFAULT_NAME,
) {
    // Declared first: property initialisers run in declaration order, and
    // `mutable` below must not be able to observe a half-built lock.
    private val lock = PlatformLock()
    private val codec = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    init {
        require(mutable.value.version == 1) { "Unsupported workflow data version" }
    }

    /**
     * Corrupt or unreadable JSON throws rather than silently starting empty —
     * "fails closed". Resetting here would delete someone's saved jobs the first
     * time a write was interrupted, which is exactly the failure the atomic
     * write below exists to prevent.
     */
    private fun load(): WorkflowSnapshot {
        val text = storage.readText(name) ?: return WorkflowSnapshot()
        return codec.decodeFromString(text)
    }

    fun change(update: (WorkflowSnapshot) -> WorkflowSnapshot) = lock.withLock { changeLocked(update) }

    /** Caller must hold [lock]. */
    private fun changeLocked(update: (WorkflowSnapshot) -> WorkflowSnapshot) {
        val next = update(mutable.value)
        if (next == mutable.value) return
        val json = codec.encodeToString(next)
        check(json.encodeToByteArray().size <= MAX_BYTES) {
            "Workflow history is full. Export and remove completed runs."
        }
        storage.writeTextAtomic(name, json)
        mutable.value = next
    }

    fun agent(id: String) = state.value.agents.find { it.id == id }

    fun run(id: String) = state.value.runs.find { it.id == id }

    fun saveAgent(agent: BusinessAgent) {
        require(agent.name.isNotBlank() && agent.name.length <= 80)
        require(agent.instructions.isNotBlank() && agent.instructions.length <= 8000)
        require(agent.provider in setOf("local", "openrouter"))
        require(agent.intervalMinutes == 0L || agent.intervalMinutes in 15..525_600)
        require(agent.scheduledGoal.length <= 8000 && (agent.intervalMinutes == 0L || agent.scheduledGoal.isNotBlank()))
        require(agent.provider != "openrouter" || agent.cloudConsent) { "Approve cloud use before saving this agent" }
        require(agent.id !in agent.delegates)
        require(agent.tools.size <= 16 && agent.delegates.size <= 8) {
            "Use at most 16 tools and 8 delegates per agent; split larger teams into specialists"
        }
        require(agent.tools.containsAll(agent.completionTools)) { "Required completion tools must be granted to this agent" }
        change { it.copy(agents = it.agents.filterNot { old -> old.id == agent.id } + agent) }
    }

    fun enqueue(agentId: String, goal: String): String = lock.withLock { enqueueLocked(agentId, goal) }

    /** Caller must hold [lock]. */
    private fun enqueueLocked(agentId: String, goal: String): String {
        require(goal.isNotBlank() && goal.length <= 8000)
        check(agent(agentId)?.enabled == true) { "Agent is disabled or missing" }
        // Repeated taps or tool callbacks refer to the same unfinished job.
        // Do not queue another run behind its pending approval or slow model.
        state.value.runs.firstOrNull { it.agentId == agentId && it.goal == goal.trim() && it.status !in TERMINAL }
            ?.let { return it.id }
        val run = BusinessRun(agentId = agentId, goal = goal.trim())
        changeLocked { it.copy(runs = it.runs + run) }
        return run.id
    }

    fun updateRun(id: String, update: (BusinessRun) -> BusinessRun) =
        lock.withLock { updateRunLocked(id, update) }

    /** Caller must hold [lock]. */
    private fun updateRunLocked(id: String, update: (BusinessRun) -> BusinessRun) {
        changeLocked { snapshot ->
            snapshot.copy(
                runs = snapshot.runs.map {
                    if (it.id == id) update(it).copy(updatedAt = PlatformTime.currentMillis()) else it
                },
            )
        }
    }

    /** Compare-and-set prevents a late model reply from resurrecting a cancelled job. */
    fun transition(expected: BusinessRun, update: (BusinessRun) -> BusinessRun): Boolean =
        lock.withLock { transitionLocked(expected, update) }

    /** Caller must hold [lock]. */
    private fun transitionLocked(expected: BusinessRun, update: (BusinessRun) -> BusinessRun): Boolean {
        if (run(expected.id) != expected) return false
        updateRunLocked(expected.id, update)
        return true
    }

    fun cancel(id: String) = updateRun(id) {
        if (it.status in TERMINAL) {
            it
        } else {
            it.copy(
                status = "CANCELLED",
                note = "Stopped. An action already in progress may still finish; check its recorded result.",
            )
        }
    }

    fun removeRun(id: String) = change { snapshot ->
        check(snapshot.runs.find { it.id == id }?.status in TERMINAL) { "Stop the job first" }
        snapshot.copy(runs = snapshot.runs.filterNot { it.id == id })
    }

    fun approve(id: String, stepId: String, approved: Boolean) = updateRun(id) { run ->
        check(run.status == "APPROVAL") { "This approval is no longer pending" }
        check(run.steps.lastOrNull()?.id == stepId) { "This step changed; review it again" }
        val expiry = run.steps.last().approvalExpiresAt
        check(!approved || expiry == 0L || PlatformTime.currentMillis() < expiry) {
            "Approval expired; deny this action and start a new job"
        }
        run.copy(
            status = if (approved) "QUEUED" else "BLOCKED",
            note = if (approved) "Approved" else "Action denied",
            steps = run.steps.map {
                if (it.id == stepId) it.copy(status = if (approved) "APPROVED" else "DENIED") else it
            },
        )
    }

    fun scheduleDue(now: Long) = change { snapshot ->
        if (snapshot.schedulesPaused) return@change snapshot
        val additions = mutableListOf<BusinessRun>()
        val agents = snapshot.agents.map { agent ->
            val busy = snapshot.runs.any { it.agentId == agent.id && it.status !in TERMINAL }
            if (agent.enabled && agent.intervalMinutes in 15..525_600 && agent.scheduledGoal.isNotBlank() && !busy &&
                now - agent.lastScheduledAt >= agent.intervalMinutes * 60_000
            ) {
                additions += BusinessRun(agentId = agent.id, goal = agent.scheduledGoal)
                agent.copy(lastScheduledAt = now)
            } else {
                agent
            }
        }
        snapshot.copy(agents = agents, runs = snapshot.runs + additions)
    }

    companion object {
        const val DEFAULT_NAME = "workflows.json"

        /** Mirrors the original's 8 MB ceiling on the encoded snapshot. */
        const val MAX_BYTES = 8_000_000

        val TERMINAL = setOf("COMPLETE", "BLOCKED", "CANCELLED")
    }
}
