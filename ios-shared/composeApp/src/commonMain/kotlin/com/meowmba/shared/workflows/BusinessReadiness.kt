package com.meowmba.shared.workflows

/**
 * A configuration inventory, not a claim that credentials or a model are live.
 *
 * Ported from `core/workflows/BusinessReadiness.kt` verbatim — it had no
 * imports beyond its own package.
 */
data class BusinessReadiness(
    val enabledAgents: Int,
    val configuredServices: Int,
    val grantedServiceTools: Int,
    val automaticReads: Int,
    val configuredSchedules: Int,
    val schedulesPaused: Boolean,
    val pendingApprovals: Int,
    val activeJobs: Int,
) {
    companion object {
        fun from(snapshot: WorkflowSnapshot): BusinessReadiness {
            val agents = snapshot.agents.filter { it.enabled }
            val grants = agents.flatMap { it.tools }.toSet()
            val connections = snapshot.connections.filter { it.enabled }
            val serviceTools = connections.flatMap { c -> c.tools.map { "mcp.${c.id}.${it.name}" } }.toSet()
            val automatic = connections.flatMap { c ->
                c.tools.filter { it.readOnlyHint && it.name in c.automaticReadTools }
                    .map { "mcp.${c.id}.${it.name}" }
            }.toSet()
            return BusinessReadiness(
                agents.size,
                connections.size,
                serviceTools.intersect(grants).size,
                automatic.intersect(grants).size,
                agents.count { it.intervalMinutes >= 15 && it.scheduledGoal.isNotBlank() },
                snapshot.schedulesPaused,
                snapshot.runs.count { it.status == "APPROVAL" },
                snapshot.runs.count { it.status !in WorkflowStore.TERMINAL },
            )
        }
    }
}
