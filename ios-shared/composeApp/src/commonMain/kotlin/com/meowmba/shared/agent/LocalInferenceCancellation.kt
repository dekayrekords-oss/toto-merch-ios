package com.meowmba.shared.agent

import com.meowmba.shared.core.SpinGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * One response turn's native-only stop signal. It does not cancel the response
 * Job: remote HTTP work can finish behind the UI's epoch fence without the
 * device-specific network cancellation crash. Only native scopes register here.
 * Create a fresh instance for every turn; stopped instances cannot be reset.
 * Ported from `core/agent/LocalInferenceCancellation.kt` (JVM `synchronized`
 * replaced with [SpinGuard] for KMP).
 */
class LocalInferenceCancellation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalInferenceCancellation>

    private val guard = SpinGuard()
    private val listeners = mutableSetOf<() -> Unit>()

    @Volatile
    var isStopRequested: Boolean = false
        private set

    /** Safe from any thread, idempotent, and never waits for native cleanup. */
    fun requestStop() {
        val callbacks = guard.guard {
            if (isStopRequested) return@guard emptyList<() -> Unit>()
            isStopRequested = true
            listeners.toList().also { listeners.clear() }
        }
        callbacks.forEach { it() }
    }

    internal fun ensureNotStopped() {
        if (isStopRequested) throw stoppedException()
    }

    /** Register an isolated native scope, never its parent response/network Job. */
    internal fun register(scope: CoroutineScope): AutoCloseable {
        val callback: () -> Unit = { scope.cancel(stoppedException()) }
        val callNow = guard.guard {
            if (isStopRequested) true else {
                listeners += callback
                false
            }
        }
        if (callNow) callback()
        return AutoCloseable { guard.guard { listeners -= callback } }
    }

    private fun stoppedException() = CancellationException("Local inference response was superseded")
}

/**
 * The child scope can cancel a queued mutex acquisition without cancelling the
 * caller's Job or siblings. Its children still finish native acknowledgement and
 * cleanup before the engine lease can be returned.
 */
internal suspend fun <T> withLocalInferenceCancellation(block: suspend () -> T): T {
    val signal = currentCoroutineContext()[LocalInferenceCancellation] ?: return block()
    signal.ensureNotStopped()
    return coroutineScope {
        val registration = signal.register(this)
        try {
            signal.ensureNotStopped()
            block()
        } finally {
            registration.close()
        }
    }
}
