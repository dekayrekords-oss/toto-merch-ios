package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.runtime.AgentOrchestrator
import com.meowmba.shared.agent.runtime.AuditSink
import com.meowmba.shared.agent.runtime.SubmitResult
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.agent.tools.Workspace
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.work.ProjectExecutionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project scope that decides where an agent's output lands.
 *
 * Ported from `ProjectExecutionContextTest`. Two of the original's five cases
 * are here; the other three drive the scope through `FileWriteTool` and
 * `AgentOrchestrator.resolveApproval`, and `BuiltinTools.kt` is not ported yet.
 * The ported three are joined by twelve cases for behaviour the original could
 * not pin — see below.
 *
 * ## Why this file is much longer than the original's
 *
 * The original's scope is a `ThreadLocal` plus `asContextElement`, so most of
 * what it guarantees is guaranteed by kotlinx.coroutines on the JVM and is not
 * testable from the outside. The port's scope *is* the coroutine context, which
 * changes what needs pinning: nesting, restore-on-throw, restore-between-tasks
 * and non-interference between concurrent tasks are all now properties of this
 * class's contract rather than of a library's implementation, so each one gets a
 * test. `concurrentTasksInDifferentProjectsEachSeeTheirOwnScope` is the sharpest
 * of them: it is the test that fails if the scope is ever moved back to
 * thread-local storage that is set on the submitting thread.
 *
 * ## The one thing the port is not allowed to lose
 *
 * `generatedAssetsStayInTheSubmittedProjectAcrossDispatcherAndUiChanges` is the
 * original's most important case and it is ported verbatim. It pins a real bug
 * class: the user switches projects while a task is running, the tool hops to
 * another dispatcher, and the asset must still land in the project that was
 * submitted — not the one that happens to be open when the write finally
 * happens. `ProjectExecutionContext` is the mechanism that makes that true, so
 * this file is where it is pinned.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectExecutionContextTest {

    /**
     * A workspace whose root is a real temp directory and whose fallback folder
     * is the mutable [active] — the stand-in for "whichever project the UI has
     * open right now".
     */
    private class Fixture {
        var active: String? = null
        val root: ModelFile = ModelFile(PlatformTemp.createDirectory("pexec"))
        val workspace: Workspace = Workspace(root) { active }

        /** Write straight to disk, bypassing the scope, to set up library files. */
        fun put(path: String, content: String): ModelFile =
            ModelFile("${root.path}/$path").apply {
                parent()?.mkdirs()
                writeBytesAtomic(content.encodeToByteArray())
            }

        fun at(path: String): ModelFile = ModelFile("${root.path}/$path")
    }

    // ── Ported ──────────────────────────────────────────────────────────────

    @Test
    fun generatedAssetsStayInTheSubmittedProjectAcrossDispatcherAndUiChanges() = runTest {
        val f = Fixture()
        f.active = "projects/project-1"
        val asset = withContext(ProjectExecutionContext.element(1, 10)) {
            // The user switches projects mid-task...
            f.active = "projects/project-2"
            // ...and the tool hops to a real dispatcher before writing.
            withContext(Dispatchers.Default) { f.workspace.newAsset("photos", "cat.png") }
        }
        assertTrue(
            asset.path.contains("projects/project-1/photos/"),
            "the asset belongs to the submitted project, not the open one: ${asset.path}",
        )
        assertNull(ProjectExecutionContext.projectId(), "the scope must not outlive the block")
        // With no scope installed the fallback wins, which is the project now open.
        assertTrue(f.workspace.newAsset("photos", "other.png").path.contains("project-2/photos/"))
    }

    @Test
    fun nonProjectTasksDoNotBorrowAnOpenProjectsFolder() = runTest {
        val f = Fixture()
        f.active = "projects/project-9"
        val asset = withContext(ProjectExecutionContext.element(null)) {
            f.workspace.newAsset("documents", "note.txt")
        }
        // A scope with no project must beat the fallback, not fall through to it.
        assertEquals(f.at("documents").canonicalPath(), asset.parent()!!.canonicalPath())
    }

    @Test
    fun scopedWritesCannotEscapeOrOverwriteAnotherProject() = runTest {
        val f = Fixture()
        withContext(ProjectExecutionContext.element(3)) {
            // The legitimate write comes FIRST, and that ordering is load-bearing.
            // `resolve` collapses `..` for its containment check but the write
            // itself uses the raw path, so `projects/project-3/../elsewhere.txt`
            // fails to open while the intermediate directory is missing — for a
            // reason that has nothing to do with the guard. Mutation-tested: run
            // the escape attempt first and deleting `resolveWrite`'s traversal
            // check goes unnoticed. The original's test has the same blind spot.
            assertTrue(f.workspace.write("projects/project-3/note.txt", "good"))
            assertFalse(f.workspace.write("../elsewhere.txt", "bad"))
            assertFalse(f.workspace.write("projects/project-4/note.txt", "bad"))
        }
        assertTrue(f.at("projects/project-3/note.txt").exists())
        assertFalse(f.at("projects/project-4/note.txt").exists())
        assertFalse(f.at("projects/elsewhere.txt").exists(), "the traversal target must not appear")
    }

    // ── The scope's own contract ────────────────────────────────────────────

    @Test
    fun theFallbackAppliesOnlyWhenThereIsNoScopeAtAll() = runTest {
        // No element: the fallback is consulted.
        assertEquals("fallback", ProjectExecutionContext.folder { "fallback" })
        // An element with a project: the scope wins and the fallback is ignored.
        assertEquals(
            "projects/project-3",
            withContext(ProjectExecutionContext.element(3)) {
                ProjectExecutionContext.folder { "fallback" }
            },
        )
        // An element with no project: null, and the fallback is *still* ignored.
        // This is the whole reason `Scope` wraps two nullable ids instead of
        // being a bare `Long?` — "no project" and "no scope" are different.
        assertNull(
            withContext(ProjectExecutionContext.element(null)) {
                ProjectExecutionContext.folder { "fallback" }
            },
        )
    }

    @Test
    fun aNestedScopeReplacesTheOuterOneUntilItEnds() = runTest {
        withContext(ProjectExecutionContext.element(7, 70)) {
            assertEquals(7L, ProjectExecutionContext.projectId())
            withContext(ProjectExecutionContext.element(8, 80)) {
                assertEquals(8L, ProjectExecutionContext.projectId())
                assertEquals(80L, ProjectExecutionContext.conversationId())
            }
            // The inner scope is gone; the outer one is back, not lost.
            assertEquals(7L, ProjectExecutionContext.projectId())
            assertEquals(70L, ProjectExecutionContext.conversationId())
        }
        assertNull(ProjectExecutionContext.projectId())
    }

    @Test
    fun theScopeIsClearedWhenTheScopedBlockThrows() = runTest {
        // `runCatching` rather than `assertFailsWith`: the block is `suspend`, and
        // kotlin.test's `assertFailsWith` takes a non-suspending lambda.
        val thrown = runCatching {
            withContext(ProjectExecutionContext.element(9)) { error("boom") }
        }
        assertIs<IllegalStateException>(thrown.exceptionOrNull(), "the block must have thrown")
        // A scope that survives a failure would silently attach the next task on
        // this dispatcher to a project that has nothing to do with it.
        assertNull(ProjectExecutionContext.projectId(), "a thrown block must not leak its scope")
    }

    @Test
    fun aFinishedTaskDoesNotLeaveItsScopeBehind() = runTest {
        launch(ProjectExecutionContext.element(5, 50)) {
            assertEquals(5L, ProjectExecutionContext.projectId())
        }
        runCurrent()
        launch {
            assertNull(ProjectExecutionContext.projectId(), "the finished task's scope must be gone")
        }
        runCurrent()
    }

    @Test
    fun concurrentTasksInDifferentProjectsEachSeeTheirOwnScope() = runTest {
        val gate = CompletableDeferred<Unit>()
        val jobs = (1L..4L).map { id ->
            async(ProjectExecutionContext.element(id, id * 10)) {
                // Every task is parked before any of them reads, so the reads
                // cannot be ordered by luck.
                gate.await()
                ProjectExecutionContext.projectId() to ProjectExecutionContext.conversationId()
            }
        }
        gate.complete(Unit)
        assertEquals(
            listOf(1L to 10L, 2L to 20L, 3L to 30L, 4L to 40L),
            jobs.map { it.await() },
        )
        assertNull(ProjectExecutionContext.projectId())
    }

    // ── The project-aware half of Workspace ─────────────────────────────────

    @Test
    fun aScopedWriteLandsInsideTheProjectFolder() = runTest {
        val f = Fixture()
        withContext(ProjectExecutionContext.element(3)) {
            assertTrue(f.workspace.write("documents/brief.md", "A real brief"))
        }
        assertEquals("A real brief", f.at("projects/project-3/documents/brief.md").readText())
        // The same relative path at the workspace root must stay untouched.
        assertFalse(f.at("documents/brief.md").exists())
    }

    @Test
    fun aScopedWriteRefusesABackslashPathThatWouldEscapeOnWindows() = runTest {
        val f = Fixture()
        withContext(ProjectExecutionContext.element(3)) {
            // `a\b.txt` is a single filename on POSIX, so `resolve` alone would
            // happily create it — the explicit backslash check is what stops it
            // from becoming a directory traversal on Windows.
            assertFalse(f.workspace.write("a\\b.txt", "bad"))
        }
        assertFalse(f.at("projects/project-3/a\\b.txt").exists())
        assertFalse(f.at("projects/project-3/a").exists())
    }

    @Test
    fun aScopedWriteRefusesAnAbsolutePathInsteadOfMakingItProjectRelative() = runTest {
        val f = Fixture()
        withContext(ProjectExecutionContext.element(3)) {
            // Unscoped, `resolve` deliberately treats a leading '/' as
            // root-relative. A *scoped* write must not do that: the contract is
            // that new outputs are project-relative, and quietly turning
            // `/etc/passwd` into `projects/project-3/etc/passwd` would be a
            // different file in a different place than the caller asked for.
            assertFalse(f.workspace.write("/etc/passwd", "bad"))
            assertFalse(f.workspace.write("../elsewhere.txt", "bad"))
        }
        assertFalse(f.at("projects/project-3/etc/passwd").exists())
        assertFalse(f.at("etc/passwd").exists())
        assertFalse(f.at("../elsewhere.txt").exists())
    }

    @Test
    fun resolveReadPrefersTheProjectCopyOverTheSharedLibrary() = runTest {
        val f = Fixture()
        f.put("documents/note.txt", "shared")
        f.put("projects/project-3/documents/note.txt", "project")
        withContext(ProjectExecutionContext.element(3)) {
            assertEquals("project", f.workspace.read("documents/note.txt"))
        }
        // No scope: the shared library copy is what a plain read sees.
        assertEquals("shared", f.workspace.read("documents/note.txt"))
    }

    @Test
    fun resolveReadFallsBackToTheSharedLibraryWhenTheProjectHasNoCopy() = runTest {
        val f = Fixture()
        f.put("documents/note.txt", "shared")
        withContext(ProjectExecutionContext.element(3)) {
            assertEquals("shared", f.workspace.read("documents/note.txt"))
        }
    }

    @Test
    fun scanFilesIsScopedToTheProjectAndPrefixesItsPaths() = runTest {
        val f = Fixture()
        f.put("notes.txt", "root")
        f.put("projects/project-3/photos/cat.png", "png")
        withContext(ProjectExecutionContext.element(3)) {
            val paths = f.workspace.scanFiles().entries.map { it.path }
            // Paths stay addressable through this workspace's own root-relative
            // names, so the prefix is part of the contract, not decoration.
            assertTrue(paths.contains("projects/project-3/photos/cat.png"), "got $paths")
            assertTrue(paths.all { it.startsWith("projects/project-3/") }, "got $paths")
            assertFalse(paths.contains("notes.txt"), "a root file is not in the project scan")
        }
    }

    @Test
    fun aNonProjectTaskScansTheRootNotTheOpenProject() = runTest {
        val f = Fixture()
        f.active = "projects/project-9"
        f.put("notes.txt", "root")
        f.put("projects/project-9/photos/cat.png", "png")

        // A workspace with no fallback at all: this is what "unscoped" means.
        val unscoped = Workspace(f.root).scanFiles().entries.map { it.path }

        val paths = withContext(ProjectExecutionContext.element(null)) {
            f.workspace.scanFiles().entries.map { it.path }
        }
        // Note the root scan *does* contain "projects/project-9/photos/cat.png" —
        // it walks into the projects tree like any other directory. So the
        // difference is not which file is listed but how the listing is scoped:
        // compare against the genuinely unscoped scan rather than asserting
        // absence, which would pass for the wrong reason.
        assertEquals(unscoped, paths, "an open project must not be borrowed")
        assertTrue(paths.contains("notes.txt"), "a root file is in an unscoped scan: $paths")

        // The fallback *would* have scoped it — that is the difference this pins.
        val viaFallback = f.workspace.scanFiles().entries.map { it.path }
        assertTrue(viaFallback.isNotEmpty(), "the project has a file to find")
        assertTrue(
            viaFallback.all { it.startsWith("projects/project-9/") },
            "the fallback scopes the scan: $viaFallback",
        )
        assertFalse(viaFallback.contains("notes.txt"), "a root file is not in a scoped scan")
    }

    @Test
    fun scanningAProjectWithNoFolderYetDoesNotCreateIt() = runTest {
        val f = Fixture()
        f.put("notes.txt", "root")
        withContext(ProjectExecutionContext.element(3)) {
            assertEquals(emptyList(), f.workspace.scanFiles().entries)
        }
        // `AssetLibrary`'s constructor creates its root. Without the explicit
        // `isDirectory` check, a *read* would silently create the project folder
        // as a side effect — which then shows up in the next unscoped scan.
        assertFalse(
            f.at("projects/project-3").exists(),
            "a scan is a read: it must not create the project folder",
        )
    }

    // ── The orchestrator's half of the wiring ───────────────────────────────

    /**
     * The scope has to survive the trip from `submit` into the tool that
     * eventually runs, which is a different coroutine on a different dispatcher.
     * The original did it by installing the element on the launched coroutine;
     * so does the port, and this is what proves it.
     */
    private class ScopeRecordingTool(private val seen: MutableList<Pair<Long?, Long?>>) : Tool {
        override val definition = ToolDefinition(
            id = "avatar.animate", displayName = "Animate", description = "records the ambient scope",
            riskLevel = RiskLevel.SAFE, confirmationPolicy = ConfirmationPolicy.NONE, timeoutMs = 60_000,
            inputSchema = mapOf("text" to "string"),
        )

        override suspend fun execute(args: Map<String, String>): ToolResult {
            seen += ProjectExecutionContext.projectId() to ProjectExecutionContext.conversationId()
            return ToolResult.Success("ok")
        }
    }

    @Test
    fun aSubmittedTaskCarriesTheAmbientScopeIntoTheTool() = runTest {
        val seen = mutableListOf<Pair<Long?, Long?>>()
        val orchestrator = AgentOrchestrator(
            backgroundScope,
            ToolRegistry().apply { register(ScopeRecordingTool(seen)) },
            object : AuditSink {
                override suspend fun log(action: String, agentId: String, taskId: String?, detail: String) = Unit
            },
        )

        val submitted = withContext(ProjectExecutionContext.element(7, 700)) {
            orchestrator.submit("main", "avatar.animate", mapOf("text" to "wiggle"), "wiggle")
        }
        val started = assertIs<SubmitResult.Started>(submitted)
        runCurrent()

        // The task remembers the project it was submitted under...
        val task = orchestrator.tasks.value.first { it.id == started.taskId }
        assertEquals(7L, task.projectId)
        assertEquals(700L, task.conversationId)
        // ...and the tool that actually ran could see it.
        assertEquals(listOf(7L to 700L), seen.toList())
        assertNull(ProjectExecutionContext.projectId(), "the submitter's scope must not linger")
    }

    @Test
    fun aTaskSubmittedWithNoScopeCarriesNoProject() = runTest {
        val seen = mutableListOf<Pair<Long?, Long?>>()
        val orchestrator = AgentOrchestrator(
            backgroundScope,
            ToolRegistry().apply { register(ScopeRecordingTool(seen)) },
            object : AuditSink {
                override suspend fun log(action: String, agentId: String, taskId: String?, detail: String) = Unit
            },
        )

        val submitted = orchestrator.submit("main", "avatar.animate", mapOf("text" to "wiggle"), "wiggle")
        val started = assertIs<SubmitResult.Started>(submitted)
        runCurrent()

        val task = orchestrator.tasks.value.first { it.id == started.taskId }
        assertNull(task.projectId)
        assertNull(task.conversationId)
        assertEquals(listOf<Pair<Long?, Long?>>(null to null), seen.toList())
    }
}
