package com.meowmba.shared.memory

/** Provider-neutral embedding contract (ported from `core/memory/EmbeddingInterface.kt`). */
interface EmbeddingInterface {
    suspend fun getEmbedding(text: String): FloatArray
}
