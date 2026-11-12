package com.meowmba.shared.work

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * App-owned task scope, not a model argument. Follows coroutine dispatcher
 * switches.
 *
 * Ported from `core/work/ProjectExecutionContext.kt`. The original is 16 lines
 * and its entire mechanism is two JVM-only APIs: a `ThreadLocal` for storage,
 * and `asContextElement` to make that `ThreadLocal` follow a coroutine across
 * dispatcher switches. Neither exists for Kotlin/Native, so the port does not
 * emulate them — it removes the need for them.
 *
 * ## Why the port is *smaller* than the original
 *
 * The original needs a `ThreadLocal` **plus** a context element because it has
 * to serve two kinds of reader with one value: suspending callers, which hold a
 * `CoroutineContext`, and non-suspending ones, which do not — specifically
 * `Workspace.projectFolder()`. A `ThreadLocal` is the only storage both can
 * reach, and `asContextElement` is the glue that re-installs it on whichever
 * thread the dispatcher picks next.
 *
 * The port has no such constraint. `Workspace`'s project-aware methods are
 * `suspend` anyway, because every caller of them is a tool `execute` body and
 * those are already `suspend` — the original only got away with non-suspending
 * readers because the `ThreadLocal` was ambient. Once every reader can hold the
 * context, the context *is* the storage, and the glue disappears.
 *
 * That is strictly stronger than the original, not a compromise:
 * `currentCoroutineContext()` follows dispatcher switches by definition, on every
 * platform, with no re-installation step that can be skipped or mis-restored.
 * The original's
 * `generatedAssetsStayInTheSubmittedProjectAcrossDispatcherAndUiChanges` — which
 * pins that a generated asset still lands in the *submitted* project after the
 * user switches projects and the tool hops to `Dispatchers.Default` — passes here
 * for the same reason it passes there, but by construction rather than by
 * emulation.
 *
 * ## The JVM-only APIs this removes
 *
 * | API | on Kotlin/Native |
 * |---|---|
 * | `java.lang.ThreadLocal` | absent |
 * | `kotlinx.coroutines.ThreadContextElement` | absent |
 * | `kotlinx.coroutines.CopyableThreadContextElement` | absent |
 * | `ThreadLocal.asContextElement()` | absent |
 *
 * `ThreadContextElement` is the surprising one, and the most expensive to
 * discover: it reads like core coroutines API, and it *is* in the JVM jar, so
 * `compileKotlinJvm` and `compileAndroidMain` both accept a `commonMain` file
 * that uses it — only the two iOS compiles reject it with
 * `Unresolved reference 'ThreadContextElement'`. Unzipping the iOS klib and
 * grepping its `linkdata` settles it: the name occurs three times and **all
 * three are inside KDoc strings**. Kotlin/Native's coroutines has no
 * thread-context-element mechanism at all, not even the `Copyable…` variant
 * that exists for the JS/Native dispatchers.
 *
 * ## A subtlety that is behaviour, not an accident
 *
 * [folder] returns the fallback **only when there is no scope at all**. A scope
 * whose `projectId` is null returns null and never consults the fallback. That is
 * what lets a non-project task run while a project is open: `element(null)` says
 * "this task belongs to no project", which must not be read as "look up
 * whichever project happens to be open".
 */
object ProjectExecutionContext {
    data class Scope(val projectId: Long?, val conversationId: Long?)

    /**
     * The ambient scope, or null when no [element] is installed.
     *
     * A function, where the original had a property: Kotlin has no suspend
     * properties, and the value is only reachable through a `CoroutineContext`.
     */
    suspend fun scope(): Scope? = currentCoroutineContext()[ScopeElement]?.scope

    suspend fun projectId(): Long? = scope()?.projectId

    suspend fun conversationId(): Long? = scope()?.conversationId

    /**
     * A context element carrying [projectId]/[conversationId] for the duration of
     * the coroutine it is installed on.
     *
     * Return type is the interface, not [ScopeElement]: the element is private,
     * and it is also the honest signature — callers pass this to `withContext` /
     * `launch`, which want a `CoroutineContext`, and should not be able to reach
     * the concrete type.
     */
    fun element(projectId: Long?, conversationId: Long? = null): CoroutineContext.Element =
        ScopeElement(Scope(projectId, conversationId))

    suspend fun folder(fallback: () -> String?): String? {
        val scope = scope() ?: return fallback()
        return scope.projectId?.let { "projects/project-$it" }
    }

    /**
     * The element is its own key, so a nested `withContext(element(…))` *replaces*
     * the outer scope rather than accumulating a second one — which is what the
     * original's `ThreadLocal.set` did too. One key per type is also what makes
     * the lookup in [scope] unambiguous.
     */
    private class ScopeElement(val scope: Scope) : AbstractCoroutineContextElement(ScopeElement) {
        companion object Key : CoroutineContext.Key<ScopeElement>
    }
}
