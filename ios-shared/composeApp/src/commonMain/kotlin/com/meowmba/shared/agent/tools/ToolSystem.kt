package com.meowmba.shared.agent.tools

/**
 * Open tool and plugin system (ported from `core/agent/tools/ToolSystem.kt`,
 * unchanged in behavior).
 *
 * Every tool declares its identity, inputs, risk level, required permissions,
 * supported environments, confirmation policy, timeout, and audit behavior.
 * The orchestrator enforces all of it; tools themselves never decide policy.
 *
 * Deliberately absent: a shell executor. Per the project's security rules,
 * the companion app does not ship an unrestricted command executor.
 */

/** How dangerous a tool execution can be. Drives the approval UX. */
enum class RiskLevel {
    /** Read-only or fully reversible. No confirmation needed. */
    SAFE,
    /** Writes data in a reversible way (confirm once per task). */
    MODERATE,
    /** Irreversible/destructive. Always requires explicit user approval. */
    DESTRUCTIVE
}

enum class ConfirmationPolicy {
    NONE,
    /** Ask the user before running (per task). */
    ALWAYS
}

enum class ToolEnvironment { PHONE, DESKTOP, SERVER, CLOUD }

/** Static description of a tool, shown to the user before approval. */
data class ToolDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    /** Expected arguments: name -> type ("string", "path", "int"). */
    val inputSchema: Map<String, String> = emptyMap(),
    val requiredPermissions: Set<String> = emptySet(),
    val riskLevel: RiskLevel,
    val environments: Set<ToolEnvironment> = setOf(ToolEnvironment.PHONE),
    val confirmationPolicy: ConfirmationPolicy = ConfirmationPolicy.NONE,
    val timeoutMs: Long = 30_000,
    /** False = this tool's calls are not written to the audit log. */
    val auditLogged: Boolean = true
)

/** Result of one tool execution. Never throws across the boundary. */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
}

/** A runnable tool. */
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(args: Map<String, String>): ToolResult
}

/** Registry of available tools (local now; remote/user-created later). */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.definition.id] = tool
    }

    fun byId(id: String): Tool? = tools[id]

    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition }
}
