package com.meowmba.shared.core.flow

/**
 * Meow Flow data model. Pure Kotlin so the whole decision path is JVM-testable.
 * See docs/MEOW_FLOW_AND_DESK_MASTER_PLAN.md §5 and docs/MEOW_FLOW_PASS_DOWN.md.
 *
 * The central unit is an unfinished [Intention] inferred from what the user says
 * and does. Evidence updates the [Situation]; an [Assessor] judges each open
 * intention; [FlowPolicy] turns judgments + user settings into a [FlowSnapshot].
 */

enum class FlowKind { WORK, MONEY, PEOPLE, HOME, ERRAND, LEISURE, HEALTH, OTHER }

/** Ordered: a higher ordinal is more urgent. The user gate uses USEFUL..URGENT. */
enum class Urgency(val label: String) {
    NOT_NOW("Not now"), USEFUL("Useful"), WORTH_IT("Worth it"), URGENT("Urgent");

    fun atLeast(other: Urgency) = ordinal >= other.ordinal
}

enum class Channel { SPEAK_AND_SHOW, SHOW_ONLY, NOTIFY, SPEAK_ONLY, HOLD }

/** Copy must never present LIKELY or UNKNOWN facts as confirmed (owner decision D12). */
enum class Certainty { CONFIRMED, LIKELY, UNKNOWN }

enum class IntentionStatus { OPEN, SNOOZED, DONE, DROPPED }

enum class Activity { STILL, WALKING, DRIVING, PASSENGER, UNKNOWN }

enum class WorkState { WORKING, WRAPPING, OFF }

/** Where the user's attention is when an interruption fires. */
enum class Surface { FLOW_ON_SCREEN, DESK_ON_SCREEN, OTHER_APP, SCREEN_OFF }

data class Intention(
    val id: String,
    /** Short user-facing sentence, e.g. "Fix the rear brake light". */
    val text: String,
    val kind: FlowKind,
    /** Where it came from, e.g. "said Sun 19:14: 'the rear light is out again'". */
    val source: String,
    val createdAt: Long,
    val dueAt: Long? = null,
    val placeHint: String? = null,
    /** Code-found facts, e.g. part=7443. Values are selected, never generated. */
    val facts: Map<String, String> = emptyMap(),
    val status: IntentionStatus = IntentionStatus.OPEN,
    val snoozedUntil: Long? = null,
    /** People who are waiting on this (names only). */
    val waitingPeople: List<String> = emptyList(),
) {
    fun isOpenAt(now: Long): Boolean = when (status) {
        IntentionStatus.OPEN -> true
        IntentionStatus.SNOOZED -> snoozedUntil != null && now >= snoozedUntil
        else -> false
    }
}

enum class EvidenceType { PLACE, MOVEMENT, APP, NOTIFICATION, CALENDAR, SPEECH, MEDIA, DESKTOP, WEATHER }

/** One observed fact. [key] groups evidence for the dateline (loc, app, cal, mail, need…). */
data class Evidence(
    val type: EvidenceType,
    val at: Long,
    val key: String,
    val label: String,
    val payload: Map<String, String> = emptyMap(),
)

/** One word/phrase in the dateline. [fresh] = newly arrived, drawn in the accent colour. */
data class Signal(val key: String, val label: String, val fresh: Boolean = false)

data class CalendarItem(val title: String, val startAt: Long, val endAt: Long, val withPeople: Boolean)

data class RouteFacts(val destination: String, val etaMinutes: Int, val bufferMinutes: Int)

/**
 * A place the user could act on an intention, computed in code from location/route.
 * Stock is never asserted in v1: [certainty] describes whether the place carries the item.
 */
data class Opportunity(
    val intentionId: String,
    val placeName: String,
    val distanceMeters: Int,
    val detourMinutes: Int,
    val open: Boolean?,
    val side: String? = null,
    val certainty: Certainty = Certainty.LIKELY,
)

data class Situation(
    val now: Long,
    val activity: Activity,
    val place: String? = null,
    val foregroundApp: String? = null,
    val upcoming: List<CalendarItem> = emptyList(),
    val route: RouteFacts? = null,
    val signals: List<Signal> = emptyList(),
    val work: WorkEstimate = WorkEstimate(WorkState.WORKING, 0.5),
    val opportunities: List<Opportunity> = emptyList(),
    val surface: Surface = Surface.FLOW_ON_SCREEN,
)

data class WorkEstimate(
    val state: WorkState,
    val probability: Double,
    val reason: String = "",
    /** OFF because the work day hasn't started yet (morning), not because it ended. Not "evening". */
    val beforeWork: Boolean = false,
)

/** One typed answer from a decision engine (or a plain rule). */
data class Judgment(
    val intentionId: String?,
    val questionId: String,
    val answer: String,
    val probability: Double,
    val engine: String,
)

/** What an assessor concluded about one open intention in the current situation. */
data class Assessment(
    val intentionId: String,
    /** P(acting on this makes sense right now). */
    val relevantNow: Double,
    /** P(the opportunity is gone within ~10 minutes if not acted on). */
    val chancePassing: Double,
    val urgency: Urgency,
    val urgencyConfidence: Double,
    val judgments: List<Judgment> = emptyList(),
)

data class FlowAction(val id: String, val label: String)

data class FlowItem(
    val intentionId: String,
    val kind: FlowKind,
    val headline: String,
    val because: String,
    val primary: FlowAction,
    val secondary: FlowAction?,
    val urgency: Urgency,
    val certainty: Certainty,
    /** Dateline keys this item came from; the UI draws leader lines from them. */
    val sources: List<String>,
    /** Non-null when an interruption was held back, e.g. "Held: …". */
    val heldReason: String? = null,
    val score: Double = 0.0,
)

data class Countdown(val label: String, val startMeters: Int)

data class FlowInterrupt(
    val item: FlowItem,
    val channel: Channel,
    val spokenLine: String?,
    val countdown: Countdown?,
)

data class FlowSnapshot(
    val at: Long,
    val signals: List<Signal>,
    val main: FlowItem?,
    /** At most [FlowPolicy.MAX_MORE] items. */
    val more: List<FlowItem>,
    val interrupt: FlowInterrupt?,
    val evening: Boolean,
    val judgments: List<Judgment>,
) {
    companion object {
        val EMPTY = FlowSnapshot(0L, emptyList(), null, emptyList(), null, evening = false, judgments = emptyList())
    }
}
