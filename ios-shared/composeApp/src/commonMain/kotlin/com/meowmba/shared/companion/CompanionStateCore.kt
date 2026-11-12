package com.meowmba.shared.companion

/**
 * Lean, platform-neutral companion state — the shared subset of the Android
 * app's `CompanionState` that drives the avatar animation state machine.
 * Pure Kotlin: no Compose, no Android, no iOS imports.
 */
enum class InteractionState { IDLE, LISTENING, THINKING, SPEAKING, MUTED, OFFLINE }

enum class CompanionEmotion { NEUTRAL, HAPPY, PROUD, CURIOUS, CONFUSED, ALERT, SLEEPY }

enum class DriveReaction { NONE, LEAN_LEFT, LEAN_RIGHT, LURCH, SPEED }

data class CompanionStateCore(
    val interactionState: InteractionState = InteractionState.IDLE,
    val emotion: CompanionEmotion = CompanionEmotion.NEUTRAL,
    val isMicEnabled: Boolean = true,
    val isCameraEnabled: Boolean = true,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
    val driveReaction: DriveReaction = DriveReaction.NONE
)
