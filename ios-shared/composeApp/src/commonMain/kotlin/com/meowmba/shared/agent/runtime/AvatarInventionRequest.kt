package com.meowmba.shared.agent.runtime

import com.meowmba.shared.voice.WakePhraseMatcher

/**
 * Explicit performance requests plus the user's chosen fishing invitations.
 * Ported from `core/agent/runtime/AvatarInventionRequest.kt`, unchanged.
 * Travel planning, species questions, negations and compound actions stay in chat.
 */
object AvatarInventionRequest {
    data class Request(val activity: String, val fresh: Boolean = false)

    fun parse(text: String): Request? {
        val n = WakePhraseMatcher.normalize(text)
        if (n in setOf(
                "lets go fishing", "let's go fishing", "let s go fishing", "let us go fishing",
                "can you fish", "can you go fishing", "go fishing", "show me fishing",
                "pretend to fish", "pretend you are fishing"
            )
        ) return Request("fishing")
        if (n in setOf("make a different fishing animation", "invent a new fishing animation")) {
            return Request("fishing", true)
        }
        val match = Regex("^(?:invent|create|make) (?:me )?(?:a |an )?(?:new |original )?(?:avatar )?animation (?:of|where|about) (.{1,400})$")
            .matchEntire(n) ?: return null
        val activity = match.groupValues[1]
        val self = Regex("^(?:you|yourself|the avatar|your avatar|meow|memba)\\b").containsMatchIn(activity)
        if (!self && !n.contains("avatar animation") && !n.startsWith("invent ")) return null
        if (activity.startsWith("not ") || activity.contains(" and open ") || activity.contains(" and send ")) return null
        return Request(activity, true)
    }
}
