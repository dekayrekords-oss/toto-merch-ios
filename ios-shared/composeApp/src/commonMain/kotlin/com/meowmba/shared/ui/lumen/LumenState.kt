package com.meowmba.shared.ui.lumen

enum class InteractionState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    MUTED,
    OFFLINE
}

/**
 * Emotional states affecting the avatar's appearance and animations.
 */
enum class CompanionEmotion {
    IDLE,
    HAPPY,
    CURIOUS,
    ALERT,
    SLEEPY,
    CONFUSED,
    PROUD,
    ATTENTIVE,
    UNDERSTANDING,
    CONCERNED,
    APOLOGETIC,
    SURPRISED,
    PLAYFUL,
    AFFECTIONATE,
    DETERMINED,
    RELIEVED
}


data class ExpressionPlan(val primary: CompanionEmotion, val secondary: CompanionEmotion = CompanionEmotion.ATTENTIVE, val secondaryWeight: Float = .15f, val intensity: Float = .82f, val repairing: Boolean = false)
