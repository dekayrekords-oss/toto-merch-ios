package com.meowmba.shared.agent

import com.meowmba.shared.core.SpinGuard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Shared resident-engine policy. Native platform providers supply the
 * AutoCloseable engine and keep all JNI/Obj-C calls inside their adapter.
 */
class LocalInferenceRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { 0L },
) {
    private val models = LocalNativeModelPool(clock)

    val isLoaded: Boolean
        get() = models.isLoaded()

    val isGenerating: Boolean
        get() = models.isBusy

    fun idleMs(nowMs: Long = clock()): Long = models.idleMs(nowMs = nowMs)

    suspend fun releaseIdle(minIdleMs: Long = 0): Boolean =
        withContext(dispatcher) { models.releaseIfIdle(minIdleMs = minIdleMs) }

    suspend fun release(): Boolean =
        withContext(dispatcher) { models.releaseWhenIdle() }

    suspend fun <T : AutoCloseable, R> withModel(
        key: String,
        create: () -> T,
        block: suspend (T) -> R,
    ): R = withContext(dispatcher) {
        models.withModel(key, create, block)
    }
}

internal class LocalNativeModelPool(
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private var resident: Resident? = null
    private var busy = false

    private class Resident(
        val key: String,
        val resource: AutoCloseable,
        var lastCompletedAt: Long,
    )

    val isBusy: Boolean
        get() = busy

    fun isLoaded(key: String? = null): Boolean =
        resident?.let { key == null || it.key == key } == true

    fun idleMs(key: String? = null, nowMs: Long = clock()): Long {
        val entry = resident?.takeIf { key == null || it.key == key } ?: return Long.MAX_VALUE
        return if (busy) 0 else (nowMs - entry.lastCompletedAt).coerceAtLeast(0)
    }

    suspend fun <T : AutoCloseable, R> withModel(
        key: String,
        create: () -> T,
        block: suspend (T) -> R,
    ): R = withLocalInferenceCancellation {
        currentCoroutineContext()[LocalInferenceCancellation]?.ensureNotStopped()
        mutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[LocalInferenceCancellation]?.ensureNotStopped()
            busy = true
            if (resident?.key != key) {
                closeResident()
                currentCoroutineContext()[LocalInferenceCancellation]?.ensureNotStopped()
                resident = Resident(key, create(), clock())
            }
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[LocalInferenceCancellation]?.ensureNotStopped()
            @Suppress("UNCHECKED_CAST")
            block(checkNotNull(resident).resource as T)
        } finally {
            resident?.lastCompletedAt = clock()
            busy = false
            mutex.unlock()
        }
    }

    fun releaseIfIdle(key: String? = null, minIdleMs: Long = 0): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            if (!isLoaded(key) || idleMs(key) < minIdleMs) false else closeResident()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun releaseWhenIdle(key: String? = null): Boolean {
        mutex.lock()
        return try {
            if (isLoaded(key)) closeResident() else false
        } finally {
            mutex.unlock()
        }
    }

    private fun closeResident(): Boolean {
        val entry = resident ?: return false
        entry.resource.close()
        resident = null
        return true
    }
}