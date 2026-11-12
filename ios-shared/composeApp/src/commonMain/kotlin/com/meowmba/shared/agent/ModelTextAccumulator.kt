package com.meowmba.shared.agent

/**
 * Accumulates provider deltas without prefix de-duplication.
 *
 * Providers are allowed to repeat text legitimately; treating a repeated
 * suffix as a duplicate can silently corrupt JSON tool decisions and prose.
 */
class ModelTextAccumulator(private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS) {
    private val buffer = StringBuilder()

    init {
        require(maxCharacters >= 0) { "maxCharacters must be non-negative" }
    }

    fun append(delta: String): String {
        check(buffer.length + delta.length <= maxCharacters) {
            "Model response exceeded the output limit"
        }
        buffer.append(delta)
        return buffer.toString()
    }

    fun current(): String = buffer.toString()

    fun clear() {
        buffer.clear()
    }

    val length: Int
        get() = buffer.length

    private companion object {
        const val DEFAULT_MAX_CHARACTERS = 120_000
    }
}