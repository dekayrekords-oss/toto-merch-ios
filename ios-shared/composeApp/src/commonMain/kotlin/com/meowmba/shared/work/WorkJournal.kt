package com.meowmba.shared.work

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Android ToolOutcome: what Meow actually knows about an action's result. */
enum class WorkOutcome { RESULT, VERIFIED, HANDOFF, UNCONFIRMED }
enum class WorkStatus { RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class WorkTask(
    val id: String,
    val title: String,
    val kind: String,
    val status: WorkStatus = WorkStatus.RUNNING,
    val outcome: WorkOutcome = WorkOutcome.RESULT,
    val result: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val log: List<String> = emptyList(),
    val executionStarted: Boolean = true,
    val recovered: Boolean = false,
) {
    val isTerminal get() = status != WorkStatus.RUNNING
}

/**
 * Android TaskJournal + TaskHistory (build 182): durable evidence of Meow's work, not a replay
 * queue. Results survive restarts; anything interrupted is marked as such and never re-run.
 */
class WorkJournal(private val storage: TextStorage = DefaultTextStorage, private val clock: () -> Long = PlatformTime::currentMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<WorkTask>> = _tasks

    private fun load(): List<WorkTask> = runCatching { json.decodeFromString<List<WorkTask>>(storage.readText(KEY) ?: "[]") }
        .getOrDefault(emptyList()).map { recover(it, clock()) }

    private fun save(next: List<WorkTask>) {
        _tasks.value = next.sortedByDescending { it.createdAt }.take(100)
        storage.writeTextAtomic(KEY, json.encodeToString(_tasks.value))
    }

    fun start(title: String, kind: String): String {
        val task = WorkTask(PlatformId.shortId(), title.take(400), kind, createdAt = clock(), log = listOf("Started"))
        save(listOf(task) + _tasks.value)
        return task.id
    }

    fun finish(id: String, ok: Boolean, outcome: WorkOutcome, result: String) = update(id) {
        it.copy(status = if (ok) WorkStatus.DONE else WorkStatus.FAILED, outcome = if (ok) outcome else WorkOutcome.RESULT,
            result = result.take(24_000), completedAt = clock(), log = (it.log + if (ok) "Finished" else "Could not finish").takeLast(12))
    }

    fun remove(id: String) = save(_tasks.value.filter { it.id != id })

    private fun update(id: String, change: (WorkTask) -> WorkTask) = save(_tasks.value.map { if (it.id == id) change(it) else it })

    companion object {
        const val KEY = "meow_work.json"

        fun recover(task: WorkTask, now: Long): WorkTask = if (task.isTerminal) task else task.copy(
            recovered = true, status = WorkStatus.FAILED, completedAt = now,
            outcome = if (task.executionStarted) WorkOutcome.UNCONFIRMED else WorkOutcome.RESULT,
            result = if (task.executionStarted)
                "Meow was interrupted while this action was running. Its outcome is unconfirmed. Check the destination before asking again; it has not been repeated."
            else "Meow was interrupted before this action ran. It has not been repeated. Ask again when you are ready.",
            log = (task.log + "Interrupted; no automatic replay").takeLast(12))

        fun label(task: WorkTask): String = when {
            task.status == WorkStatus.CANCELLED -> if (task.executionStarted) "Stopped · check outcome" else "Stopped"
            task.outcome == WorkOutcome.UNCONFIRMED -> "Check outcome"
            task.status == WorkStatus.RUNNING -> "Working"
            task.status == WorkStatus.FAILED -> if (task.recovered && !task.executionStarted) "Not run" else "Could not finish"
            task.outcome == WorkOutcome.HANDOFF -> "Continue in the opened app"
            task.outcome == WorkOutcome.VERIFIED -> "Verified result"
            else -> "Result available"
        }
    }
}
