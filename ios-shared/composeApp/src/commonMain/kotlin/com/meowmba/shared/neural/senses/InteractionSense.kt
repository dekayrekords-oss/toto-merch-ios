package com.meowmba.shared.neural.senses

import com.meowmba.shared.neural.SensoryEvent
import com.meowmba.shared.neural.SensoryKind
import com.meowmba.shared.neural.SensorySource

/** The conversation states this sense cares about; mirrors `InteractionState` without depending on the UI package. */
enum class ConversationPhase { IDLE, LISTENING, THINKING, SPEAKING, MUTED, OFFLINE }

/**
 * Turns the conversation loop's own transitions into sensory evidence:
 *
 * - IDLE -> LISTENING: the wake word or a tap started a turn -> [SensoryKind.SPOKEN_TO].
 * - SPEAKING -> LISTENING: the person cut in while the character was talking -> [SensoryKind.BARGE_IN].
 * - task outcomes -> [SensoryKind.TASK_SUCCESS] / [SensoryKind.TASK_FAILURE].
 * - praise, touch and reply affect pass through as the matching impulses.
 *
 * No text is consumed here. What was said belongs to the reasoning layer; the
 * neural layer only learns *that* something happened and how it went.
 */
class InteractionSense {
    private var phase: ConversationPhase = ConversationPhase.IDLE

    fun reset() {
        phase = ConversationPhase.IDLE
    }

    fun current(): ConversationPhase = phase

    fun onPhase(next: ConversationPhase, nowMs: Long): List<SensoryEvent> {
        val prev = phase
        phase = next
        if (prev == next) return emptyList()
        val out = ArrayList<SensoryEvent>(1)
        when {
            prev == ConversationPhase.SPEAKING && next == ConversationPhase.LISTENING ->
                out += SensoryEvent.impulse(SensoryKind.BARGE_IN, 1f, SensorySource.INTERACTION, 1f, nowMs)
            next == ConversationPhase.LISTENING && (prev == ConversationPhase.IDLE || prev == ConversationPhase.MUTED) ->
                out += SensoryEvent.impulse(SensoryKind.SPOKEN_TO, 1f, SensorySource.INTERACTION, 1f, nowMs)
        }
        return out
    }

    fun onTaskOutcome(success: Boolean, nowMs: Long, weight: Float = 1f): SensoryEvent =
        SensoryEvent.impulse(
            if (success) SensoryKind.TASK_SUCCESS else SensoryKind.TASK_FAILURE,
            weight.coerceIn(0f, 1f), SensorySource.INTERACTION, 1f, nowMs,
        )

    fun onPraise(nowMs: Long, weight: Float = 1f): SensoryEvent =
        SensoryEvent.impulse(SensoryKind.PRAISE, weight.coerceIn(0f, 1f), SensorySource.INTERACTION, 1f, nowMs)

    fun onTouch(nowMs: Long, weight: Float = 1f): SensoryEvent =
        SensoryEvent.impulse(SensoryKind.TOUCH, weight.coerceIn(0f, 1f), SensorySource.TOUCH, 1f, nowMs)

    /** Reply or user affect as a signed impulse, -1..1. */
    fun onAffect(valence: Float, nowMs: Long): SensoryEvent? {
        if (!valence.isFinite() || valence == 0f) return null
        return SensoryEvent.impulse(SensoryKind.AFFECT, valence.coerceIn(-1f, 1f), SensorySource.INTERACTION, 1f, nowMs)
    }
}
