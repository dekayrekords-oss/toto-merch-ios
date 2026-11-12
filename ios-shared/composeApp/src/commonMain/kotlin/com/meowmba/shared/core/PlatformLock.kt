package com.meowmba.shared.core

/**
 * A reentrant mutual-exclusion lock, in common code.
 *
 * The Android original reaches for `@Synchronized` (23 files) and `@Volatile`
 * (33 files). Neither exists in common Kotlin — `kotlin.jvm.Synchronized` and
 * `kotlin.jvm.Volatile` are JVM-only, and there is no lock in the common
 * stdlib. Rather than sprinkle `expect`/`actual` across every ported class,
 * the ported code takes this one seam instead.
 *
 * Reentrant, to match `@Synchronized` exactly: a synchronised method that calls
 * another synchronised method on the same instance must not deadlock. The iOS
 * actual is therefore `NSRecursiveLock` and not `NSLock` — `NSLock` is *not*
 * reentrant and would hang on the first nested call.
 *
 * Where the original used `@Volatile` for visibility, ported code should hold
 * this lock for *reads as well as writes*. A lock gives the same
 * happens-before guarantee a volatile field does, and makes the read-modify-
 * write sequences in the original genuinely atomic rather than merely
 * visible.
 */
expect class PlatformLock() {
    /**
     * Run [block] with the lock held, returning its result.
     *
     * Deliberately not suspending: the drive tick and the render loop call into
     * ported classes from non-suspending contexts, and making this `suspend`
     * would force a coroutine boundary into the frame path.
     */
    fun <T> withLock(block: () -> T): T
}
