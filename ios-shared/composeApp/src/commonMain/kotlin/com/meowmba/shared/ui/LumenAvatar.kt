package com.meowmba.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import com.meowmba.shared.companion.AvatarPose
import com.meowmba.shared.companion.CustomAnimations
import com.meowmba.shared.companion.AnimFrame
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.ui.lumen.AvatarAnimal
import com.meowmba.shared.ui.lumen.AvatarFormProfiles
import com.meowmba.shared.ui.lumen.LumenFrame
import com.meowmba.shared.ui.lumen.LumenPainter
import com.meowmba.shared.ui.lumen.lumenCenter
import com.meowmba.shared.ui.lumen.lumenUnit
import com.meowmba.shared.ui.lumen.LumenInput
import com.meowmba.shared.ui.lumen.LumenRig
import com.meowmba.shared.ui.lumen.lumenPerformanceInput
import kotlinx.coroutines.delay
import kotlin.math.min

internal enum class AvatarMode(val title: String, val description: String) {
    LUMEN("Lumen", "One expressive companion with animal forms, playful scenes and a changing wardrobe."),
    LUMEN_CAT("Lumen Cat", "Living glass eyes, velvet ears and floating whiskers. The same attentive motion, in a cat."),
    ORB("Jarvis Orb", "A quieter light for the home screen"),
    OFF("Off", "Hide the idle avatar"),
}

/** Shared Android Lumen renderer; the host supplies pose and persistent appearance. */
@Composable
internal fun LumenAvatar(
    pose: AvatarPose,
    modifier: Modifier = Modifier,
    mode: AvatarMode = AvatarMode.LUMEN,
    magnification: Float = 1f,
    onTap: () -> Unit = {},
    thinking: Boolean = false,
    speaking: Boolean = false,
    offline: Boolean = false,
    listening: Boolean = false,
    emotion: com.meowmba.shared.companion.CompanionEmotion = com.meowmba.shared.companion.CompanionEmotion.NEUTRAL,
) {
    if (mode == AvatarMode.ORB) { JarvisOrb(modifier, magnification, onTap, thinking, speaking, offline); return }
    val appearance by AvatarAppearanceStore.state.collectAsState()
    val playback by AvatarAppearanceStore.playback.collectAsState()
    val currentPose by rememberUpdatedState(pose)
    val currentAppearance by rememberUpdatedState(appearance)
    val currentPlayback by rememberUpdatedState(playback)
    val live by rememberUpdatedState(LumenLive(thinking, speaking, offline, listening, emotion))
    val painter = remember { LumenPainter() }
    // Vending Machine: a generated form replaces Lumen's drawn body; a prop rides along (Android AvatarRenderer).
    val worn by com.meowmba.shared.ui.vending.Vending.worn.collectAsState()
    val prop by com.meowmba.shared.ui.vending.Vending.equipped.collectAsState()
    val generatedPainter = remember { com.meowmba.shared.ui.vending.GeneratedAvatarPainter() }
    val clockStart = remember { PlatformTime.currentMillis() }
    val rig = remember { LumenRig() }
    val voice = remember { SpeechEnvelope() }
    var frame by remember { mutableStateOf(LumenFrame()) }
    var progress by remember { mutableStateOf(0f) }
    // Touch: Lumen looks at your finger while it is down and a moment after; a long hold is petting.
    var touch by remember { mutableStateOf<Offset?>(null) }
    var touchUntil by remember { mutableStateOf(0L) }
    var petting by remember { mutableStateOf(false) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    LaunchedEffect(mode) {
        val started = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val t = (now - started) / 1_000_000_000f
                val active = currentPlayback
                val elapsed = active?.let { PlatformTime.currentMillis() - it.startedAt } ?: 0L
                val animation = active?.let { CustomAnimations.sample(it.performance.spec, elapsed) }
                progress = active?.let { elapsed.toFloat() / it.performance.spec.durationMs } ?: 0f
                if (active != null && animation == null) AvatarAppearanceStore.playback.value = null
                val poseNow = currentPose
                val playing = animation != null
                val form = active?.let { AvatarAnimal.performanceFor(it.performance.id) }
                    ?: if (mode == AvatarMode.LUMEN_CAT) AvatarAnimal.CAT else currentAppearance.animal
                val l = live
                val interaction = when {
                    l.offline -> com.meowmba.shared.ui.lumen.InteractionState.OFFLINE
                    l.speaking -> com.meowmba.shared.ui.lumen.InteractionState.SPEAKING
                    l.thinking -> com.meowmba.shared.ui.lumen.InteractionState.THINKING
                    l.listening -> com.meowmba.shared.ui.lumen.InteractionState.LISTENING
                    else -> com.meowmba.shared.ui.lumen.InteractionState.IDLE
                }
                // Gaze priority, as Android: camera face, then a finger, then Lumen's own idle attention.
                val face = com.meowmba.shared.platform.FaceAttention.target.value
                val fingerLive = touch != null || PlatformTime.currentMillis() < touchUntil
                val finger = touch?.takeIf { fingerLive && canvasSize.width > 0f }?.let {
                    Offset(((it.x / canvasSize.width) - .5f) * 2f, ((it.y / canvasSize.height) - .45f) * 2f)
                }
                val gx = face?.let { (it.first - .5f) * 2f } ?: finger?.x
                val gy = face?.let { (it.second - .5f) * 2f } ?: finger?.y
                val input = LumenInput(
                    gazeX = gx?.coerceIn(-1f, 1f), gazeY = gy?.coerceIn(-1f, 1f),
                    cameraEnabled = gx != null,
                    emotion = com.meowmba.shared.ui.lumen.CompanionEmotion.entries.firstOrNull { it.name == l.emotion.name }
                        ?: com.meowmba.shared.ui.lumen.CompanionEmotion.IDLE,
                    interaction = interaction, animal = form,
                    openness = animation?.eyeOpen ?: poseNow.eyeOpen,
                    pupil = animation?.pupilSize ?: .5f,
                    tilt = animation?.headTiltDeg ?: 0f,
                    bounce = animation?.bodyBounce ?: 0f,
                    happy = animation?.happyEyes ?: poseNow.happyEyes,
                    speaking = if (interaction == com.meowmba.shared.ui.lumen.InteractionState.SPEAKING) voice.level(t) else 0f,
                    performing = playing,
                    affection = petting,
                    gazeUp = animation?.pupilGazeUp ?: 0f,
                    earPerk = animation?.earPerk ?: 1f,
                    tailWag = animation?.tailWag ?: if (petting) .8f else 0f,
                    presence = if (poseNow.dimmed) .5f else 1f,
                )
                frame = rig.step(t, lumenPerformanceInput(input, active?.performance, progress))
            }
        }
    }
    Canvas(modifier
        .onSizeChanged { canvasSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(onTap) {
            detectTapGestures(
                onPress = { at ->
                    touch = at; dragEnd = at
                    val released = tryAwaitRelease()
                    touch = null; petting = false
                    touchUntil = PlatformTime.currentMillis() + 1_200
                    released
                },
                onLongPress = { petting = true },
                // A drag is Lumen following your finger (or a swipe), never a tap.
                onTap = { at -> if ((dragEnd - at).getDistance() < viewConfiguration.touchSlop) onTap() },
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    touch = c.position; dragEnd = c.position
                }
            }
        }) {
        when (mode) {
            AvatarMode.LUMEN, AvatarMode.LUMEN_CAT -> {
                val zoom = magnification
                val tSec = (PlatformTime.currentMillis() - clockStart) / 1000f
                val form = worn
                if (form != null) {
                    val placed = com.meowmba.shared.ui.vending.generatedAvatarPlacement(size.width, size.height, frame, zoom)
                    generatedPainter.draw(this, form, frame, tSec, placed.center, placed.unit)
                    prop?.let { generatedPainter.drawProp(this, it, frame, tSec, placed.center, placed.unit) }
                    return@Canvas
                }
                prop?.let { p -> generatedPainter.drawProp(this, p, frame, tSec, lumenCenter(size.width, size.height, frame),
                    lumenUnit(size.width, size.height, frame) * zoom) }
                scale(zoom, zoom, lumenCenter(size.width, size.height, frame)) {
                    painter.draw(this, frame, Color(appearance.eye), Color(appearance.glow),
                        catFeatures = mode == AvatarMode.LUMEN_CAT, featureColor = Color(appearance.feature),
                        performance = playback?.performance,
                        progress = progress,
                        showPrivacyMarks = false)
                }
            }
            AvatarMode.ORB -> {
                val u = min(size.width, size.height)
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Brush.radialGradient(
                    0f to Color(0xFFCCFFF4), .35f to Color(0xFF64CDB7),
                    .7f to Color(0xFF1F807C), 1f to Color(0x00325A5A),
                    center = center, radius = u * .36f), u * .36f, center)
            }
            AvatarMode.OFF -> Unit
        }
    }
}

/**
 * Android's Jarvis Orb (`ParticleOrbAvatar`, from the Meow server's jarvis_mode.js): a floating
 * sphere where every particle is a model parameter. Particles eject with their names while the
 * model is thinking; the caption reads "N PARAMETERS" at rest and "◈ INFERENCE" while answering.
 */
@Composable
private fun JarvisOrb(modifier: Modifier, magnification: Float, onTap: () -> Unit,
                      thinking: Boolean, speaking: Boolean, offline: Boolean) {
    val sim = remember { ParticleOrbSim() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val appearance by AvatarAppearanceStore.state.collectAsState()
    var clock by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock = (it - start) / 1_000_000_000f }
    }
    Canvas(modifier.pointerInput(onTap) { detectTapGestures { onTap() } }) {
        val t = clock
        val r = min(size.width, size.height) * 0.22f
        val center = Offset(size.width / 2f, size.height * 0.45f)
        val talk = if (speaking) (0.35f + 0.25f * kotlin.math.sin(t * 9f)) else 0f
        scale(magnification.coerceIn(.5f, 3f) / 2f * 1.4f, magnification.coerceIn(.5f, 3f) / 2f * 1.4f, center) {
            sim.draw(scope = this, measurer = measurer, center = center, domeRadius = r * 0.816f,
                thinking = thinking, level = talk, clockSec = t, ringColor = Color(appearance.glow),
                alpha = 1f, offline = offline)
        }
    }
}

private data class LumenLive(val thinking: Boolean, val speaking: Boolean, val offline: Boolean,
    val listening: Boolean, val emotion: com.meowmba.shared.companion.CompanionEmotion)

/**
 * A believable mouth level for speech without an audio tap: syllables at ~4–6 Hz, louder stressed
 * beats, short breaths between phrases. Deterministic per session so it never jitters frame to frame.
 */
private class SpeechEnvelope {
    private val phase = kotlin.random.Random.nextFloat() * 10f
    fun level(t: Float): Float {
        val x = t + phase
        val syllable = (kotlin.math.sin(x * 2f * kotlin.math.PI.toFloat() * 4.6f) * .5f + .5f)
        val stress = (kotlin.math.sin(x * 2f * kotlin.math.PI.toFloat() * 1.3f) * .5f + .5f)
        val phrase = kotlin.math.sin(x * 2f * kotlin.math.PI.toFloat() * .23f)
        val breath = if (phrase > .82f) 0.15f else 1f
        return ((.25f + .75f * syllable * syllable) * (.55f + .45f * stress) * breath).coerceIn(0f, 1f)
    }
}
