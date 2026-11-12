package com.meowmba.shared.core.flow

import com.meowmba.shared.core.DefaultTextStorage
import kotlinx.coroutines.flow.MutableStateFlow

/** The live context items plus what the person dismissed (kept) or put off (for an hour). */
object FlowContextState {
    val items = MutableStateFlow<List<FlowItem>>(emptyList())
    val dismissed = MutableStateFlow(DefaultTextStorage.readText("flow_ctx_dismissed").orEmpty().lines().filter { it.isNotBlank() }.toSet())
    private val later = mutableMapOf<String, Long>()
    val comments = MutableStateFlow(DefaultTextStorage.readText("flow_ctx_comments").orEmpty().lines()
        .mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap())

    fun comment(id: String, text: String) {
        comments.value = (comments.value + (id to text.replace('\n', ' ').take(1000))).entries.toList().takeLast(200).associate { it.key to it.value }
        DefaultTextStorage.writeTextAtomic("flow_ctx_comments", comments.value.entries.joinToString("\n") { it.key + "\t" + it.value })
    }

    fun snoozed(now: Long): Set<String> = later.filterValues { it > now }.keys

    fun dismiss(id: String) {
        dismissed.value = (dismissed.value + id).toList().takeLast(200).toSet()
        DefaultTextStorage.writeTextAtomic("flow_ctx_dismissed", dismissed.value.joinToString("\n"))
        items.value = items.value.filter { it.intentionId != id }
    }

    fun later(id: String, now: Long) {
        later[id] = now + 3_600_000L
        // An equal list would not re-emit, so the row must leave the list itself; refreshes keep it hidden until then.
        items.value = items.value.filter { it.intentionId != id }
    }
}
