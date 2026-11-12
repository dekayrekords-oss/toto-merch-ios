package com.meowmba.shared.core

/** KMP-safe time and ID primitives (System.currentTimeMillis/UUID are JVM-only). */
expect object PlatformTime {
    fun currentMillis(): Long

    /**
     * The device's current offset from UTC, in minutes (negative west of
     * Greenwich). PromptBuilder's clock line is built from civil-calendar math
     * with an explicit offset — without this the model is told the wrong time.
     */
    fun utcOffsetMinutes(): Int
}

expect object PlatformId {
    /** Short random identifier (task ids etc.), 8 lowercase hex chars. */
    fun shortId(): String

    /**
     * A random RFC 4122 version-4 UUID in canonical lowercase form
     * (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
     *
     * Distinct from [shortId] because it is used somewhere the *shape* is load
     * bearing: `AssetLibrary.trashDir` validates a trash id against
     * `[a-f0-9-]{36}` as a path-traversal guard before using it as a directory
     * name. Widening that regex to accept a shorter id would weaken a security
     * check to avoid a seam member, so the seam provides the real thing.
     */
    fun uuid(): String
}

/** Hardware facts for the LLM screen's System Resources card (Android `SystemUtils.getCapabilities`). */
expect object DeviceInfo {
    fun ramBytes(): Long
    fun cpuCores(): Int
    /** "iOS" / "Android" / the JVM's OS name. */
    fun osName(): String
    /** "27.0", "API 37", … */
    fun osVersion(): String
    fun is64Bit(): Boolean
    /** Dedicated ML accelerator: Apple Neural Engine on iPhone; unknown elsewhere. */
    fun hasNeuralAccelerator(): Boolean
    /** User-visible app version (Android `BuildConfig.VERSION_NAME`; iOS `CFBundleShortVersionString`). */
    fun appVersion(): String
}
