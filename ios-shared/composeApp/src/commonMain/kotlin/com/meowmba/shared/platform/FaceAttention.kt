package com.meowmba.shared.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the tracked face is, in display coordinates (0..1, origin top-left).
 * The iOS front-camera publisher already flips X so a person moving left is
 * on the left of this space. Do not flip X again here. The back camera is
 * unpublished on iOS; Android VisionManager flips front-camera X only.
 */
object FaceAttention {
    private val _target = MutableStateFlow<Pair<Float, Float>?>(null)
    val target: StateFlow<Pair<Float, Float>?> = _target
    fun update(x: Float, y: Float) { _target.value = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f) }
    fun clear() { _target.value = null }

    /** Gaze in -1..1 from already display-space X (front camera flipped at source). */
    fun gazeX(): Float? = _target.value?.let { ((it.first - .5f) * 2f).coerceIn(-1f, 1f) }
    fun gazeUp(): Float? = _target.value?.let { ((.5f - it.second) * 1.2f).coerceIn(-.6f, .6f) }
}
