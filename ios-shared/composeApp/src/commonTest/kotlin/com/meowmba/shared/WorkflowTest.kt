package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.runtime.AgentRouter
import com.meowmba.shared.core.MemoryTextStorage
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.core.TextStorage
import com.meowmba.shared.workflows.BusinessAgent
import com.meowmba.shared.workflows.BusinessMcpConnection
import com.meowmba.shared.workflows.BusinessMcpTool
import com.meowmba.shared.workflows.BusinessReadiness
import com.meowmba.shared.workflows.WorkStep
import com.meowmba.shared.workflows.WorkflowSnapshot
import com.meowmba.shared.workflows.WorkflowStarterCatalog
import com.meowmba.shared.workflows.WorkflowStore
import com.meowmba.shared.workflows.WorkflowThermalGate
import com.meowmba.shared.workflows.WorkflowTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workflow layer's store, thermal gate, readiness inventory and starter
 * catalogue.
 *
 * Ported from the original's `BusinessReadinessTest`, `WorkflowStartersTest`
 * and the store-only half of `BusinessWorkflowTest`. The engine-driving half of
 * `BusinessWorkflowTest` now lives in `WorkflowEngineTest`.
 *
 * One adaptation: the originals used a `TemporaryFolder` + `java.io.File`, but
 * here the store is given a [MemoryTextStorage], so "reopen the file" becomes
 * "construct a second store over the same storage" — the same round trip through
 * the codec, without touching a real filesystem. The *durability* half of the
 * contract (fsync + atomic rename) cannot be exercised through a map, so it is
 * tested against the real JVM actual in `AppStorageAtomicWriteTest`.
 *
 * `thirtyDistinctRecipesOnlyGrantRealTools` cross-checks every starter against
 * `WorkflowTools.local(root).definitions()`. That registry had not been ported
 * when the rest of this file was, so the check was dropped and the test renamed
 * to `thirtyDistinctRecipesAreSelfConsistent` with the omission recorded. The
 * workspace seam has since landed, so the original check and name are back: it
 * is the strongest invariant the catalogue has — a starter granting a tool that
 * no registry provides would look configured and then fail on its first tick —
 * and nothing else in the suite asserts it.
 */
class WorkflowTest {

    // -- helpers ----------------------------------------------------------

    private fun agent(id: String = "reader") = BusinessAgent(
        id = id,
        name = id,
        instructions = "Read assigned inputs",
        tools = setOf("files.read"),
    )

    private fun store(
        storage: TextStorage = MemoryTextStorage(),
        name: String = WorkflowStore.DEFAULT_NAME,
    ) = WorkflowStore(storage, name)

    /** Counts writes so "a no-op change must not touch storage" is assertable. */
    private class CountingStorage : TextStorage {
        private val inner = MemoryTextStorage()
        var writes = 0
            private set

        override fun readText(name: String): String? = inner.readText(name)

        override fun writeText(name: String, content: String) {
            writes++
            inner.writeText(name, content)
        }

        override fun writeTextAtomic(name: String, content: String) {
            writes++
            inner.writeTextAtomic(name, content)
        }

        override fun delete(name: String) = inner.delete(name)
    }

    // -- thermal gate -----------------------------------------------------

    @Test
    fun thermalPauseWaitsForMeaningfulCoolingBeforeReloadingTheModel() {
        val gate = WorkflowThermalGate()
        assertTrue(gate.allowsRun(385, false, false))
        assertFalse(gate.allowsRun(390, false, false))
        assertFalse(gate.allowsRun(389, false, false))
        assertFalse(gate.allowsRun(375, false, false))
        assertTrue(gate.allowsRun(374, false, false))
        assertFalse(gate.allowsRun(360, true, false))
        assertFalse(gate.allowsRun(360, false, true))
        assertTrue(gate.allowsRun(360, false, false))
    }

    // -- readiness inventory ----------------------------------------------

    @Test
    fun unconfiguredServicesAreNeverDisplayedAsAutonomousAccess() {
        val summary = BusinessReadiness.from(WorkflowSnapshot())
        assertEquals(0, summary.configuredServices)
        assertEquals(0, summary.grantedServiceTools)
        assertEquals(0, summary.automaticReads)
        assertEquals(0, summary.configuredSchedules)
    }

    @Test
    fun scheduledReadsRequireEnabledServiceAgentAndSpecificReadGrant() {
        val connection = BusinessMcpConnection(
            id = "mail", name = "Mail", endpoint = "https://example.invalid/mcp",
            tools = listOf(
                BusinessMcpTool("list", "Read mail", "{}", true),
                BusinessMcpTool("send", "Send mail", "{}", false),
            ),
            automaticReadTools = setOf("list", "send"),
        )
        val enabled = agent().copy(
            tools = setOf("mcp.mail.list", "mcp.mail.send", "mcp.missing.read"),
            intervalMinutes = 15,
            scheduledGoal = "Summarize mail",
        )
        val snapshot = WorkflowSnapshot(agents = listOf(enabled), connections = listOf(connection))
        val ready = BusinessReadiness.from(snapshot)
        assertEquals(2, ready.grantedServiceTools)
        assertEquals(1, ready.automaticReads)
        assertEquals(1, ready.configuredSchedules)
        assertEquals(
            0,
            BusinessReadiness.from(snapshot.copy(agents = listOf(enabled.copy(enabled = false)))).automaticReads,
        )
        assertEquals(
            0,
            BusinessReadiness.from(snapshot.copy(connections = listOf(connection.copy(enabled = false)))).grantedServiceTools,
        )
        assertEquals(
            0,
            BusinessReadiness.from(snapshot.copy(connections = listOf(connection.copy(automaticReadTools = emptySet())))).automaticReads,
        )
    }

    // -- store ------------------------------------------------------------

    @Test
    fun repeatedQueueRequestsReuseThePendingJobIncludingAcrossStoreReload() {
        val storage = MemoryTextStorage()
        val store = store(storage)
        store.saveAgent(agent())
        val id = store.enqueue("reader", "Summarize files")
        assertEquals(id, store.enqueue("reader", "  Summarize files  "))
        val restored = store(storage)
        assertEquals(id, restored.enqueue("reader", "Summarize files"))
        assertEquals(1, restored.state.value.runs.size)
        restored.cancel(id)
        assertNotEquals(id, restored.enqueue("reader", "Summarize files"))
    }

    @Test
    fun pausingSchedulesIsDurableAndPreservesPermissionsAndManualJobs() {
        val storage = MemoryTextStorage()
        val store = store(storage)
        val configured = agent().copy(intervalMinutes = 15, scheduledGoal = "Summarize assigned files")
        store.saveAgent(configured)
        store.change { it.copy(schedulesPaused = true) }
        val restored = store(storage)
        restored.scheduleDue(1_000_000)
        assertTrue(restored.state.value.runs.isEmpty())
        assertEquals(configured, restored.agent(configured.id))
        assertTrue(BusinessReadiness.from(restored.state.value).schedulesPaused)
        val manual = restored.enqueue(configured.id, "Read now")
        assertEquals("QUEUED", restored.run(manual)!!.status)
        restored.cancel(manual)
        restored.change { it.copy(schedulesPaused = false) }
        restored.scheduleDue(2_000_000)
        assertEquals(2, restored.state.value.runs.size)
    }

    @Test
    fun olderStoredDataDefaultsToItsExistingScheduleBehavior() {
        val storage = MemoryTextStorage(
            mapOf(WorkflowStore.DEFAULT_NAME to """{"version":1,"agents":[],"runs":[],"connections":[]}"""),
        )
        assertFalse(store(storage).state.value.schedulesPaused)
    }

    @Test
    fun schedulesDoNotDuplicateActiveJobsAndRespectIntervals() {
        val storage = MemoryTextStorage()
        val store = store(storage)
        store.saveAgent(agent().copy(intervalMinutes = 15, scheduledGoal = "Daily report"))
        store.scheduleDue(1_000_000)
        store.scheduleDue(2_000_000)
        assertEquals(1, store.state.value.runs.size)
        store.cancel(store.state.value.runs.single().id)
        store.scheduleDue(1_000_001)
        assertEquals(1, store.state.value.runs.size)
        store.scheduleDue(2_000_000)
        assertEquals(2, store(storage).state.value.runs.size)
    }

    @Test
    fun invalidIntervalsAndCloudWithoutConsentRejected() {
        val store = store()
        for (a in listOf(
            agent().copy(intervalMinutes = 1),
            agent().copy(intervalMinutes = Long.MAX_VALUE),
            agent().copy(provider = "openrouter"),
        )) {
            assertTrue(runCatching { store.saveAgent(a) }.isFailure, "should reject: $a")
        }
    }

    @Test
    fun corruptedCheckpointIsPreservedNotReset() {
        // Fails closed: a half-written or corrupt snapshot must not silently
        // become an empty one, or the first interrupted write would delete
        // someone's saved jobs.
        val storage = MemoryTextStorage(mapOf(WorkflowStore.DEFAULT_NAME to "broken"))
        assertFailsWith<Exception> { store(storage) }
        assertEquals("broken", storage.readText(WorkflowStore.DEFAULT_NAME))
    }

    @Test
    fun newerDataVersionIsRejectedRatherThanCoerced() {
        // The guard above is only reachable from *well-formed* JSON: malformed
        // text already throws inside the decoder, so `corruptedCheckpoint...`
        // would pass with the version check deleted. This is the case the check
        // exists for — a file a future app version wrote. Coercing it would
        // silently drop the fields that version added, and the next save would
        // overwrite the user's jobs with the lossy version.
        val storage = MemoryTextStorage(
            mapOf(WorkflowStore.DEFAULT_NAME to """{"version":2,"agents":[],"runs":[]}"""),
        )
        assertFailsWith<IllegalArgumentException> { store(storage) }
        assertEquals(
            """{"version":2,"agents":[],"runs":[]}""",
            storage.readText(WorkflowStore.DEFAULT_NAME),
        )
    }

    @Test
    fun reloadPreservesRunStatusStepsAndArguments() {
        val storage = MemoryTextStorage()
        val store = store(storage)
        store.saveAgent(agent())
        val id = store.enqueue("reader", "goal")
        store.updateRun(id) {
            it.copy(
                status = "RUNNING",
                steps = listOf(
                    WorkStep(
                        id = "s1", agentId = "reader", tool = "files.read",
                        args = mapOf("path" to "a.csv"), status = "SUCCEEDED", output = "data",
                    ),
                ),
            )
        }
        val reloaded = store(storage)
        val run = reloaded.run(id)!!
        assertEquals("RUNNING", run.status)
        val step = run.steps.single()
        assertEquals("data", step.output)
        assertEquals(mapOf("path" to "a.csv"), step.args)
        assertEquals("files.read", step.tool)
    }

    // -- store: documented guarantees the original never pinned directly ----

    @Test
    fun transitionIsCompareAndSetSoAStaleSnapshotCannotResurrectAJob() {
        val store = store()
        store.saveAgent(agent())
        val id = store.enqueue("reader", "goal")
        val stale = store.run(id)!!
        // The stored run moves on; `stale` is now out of date.
        store.updateRun(id) { it.copy(note = "moved on") }
        assertFalse(
            store.transition(stale) { it.copy(status = "COMPLETE") },
            "a stale expectation must be rejected",
        )
        assertEquals("QUEUED", store.run(id)!!.status)
        // The current snapshot is accepted.
        assertTrue(store.transition(store.run(id)!!) { it.copy(status = "RUNNING") })
        assertEquals("RUNNING", store.run(id)!!.status)
    }

    @Test
    fun removeRunRefusesWhileTheJobIsStillActive() {
        val store = store()
        store.saveAgent(agent())
        val id = store.enqueue("reader", "goal")
        assertFailsWith<IllegalStateException> { store.removeRun(id) }
        store.cancel(id)
        store.removeRun(id)
        assertNull(store.run(id))
    }

    @Test
    fun saveAgentRejectsSelfDelegationOversizedTeamsAndUngrantedCompletionTools() {
        val store = store()
        assertFailsWith<IllegalArgumentException> {
            store.saveAgent(agent("a").copy(delegates = setOf("a")))
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveAgent(agent("a").copy(tools = (1..17).map { "t$it" }.toSet()))
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveAgent(agent("a").copy(delegates = (1..9).map { "d$it" }.toSet()))
        }
        // A completion tool that was never granted would be unusable, so the
        // requirement is a grant superset, not merely a non-empty set.
        assertFailsWith<IllegalArgumentException> {
            store.saveAgent(agent("a").copy(completionTools = setOf("files.write")))
        }
    }

    @Test
    fun aChangeThatChangesNothingDoesNotRewriteStorage() {
        // `change` returns early on an equal snapshot; without that, a tick that
        // re-reads state would fsync the whole history every frame.
        val storage = CountingStorage()
        val store = WorkflowStore(storage, WorkflowStore.DEFAULT_NAME)
        store.saveAgent(agent())
        val before = storage.writes
        store.change { it }
        assertEquals(before, storage.writes, "an identity update must not write")
        store.change { it.copy(schedulesPaused = true) }
        assertEquals(before + 1, storage.writes)
    }

    // -- router -----------------------------------------------------------

    @Test
    fun voiceCommandsReachBusinessToolsWithoutModelGuesswork() {
        assertEquals("business.open", AgentRouter.route("open business agents")?.toolId)
        assertEquals("business.jobs", AgentRouter.route("show business jobs")?.toolId)
        val start = AgentRouter.route("ask business agent Operations manager to summarize orders")!!
        assertEquals("business.start", start.toolId)
        assertEquals("Operations manager", start.args["agent"])
        assertEquals("summarize orders", start.args["goal"])
    }

    @Test
    fun businessModeCommandsOpenTheWorkspaceWithoutStartingJobs() {
        for (phrase in listOf("business mode", "open business mode", "switch to business mode")) {
            val intent = AgentRouter.route(phrase)
            assertEquals("business.open", intent?.toolId, phrase)
            assertTrue(intent!!.args.isEmpty(), phrase)
        }
    }

    // -- starter catalogue ------------------------------------------------

    @Test
    fun thirtyDistinctRecipesOnlyGrantRealTools() {
        val registry = WorkflowTools.local(ModelFile(PlatformTemp.createDirectory("starters")))
            .definitions().map { it.id }.toSet()
        assertEquals(30, WorkflowStarterCatalog.all.size)
        assertEquals(30, WorkflowStarterCatalog.all.map { it.id }.distinct().size)
        val store = store()
        WorkflowStarterCatalog.all.forEach { starter ->
            val agent = starter.agent()
            store.saveAgent(agent)
            assertTrue(registry.containsAll(agent.tools), "${starter.id} grants a tool that does not exist")
            assertTrue(agent.tools.containsAll(agent.completionTools), starter.id)
            assertEquals(0L, agent.intervalMinutes, starter.id)
            assertFalse(agent.cloudConsent, starter.id)
        }
    }

    @Test
    fun executiveTeamDelegationIsBoundedAndStable() {
        val team = WorkflowStarterCatalog.executiveTeam()
        assertEquals(team.map { it.id }, WorkflowStarterCatalog.executiveTeam().map { it.id })
        assertEquals(3, team.last().delegates.size)
        assertEquals(team.dropLast(1).map { it.id }.toSet(), team.last().delegates)
        assertTrue(team.all { it.intervalMinutes == 0L && !it.cloudConsent })
    }
}
