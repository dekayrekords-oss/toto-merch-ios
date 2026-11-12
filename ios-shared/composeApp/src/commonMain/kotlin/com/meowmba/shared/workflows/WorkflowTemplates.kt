package com.meowmba.shared.workflows

/**
 * The same reusable role definitions are exercised by device acceptance.
 * Paths, filters and amounts come from each job, never a hard-coded fixture.
 *
 * Ported from `core/workflows/WorkflowTemplates.kt` verbatim — it had no
 * imports at all.
 */
object WorkflowTemplates {
    fun operationsTeam(): List<BusinessAgent> {
        val analyst = BusinessAgent(
            name = "Order analyst",
            instructions = "Analyze the order CSV in the user request. Read the file to learn its actual columns and values; do not guess their names. Use files.csv_summary for exact totals and the requested filter. Finish by reporting the actual numeric total and selected row count from that tool's result, with evidence. Do not repeat these instructions as your summary.",
            tools = setOf("files.list", "files.read", "files.csv_summary"),
            completionTools = setOf("files.csv_summary"),
        )
        val manager = BusinessAgent(
            name = "Operations manager",
            instructions = "First delegate the complete order analysis to the analyst, including the user request and file paths. After it returns, use the recorded tool results to write the requested operations report with files.write. Include the actual numeric total and selected row count. Do not delegate twice or repeat role instructions as report content. Finish only after the report was written and verified. Never invent transactions or claim a draft was sent.",
            tools = setOf("files.list", "files.read", "files.write"),
            delegates = setOf(analyst.id), completionTools = setOf("files.write"),
        )
        return listOf(analyst, manager)
    }
}
