package com.meowmba.shared.voice

/**
 * Original Toto merch-helper voice recipes for on-device Kokoro-82M.
 *
 * These are blends of stock Kokoro style vectors, not a clone of any actor
 * or real person. Swap the default by changing [DEFAULT_ID].
 *
 * Licenses: Kokoro-82M is Apache-2.0. The sherpa-onnx int8 pack also ships
 * espeak-ng-data (GPL-3.0) used only for phonemization.
 */
object TotoVoiceConfig {
    const val DEFAULT_ID = "toto_british_baritone_bright"
    const val OFFICIAL_SPEAKER_COUNT = 54

    data class Part(val sourceId: String, val sourceSpeaker: Int, val weight: Float)
    data class Recipe(
        val id: String,
        val name: String,
        val description: String,
        val speed: Float,
        val pitchSemitones: Float,
        val parts: List<Part>,
    ) {
        val speaker: Int get() = OFFICIAL_SPEAKER_COUNT + CANDIDATES.indexOfFirst { it.id == id }
    }

    val CANDIDATES = listOf(
        Recipe(
            "toto_british_baritone_bright",
            "Toto (British baritone, bright)",
            "Original Toto blend · bright British merch host",
            0.95f, 0f,
            listOf(
                Part("bm_lewis", 27, 0.7767f),
                Part("bm_george", 26, 0.0742f),
                Part("bm_daniel", 24, 0.0291f),
                Part("bm_fable", 25, 0.12f),
            ),
        ),
        Recipe(
            "toto_warm_shopkeeper",
            "Toto (warm shopkeeper)",
            "Original Toto blend · warm, clear merch helper",
            1.05f, 0.5f,
            listOf(Part("bm_daniel", 24, 0.42f), Part("am_michael", 16, 0.33f), Part("am_liam", 15, 0.25f)),
        ),
        Recipe(
            "toto_bright_helper",
            "Toto (bright helper)",
            "Original Toto blend · brighter, upbeat merch helper",
            1.08f, 1.0f,
            listOf(Part("am_puck", 18, 0.40f), Part("am_echo", 12, 0.30f), Part("bm_george", 26, 0.30f)),
        ),
        Recipe(
            "toto_steady_host",
            "Toto (steady host)",
            "Original Toto blend · steady, unhurried merch host",
            0.98f, 0f,
            listOf(Part("bm_lewis", 27, 0.45f), Part("am_onyx", 17, 0.30f), Part("am_adam", 11, 0.25f)),
        ),
    )

    fun recipe(id: String?) = CANDIDATES.firstOrNull { it.id == id } ?: CANDIDATES.first()
}

enum class TotoVoiceSource { ON_PHONE, LION, SYSTEM }

/** Merch default is on-phone. Lion is an advanced opt-in, off unless chosen. */
object TotoVoicePreferences {
    private const val SOURCE_KEY = "toto_voice_source"
    private const val VOICE_KEY = "toto_voice_id"
    private val store get() = com.meowmba.shared.core.DefaultTextStorage

    var source: TotoVoiceSource
        get() = when (store.readText(SOURCE_KEY)?.lowercase()) {
            "lion" -> TotoVoiceSource.LION
            "system" -> TotoVoiceSource.SYSTEM
            else -> TotoVoiceSource.ON_PHONE
        }
        set(value) {
            store.writeTextAtomic(
                SOURCE_KEY,
                when (value) {
                    TotoVoiceSource.ON_PHONE -> "on_phone"
                    TotoVoiceSource.LION -> "lion"
                    TotoVoiceSource.SYSTEM -> "system"
                },
            )
        }

    var voiceId: String
        get() = store.readText(VOICE_KEY)?.takeIf { id -> TotoVoiceConfig.CANDIDATES.any { it.id == id } }
            ?: TotoVoiceConfig.DEFAULT_ID
        set(value) { store.writeTextAtomic(VOICE_KEY, TotoVoiceConfig.recipe(value).id) }
}

/**
 * On-device Kokoro render. The iOS host registers [com.meowmba.shared.platform.PlatformDrivers.onDeviceTts]
 * at launch. Render waits for a first-run pack install; callers fall back to system speech only if synthesis fails.
 */
object TotoOnDeviceVoice {
    fun available(): Boolean = com.meowmba.shared.platform.PlatformDrivers.onDeviceTts?.ready == true

    fun sentences(text: String): List<String> {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun render(text: String): ByteArray? {
        val driver = com.meowmba.shared.platform.PlatformDrivers.onDeviceTts ?: return null
        val recipe = TotoVoiceConfig.recipe(TotoVoicePreferences.voiceId)
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            driver.render(text, recipe.id, recipe.speaker, recipe.speed) { wav ->
                if (cont.isActive) cont.resumeWith(Result.success(wav))
            }
        }
    }
}
