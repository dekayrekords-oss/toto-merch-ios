package com.meowmba.shared.agent.runtime

import com.meowmba.shared.companion.AvatarPerformanceCatalog

/** Only explicit authored scene requests; casual mentions stay in conversation. */
internal object AvatarSceneRequest {
    private val sceneAction = Regex("^(?:chase|follow|watch|inspect|investigate|hide|sneak|curl|snuggle|bat|catch|roll|read|wear|put on|tip|open|turn|stargaze|pose)\\b")
    fun parse(text: String): String? {
        val phrase = AgentRouter.stripAddress(text).lowercase().trim()
            .trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
            .replaceFirst(Regex("^(?:please |(?:can|could|would) you (?:please )?)"), "")
            .removeSuffix(" please")
        val raw = phrase.replaceFirst(Regex("^(?:play|perform|show me) (?:the )?"), "")
            .removeSuffix(" animation").removeSuffix(" scene")
        // A verb alone never grants a match: the entire phrase must still be an
        // authored scene alias below. File names and extra actions stay outside.
        val explicit = raw != phrase || sceneAction.containsMatchIn(phrase)
        if (!explicit) return null
        return sequenceOf(phrase, raw).mapNotNull(AvatarPerformanceCatalog::resolve)
            .firstOrNull { it.category == "Scenes" }?.title
    }
}
