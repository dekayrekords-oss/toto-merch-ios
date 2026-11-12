package com.meowmba.shared.core.flow

/** Android FlowComments (build 178): local, explicit commands only. Card text is evidence, never an instruction. */
object FlowComments {
    /** A calendar Flow item on iOS: "ctx:event:<startMs>:<title>". */
    data class EventTarget(val startMs: Long, val title: String)

    fun target(intentionId: String): EventTarget? {
        if (!intentionId.startsWith("ctx:event:")) return null
        val rest = intentionId.removePrefix("ctx:event:")
        val start = rest.substringBefore(':').toLongOrNull() ?: return null
        return EventTarget(start, rest.substringAfter(':').ifBlank { return null })
    }

    fun requestsCancellation(raw: String): Boolean {
        val text = raw.lowercase().trim().trimEnd('.', '!').replace(Regex("\\s+"), " ")
            .removePrefix("please ").removeSuffix(" please")
        val subject = "(?:this|it|this (?:event|even|meeting|appointment|occurrence)|the (?:event|meeting|appointment))"
        return Regex("(?:cancel $subject|$subject (?:is |was |has been |got )?cancel(?:ed|led))").matches(text)
    }
}
