package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.core.TextStorage
import com.meowmba.shared.workflows.BusinessAgent
import com.meowmba.shared.workflows.BusinessRun
import com.meowmba.shared.workflows.WorkDecision
import com.meowmba.shared.workflows.WorkStep
import com.meowmba.shared.workflows.WorkflowBrain
import com.meowmba.shared.workflows.WorkflowEngine
import com.meowmba.shared.workflows.WorkflowModelBrain
import com.meowmba.shared.workflows.WorkflowStore
import com.meowmba.shared.workflows.WorkflowTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The executor: one durable transition per tick, and every decision re-checked
 * against the *current* agent and tool catalog.
 *
 * Ported from the engine-driving half of the original's `BusinessWorkflowTest`:
 * 26 of its 36 tests, which is now all of them. The other ten are accounted for
 * by measurement, not estimate:
 *  - 4 `csv*` tests live in `CsvSummaryTest`, since they exercise `CsvSummary`
 *    directly rather than the engine.
 *  - `traversalAndSymlinkEscapesAreRejected` is a test of the *tools*, so it
 *    lives in `WorkflowToolsTest` (the `../` half) and
 *    `WorkflowToolsSymlinkTest` (the `link/` half, which needs a JVM).
 *  - 5 store-and-catalogue tests (`thermalPause…`, `schedulesDoNotDuplicate…`,
 *    `invalidIntervals…`, `corruptedCheckpoint…`, `voiceCommands…`) live in
 *    `WorkflowTest`, which also carries `BusinessReadinessTest` and
 *    `WorkflowStartersTest`.
 *
 * The registry is the shipped one. The original built it with
 * `WorkflowTools.local(File(temp.root, "workspace"))` and asserted against real
 * files; that factory needs a workspace-scoped filesystem seam (path resolution,
 * symlink checks, depth-limited walking, SHA-256), which did not exist when the
 * rest of this file was ported, so these tests briefly ran against explicit
 * fakes. The seam has since landed, so `fileTools()` is the real factory again
 * and the tests assert on the files it writes — assertions a fake cannot
 * support, because a fake is never refused a path and never leaves a partial
 * file behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowEngineTest {

    // -- helpers ----------------------------------------------------------

    private fun agent(
        id: String = "manager",
        tools: Set<String> = setOf("files.list", "files.read", "files.write", "files.csv_summary"),
    ) = BusinessAgent(id = id, name = id, instructions = "Process orders safely", tools = tools)

    /**
     * The original's `store()` returned a new store over the same *file*, so
     * "reopen and re-read" was implicit in calling it twice. Here the equivalent
     * is constructing a second store over the same storage, so tests that need
     * that round trip must hold the storage.
     */
    private fun store(storage: TextStorage = MemoryTextStorage()) = WorkflowStore(storage)

    private fun brain(decision: WorkDecision) = WorkflowBrain { _, _, _, _, _ -> decision }

    private fun fake(
        id: String,
        risk: RiskLevel = RiskLevel.SAFE,
        confirm: ConfirmationPolicy = ConfirmationPolicy.NONE,
        permissions: Set<String> = emptySet(),
        timeout: Long = 30_000,
        result: () -> ToolResult = { ToolResult.Success("done") },
    ) = object : Tool {
        override val definition = ToolDefinition(
            id, id, id,
            requiredPermissions = permissions,
            riskLevel = risk,
            confirmationPolicy = confirm,
            timeoutMs = timeout,
        )

        override suspend fun execute(args: Map<String, String>) = result()
    }

    /** A fresh sandbox root, for the tests that assert on real files. */
    private fun workspaceRoot() =
        ModelFile("${PlatformTemp.createDirectory("engine-ws")}/workspace").also { it.mkdirs() }

    /**
     * The shipped registry, exactly as the app builds it — including the outbox
     * tools, so a `definitions()` assertion here sees what a model really sees.
     *
     * Each call gets its own sandbox, so no test can observe another's files.
     */
    private fun fileTools() = WorkflowTools.local(workspaceRoot())

    // -- approval gating --------------------------------------------------

    @Test
    fun writeNeverRunsBeforeApprovalAndDenialPersists() = runBlocking {
        val root = workspaceRoot()
        val s = store(); s.saveAgent(agent())
        val id = s.enqueue("manager", "Write draft")
        val engine = WorkflowEngine(
            s, WorkflowTools.local(root),
            brain(WorkDecision("tool", "files.write", mapOf("path" to "draft.txt", "content" to "hello"))),
        )
        engine.tick(id); engine.tick(id)
        assertEquals("APPROVAL", s.run(id)!!.status)
        // The status alone would not prove the write was withheld: the file is the
        // thing that must not exist, and this is the only assertion that says so.
        assertFalse(ModelFile("${root.path}/draft.txt").exists())
        s.approve(id, s.run(id)!!.steps.last().id, false); engine.tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
        assertFalse(ModelFile("${root.path}/draft.txt").exists())
    }

    /**
     * The whole point of the workflow layer, in one run: a manager delegates the
     * arithmetic to an analyst that may only read CSVs, the analyst's exact total
     * becomes the manager's report, the write waits for approval, and the store is
     * reopened after every single transition.
     *
     * Reopening is the part that matters. An in-memory store would pass this even
     * if the durable state were written wrong, so `store(storage)` constructs a
     * second store over the same storage — the port's equivalent of the original
     * re-reading `state.json` — and the engine is rebuilt from it each tick. If a
     * transition did not survive the codec, the run would stall rather than
     * complete.
     */
    @Test
    fun teamDelegatesAnalyzesApprovesWritesAndSurvivesReload() = runBlocking {
        val storage = MemoryTextStorage()
        var store = store(storage)
        val root = workspaceRoot()
        ModelFile("${root.path}/orders.csv")
            .writeBytesAtomic("id,amount,status\nA,10.10,paid\nB,20.20,unpaid\nC,5.05,unpaid\n".encodeToByteArray())
        val analyst = agent("analyst", setOf("files.csv_summary"))
        store.saveAgent(analyst)
        store.saveAgent(agent().copy(delegates = setOf(analyst.id)))
        val id = store.enqueue("manager", "Write an unpaid-order report")

        val brain = WorkflowBrain { agent, _, run, _, _ ->
            val evidence = run.steps.filter { it.status == "SUCCEEDED" }
            when {
                agent.id == "analyst" && evidence.isEmpty() -> WorkDecision(
                    "tool", "files.csv_summary",
                    mapOf(
                        "path" to "orders.csv",
                        "amount_column" to "amount",
                        "filter_column" to "status",
                        "filter_value" to "unpaid",
                    ),
                )
                agent.id == "analyst" ->
                    WorkDecision("finish", summary = evidence.last().output, evidence = listOf(evidence.last().id))
                evidence.isEmpty() ->
                    WorkDecision("delegate", agentId = "analyst", goal = "Calculate unpaid total")
                evidence.none { it.tool == "files.write" } -> WorkDecision(
                    "tool", "files.write",
                    mapOf("path" to "reports/unpaid.txt", "content" to evidence.last().output),
                )
                else -> WorkDecision("finish", summary = "Report saved and verified", evidence = listOf(evidence.last().id))
            }
        }

        repeat(15) {
            val engine = WorkflowEngine(store, WorkflowTools.local(root), brain)
            engine.tick(id)
            // Reopen after every durable transition, not an in-memory fake.
            store = store(storage)
            if (store.run(id)!!.status == "APPROVAL") {
                assertFalse(ModelFile("${root.path}/reports/unpaid.txt").exists())
                store.approve(id, store.run(id)!!.steps.last().id, true)
            }
        }

        assertEquals("COMPLETE", store.run(id)!!.status)
        assertTrue(ModelFile("${root.path}/reports/unpaid.txt").readText()!!.contains("25.25"))
        assertTrue(store.run(id)!!.steps.any { it.output.contains("SHA-256:") })
        assertEquals(1, store.run(id)!!.steps.count { it.tool == "files.write" })
    }

    @Test
    fun permissionRevokedWhileWaitingForApprovalIsEnforced() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Write")
        val e = WorkflowEngine(
            s, fileTools(),
            brain(WorkDecision("tool", "files.write", mapOf("path" to "x", "content" to "x"))),
        )
        e.tick(id); s.approve(id, s.run(id)!!.steps.last().id, true)
        s.saveAgent(agent(tools = emptySet())); e.tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun staleOrExpiredApprovalCannotAuthorizeAnotherAction() = runBlocking {
        val s = store(); s.saveAgent(agent(tools = setOf("test"))); val id = s.enqueue("manager", "Try")
        val e = WorkflowEngine(
            s,
            ToolRegistry().apply { register(fake("test", RiskLevel.DESTRUCTIVE, result = { ToolResult.Success("done") })) },
            brain(WorkDecision("tool", "test")),
        )
        e.tick(id)
        assertTrue(runCatching { s.approve(id, "wrong-id", true) }.isFailure)
        s.updateRun(id) { it.copy(steps = it.steps.map { step -> step.copy(approvalExpiresAt = 1) }) }
        assertTrue(runCatching { s.approve(id, s.run(id)!!.steps.last().id, true) }.isFailure)
        e.tick(id); assertEquals("BLOCKED", s.run(id)!!.status)
    }

    // -- authorization ----------------------------------------------------

    @Test
    fun forgedCompletionHasNoEvidenceAndIsBlocked() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Send an email")
        WorkflowEngine(s, fileTools(), brain(WorkDecision("finish", summary = "Sent!", evidence = listOf("invented")))).tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun unregisteredOrUnpermittedToolCannotRun() = runBlocking {
        for (tool in listOf("files.write", "shell.exec")) {
            val s = store(); s.saveAgent(agent(tools = emptySet())); val id = s.enqueue("manager", "Try")
            WorkflowEngine(s, fileTools(), brain(WorkDecision("tool", tool))).tick(id)
            assertEquals("BLOCKED", s.run(id)!!.status); assertTrue(s.run(id)!!.steps.isEmpty())
        }
    }

    @Test
    fun osPermissionIsCheckedBeforeDispatch() = runBlocking {
        var count = 0; val s = store(); s.saveAgent(agent(tools = setOf("test"))); val id = s.enqueue("manager", "Try")
        val tools = ToolRegistry().apply {
            register(fake("test", permissions = setOf("protected"), result = { count++; ToolResult.Success("done") }))
        }
        WorkflowEngine(s, tools, brain(WorkDecision("tool", "test"))).tick(id)
        assertEquals(0, count); assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun configurationChangedDuringPlanningFailsClosed() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ ->
                s.saveAgent(agent(tools = emptySet())); WorkDecision("tool", "files.list")
            },
        ).tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    // -- cancellation and replay ------------------------------------------

    /**
     * The run is cancelled *while the brain is deciding*, and the brain still
     * returns a tool action. The action must not be recorded.
     *
     * Checked by mutation that this passes for the right reason. Deleting the
     * engine's own `if (store.run(id) != planning) return false` early-out does
     * **not** make this test fail, because `WorkflowStore.transition` is itself
     * compare-and-set and silently refuses the stale write. What this test
     * actually pins is the *store's* guard — which is the real invariant, and
     * the one the other tests in this file rely on. The engine's line is defence
     * in depth that no black-box test can distinguish; do not delete the store's
     * CAS believing this test would catch it.
     */
    @Test
    fun cancellationDuringPlanningCannotResurrectJob() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        val e = WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ ->
                s.cancel(id); WorkDecision("tool", "files.list")
            },
        )
        e.tick(id); assertEquals("CANCELLED", s.run(id)!!.status); assertTrue(s.run(id)!!.steps.isEmpty())
    }

    @Test
    fun cancellationDuringToolKeepsActualResultButStopsNextAction() = runBlocking {
        val s = store(); s.saveAgent(agent(tools = setOf("test"))); val id = s.enqueue("manager", "Try")
        val tools = ToolRegistry().apply {
            register(fake("test", result = { s.cancel(id); ToolResult.Success("Actually ran") }))
        }
        val e = WorkflowEngine(s, tools, brain(WorkDecision("tool", "test")))
        e.tick(id); e.tick(id); e.tick(id)
        assertEquals("CANCELLED", s.run(id)!!.status)
        assertEquals("Actually ran", s.run(id)!!.steps.single().output)
    }

    @Test
    fun interruptedActionIsNeverAutomaticallyReplayed() = runBlocking {
        var calls = 0
        val storage = MemoryTextStorage()
        val s = store(storage); s.saveAgent(agent(tools = setOf("test"))); val id = s.enqueue("manager", "Try")
        s.updateRun(id) {
            it.copy(steps = listOf(WorkStep(agentId = "manager", tool = "test", args = emptyMap(), status = "EXECUTING")))
        }
        val tools = ToolRegistry().apply { register(fake("test", result = { calls++; ToolResult.Success("done") })) }
        WorkflowEngine(store(storage), tools, brain(WorkDecision("finish"))).tick(id)
        assertEquals(0, calls); assertEquals("BLOCKED", store(storage).run(id)!!.status)
    }

    @Test
    fun failureIsNotTreatedAsSuccess() = runBlocking {
        val s = store(); s.saveAgent(agent(tools = setOf("test"))); val id = s.enqueue("manager", "Try")
        val tools = ToolRegistry().apply { register(fake("test", result = { ToolResult.Failure("HTTP 401") })) }
        val e = WorkflowEngine(s, tools, brain(WorkDecision("tool", "test")))
        e.tick(id); e.tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status); assertEquals("FAILED", s.run(id)!!.steps.single().status)
    }

    @Test
    fun failedReadCanBeCorrectedButDoesNotCountAsEvidence() = runBlocking {
        val s = store(); s.saveAgent(agent(tools = setOf("files.read"))); val id = s.enqueue("manager", "Read orders")
        val tools = ToolRegistry().apply { register(fake("files.read", result = { ToolResult.Failure("missing") })) }
        val e = WorkflowEngine(s, tools, brain(WorkDecision("tool", "files.read", mapOf("path" to "missing.csv"))))
        e.tick(id); e.tick(id)
        assertEquals("QUEUED", s.run(id)!!.status)
        assertEquals("FAILED", s.run(id)!!.steps.last().status)
        repeat(6) { e.tick(id) }
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun cancellingWhileModelWaitsStopsInferenceAndLeavesNoNewStep() = runTest {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        var cancelled = false
        val e = WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ ->
                try {
                    delay(80_000); WorkDecision("tool", "files.list")
                } finally {
                    cancelled = true
                }
            },
        )
        val work = launch { e.tick(id) }
        runCurrent()
        s.cancel(id); work.join()
        assertTrue(cancelled); assertTrue(s.run(id)!!.steps.isEmpty())
        assertEquals("CANCELLED", s.run(id)!!.status)
    }

    // -- delegation -------------------------------------------------------

    @Test
    fun delegationMustBeAllowedAndCyclesAreBlocked() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        WorkflowEngine(s, fileTools(), brain(WorkDecision("delegate", agentId = "manager", goal = "cycle"))).tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun disablingParentStopsRunningDelegate() = runBlocking {
        val s = store(); s.saveAgent(agent().copy(delegates = setOf("child"))); s.saveAgent(agent("child"))
        val id = s.enqueue("manager", "Try")
        val e = WorkflowEngine(s, fileTools(), brain(WorkDecision("delegate", agentId = "child", goal = "Try")))
        e.tick(id); s.saveAgent(agent().copy(enabled = false)); e.tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun completedDelegateIsNotRepeatedToBurnThePhoneBattery() = runBlocking {
        val s = store(); s.saveAgent(agent("child")); s.saveAgent(agent().copy(delegates = setOf("child")))
        val id = s.enqueue("manager", "Analyze then report")
        s.updateRun(id) {
            it.copy(steps = listOf(WorkStep("s1", "child", "agent.result", emptyMap(), "SUCCEEDED", "Actual result: 25.25")))
        }
        var offered: List<BusinessAgent>? = null
        val model = WorkflowBrain { _, _, _, _, team ->
            offered = team; WorkDecision("delegate", agentId = "child", goal = "Repeat")
        }
        WorkflowEngine(s, fileTools(), model).tick(id)
        assertTrue(offered!!.isEmpty())
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    // -- resource guards and budgets --------------------------------------

    @Test
    fun heatPauseMakesNoModelOrToolCallsAndCanResume() = runBlocking {
        var cool = false; var modelCalls = 0
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        val e = WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ -> modelCalls++; WorkDecision("tool", "files.list") },
            { cool },
        )
        e.tick(id); assertEquals("PAUSED", s.run(id)!!.status); assertEquals(0, modelCalls)
        cool = true; e.tick(id); assertEquals(1, modelCalls)
    }

    @Test
    fun gettingHotDuringPlanningStopsNativeWorkBeforeAnyTool() = runTest {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        var cool = true
        val e = WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ -> delay(80_000); WorkDecision("tool", "files.list") },
            { cool },
        )
        val work = launch { e.tick(id) }
        runCurrent(); cool = false; work.join()
        assertEquals("PAUSED", s.run(id)!!.status); assertTrue(s.run(id)!!.steps.isEmpty())
    }

    @Test
    fun modelBudgetIsDurable() = runBlocking {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        s.updateRun(id) { it.copy(attempts = 40) }
        WorkflowEngine(s, fileTools(), brain(WorkDecision("tool", "files.list"))).tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    @Test
    fun modelTimeoutBlocksWithoutHangingQueue() = runTest {
        val s = store(); s.saveAgent(agent()); val id = s.enqueue("manager", "Try")
        WorkflowEngine(
            s, fileTools(),
            WorkflowBrain { _, _, _, _, _ -> delay(100_000); WorkDecision("tool", "files.list") },
        ).tick(id)
        assertEquals("BLOCKED", s.run(id)!!.status)
    }

    // -- completion evidence ----------------------------------------------

    @Test
    fun completionRequiresTheConfiguredActionNotJustSomeSuccessfulRead() = runBlocking {
        val s = store(); s.saveAgent(agent().copy(completionTools = setOf("files.write")))
        val id = s.enqueue("manager", "Write a report")
        s.updateRun(id) {
            it.copy(
                steps = listOf(
                    WorkStep(id = "s1", agentId = "manager", tool = "files.read", args = emptyMap(), status = "SUCCEEDED", output = "data"),
                ),
            )
        }
        val e = WorkflowEngine(s, fileTools(), brain(WorkDecision("finish", summary = "Done", evidence = listOf("s1"))))
        repeat(4) { e.tick(id) }
        assertEquals("BLOCKED", s.run(id)!!.status)
        assertEquals(3, s.run(id)!!.steps.count { it.status == "REJECTED" })
        assertTrue(s.run(id)!!.steps.none { it.tool == "agent.result" })
    }

    // -- the brain's parsing and grammar ----------------------------------

    @Test
    fun decisionParserRejectsProseAndMalformedArguments() {
        assertEquals("tool", WorkflowModelBrain.parse("""{"action":"tool","tool":"files.list"}""").action)
        assertTrue(runCatching { WorkflowModelBrain.parse("I have completed everything") }.isFailure)
        assertTrue(runCatching { WorkflowModelBrain.parse("""{"action":"tool","args":{"x":[]}}""") }.isFailure)
    }

    @Test
    fun localGrammarExcludesPrematureFinishAndInventedDelegates() {
        val agent = agent().copy(completionTools = setOf("files.write"))
        val run = BusinessRun(agentId = agent.id, goal = "Write report")
        val schema = Json.parseToJsonElement(
            WorkflowModelBrain.decisionSchema(
                agent, run.frames.first(), run,
                fileTools().definitions(), listOf(agent("real-child-id")),
            ),
        ).jsonObject
        val props = schema["properties"]!!.jsonObject
        assertFalse(props["action"]!!.jsonObject["enum"]!!.jsonArray.toString().contains("finish"))
        assertEquals("""["d1"]""", props["agentId"]!!.jsonObject["enum"]!!.jsonArray.toString())
    }

    @Test
    fun explicitUserFileReferencesConstrainPathsWithoutInventingFiles() {
        val goal = "Analyze imports/alpha-42/orders.csv and write reports/unpaid.txt. Use files.csv_summary."
        assertEquals(listOf("imports/alpha-42/orders.csv", "reports/unpaid.txt"), WorkflowModelBrain.fileReferences(goal))
        val a = agent(tools = setOf("files.csv_summary"))
        val run = BusinessRun(agentId = a.id, goal = goal)
        val schema = Json.parseToJsonElement(
            WorkflowModelBrain.decisionSchema(
                a, run.frames.first(), run,
                fileTools().definitions().filter { it.id in a.tools }, emptyList(),
            ),
        ).jsonObject
        val paths = schema["properties"]!!.jsonObject["args"]!!.jsonObject["properties"]!!
            .jsonObject["path"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(2, paths.size)
        assertFalse(paths.toString().contains("files.csv_summary"))
        assertEquals(
            listOf("imports/monthly orders.csv", "reports/2026.09.summary.txt"),
            WorkflowModelBrain.fileReferences("Read \"imports/monthly orders.csv\" and write reports/2026.09.summary.txt."),
        )
        assertTrue(WorkflowModelBrain.fileReferences("Use https://example.com/file.csv or ../outside.csv").isEmpty())
    }

    @Test
    fun reportFilenameDoesNotRestrictAServicePathParameter() {
        val tool = ToolDefinition("mcp.test.read", "Remote read", "Read a remote path", mapOf("path" to "string"), riskLevel = RiskLevel.SAFE)
        val a = agent(tools = setOf(tool.id, "files.write"))
        val run = BusinessRun(agentId = a.id, goal = "Read the remote project and save reports/summary.txt")
        val schema = Json.parseToJsonElement(
            WorkflowModelBrain.decisionSchema(a, run.frames.first(), run, listOf(tool), emptyList()),
        ).jsonObject
        assertNull(schema["properties"]!!.jsonObject["args"]!!.jsonObject["properties"])
    }
}
