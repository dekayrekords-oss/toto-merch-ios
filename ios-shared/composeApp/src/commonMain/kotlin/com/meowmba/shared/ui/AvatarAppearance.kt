package com.meowmba.shared.ui

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.ui.lumen.AvatarPerformance
import com.meowmba.shared.ui.lumen.AvatarAnimal
import com.meowmba.shared.ui.lumen.AvatarColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class AvatarAppearance(
    val animal: AvatarAnimal = AvatarAnimal.NONE,
    val eye: Long = AvatarColors.DEFAULT_EYE,
    val glow: Long = AvatarColors.DEFAULT_GLOW,
    val feature: Long = 0xFFE8D5B9L,
    val scale: Float = 1.05f,
)

internal object AvatarAppearanceStore {
    data class Playback(val performance: AvatarPerformance, val startedAt: Long)
    val playback = MutableStateFlow<Playback?>(null)
    fun play(performance: AvatarPerformance) {
        playback.value = Playback(performance, PlatformTime.currentMillis())
    }
    private val current = MutableStateFlow(AvatarAppearance(
        animal = AvatarAnimal.fromKey(DefaultTextStorage.readText("avatar_animal")),
        eye = AvatarColors.parse(DefaultTextStorage.readText("avatar_eye"), AvatarColors.DEFAULT_EYE),
        glow = AvatarColors.parse(DefaultTextStorage.readText("avatar_glow"), AvatarColors.DEFAULT_GLOW),
        feature = AvatarColors.parse(DefaultTextStorage.readText("avatar_feature"), 0xFFE8D5B9L),
        scale = DefaultTextStorage.readText("avatar_scale")?.toFloatOrNull()?.coerceIn(.5f, 3f) ?: 1.05f,
    ))
    val state: StateFlow<AvatarAppearance> = current

    fun update(value: AvatarAppearance) {
        DefaultTextStorage.writeTextAtomic("avatar_animal", value.animal.key)
        DefaultTextStorage.writeTextAtomic("avatar_eye", AvatarColors.toPref(value.eye))
        DefaultTextStorage.writeTextAtomic("avatar_glow", AvatarColors.toPref(value.glow))
        DefaultTextStorage.writeTextAtomic("avatar_feature", AvatarColors.toPref(value.feature))
        DefaultTextStorage.writeTextAtomic("avatar_scale", value.scale.toString())
        current.value = value
    }
}
