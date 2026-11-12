package com.meowmba.shared.ui.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.meowmba.shared.core.flow.FlowKind
import com.meowmba.shared.core.flow.FlowSnapshot

/**
 * Meow's Desk (AppTheme.DESK mode) — a second face on the same FlowSnapshot as Meow Flow
 * (master plan §9, docs/PRO_DESK_AVATAR_PASS_DOWN.md, docs/design/pro-desk-avatar.html).
 * Every object shows real data from the snapshot or from app facts; no fake content.
 */
enum class DeskObject(
    val label: String,
    /** Tap target in scene units. The office is x 0–412; the living room is left of it (negative x), the bedroom right (412–824). */
    val left: Float, val top: Float, val width: Float, val height: Float,
    /** Where the cat walks to (scene x of the cat's left edge). */
    val catX: Float,
) {
    WINDOW("Window: weather", 232f, 60f, 160f, 220f, 250f),
    PROJECT("Corkboard: project", 16f, 172f, 202f, 182f, 150f),
    /** The computer on the old Gateway 2000: opens Computer and Development Studio. */
    BUILD("Computer: Development Studio", 18f, 404f, 184f, 196f, 150f),
    TODAY("Sticky note: next up", 202f, 414f, 66f, 64f, 150f),
    MONEY("Jar: money", 270f, 506f, 68f, 100f, 205f),
    INBOX("Letter tray: messages", 332f, 518f, 78f, 88f, 250f),
    IDEA("Lamp: an idea from Meow", 286f, 404f, 120f, 92f, 190f),
    /** Opens Android's Clock (alarms, timers). */
    CLOCK("Clock: alarms and timers", 34f, 74f, 76f, 76f, 60f),
    /** The desk drawers: folders of apps and Meow tools the user fills. */
    DRAWER_TOP("Top drawer: apps and tools", 30f, 640f, 150f, 42f, 70f),
    DRAWER_BOTTOM("Bottom drawer: apps and tools", 30f, 686f, 150f, 38f, 70f),

    // ---- bedroom (right of the office) ----
    BIG_WINDOW("Big window: weather", 522f, 126f, 220f, 260f, 546f),
    /** The old TV and the console under it open the MAME arcade. */
    ARCADE("Old TV and game console: arcade", 424f, 520f, 128f, 220f, 552f),
    TOWER_BED("Cat tower by the window", 568f, 356f, 66f, 384f, 546f),
    BED("Bed", 682f, 576f, 140f, 164f, 702f),
    FOOD("Food tray", 632f, 692f, 52f, 48f, 562f),
    LITTER("Litter box", 504f, 684f, 60f, 56f, 478f),

    // ---- living room (left of the office) ----
    BOOKSHELF("Bookshelf: audiobooks and the manual", -402f, 420f, 82f, 320f, -382f),
    BIG_TV("Big-screen TV: what's on YouTube", -272f, 336f, 190f, 118f, -242f),
    STEREO("Stereo: what's playing", -150f, 576f, 142f, 164f, -192f),
    COUCH("Couch", -266f, 628f, 116f, 112f, -242f),
    TOWER_LIVING("Cat tower in the living room", -316f, 466f, 44f, 274f, -342f),

    // ---- hanging pictures the user picks (two in the living room, two in the bedroom) ----
    PICTURE_LIVING_A("Picture on the wall", -386f, 116f, 80f, 100f, -380f),
    PICTURE_LIVING_B("Picture on the wall", -176f, 126f, 110f, 84f, -176f),
    PICTURE_BED_A("Picture over the bed", 650f, 448f, 70f, 86f, 630f),
    PICTURE_BED_B("Picture over the bed", 740f, 440f, 70f, 92f, 700f),
    ;

    /** Tap target right now as (left, top, width, height): phone on its side some furniture is bigger. */
    val hit: FloatArray get() = (if (DeskLayout.roomy) ROOMY[this] else null) ?: floatArrayOf(left, top, width, height)

    val isPicture: Boolean get() = name.startsWith("PICTURE_")

    val room: DeskRoom get() = DeskRoom.of(left + width / 2 - 55f)
    /** Objects the cat uses on its own; tapping them sends the cat there, no panel opens. */
    val catOnly: Boolean get() = this in setOf(TOWER_BED, BED, FOOD, LITTER, COUCH, TOWER_LIVING)
}

/** Sideways (landscape) layout: a wider bookshelf, longer bed, much bigger TV and a wider bedroom window. */
object DeskLayout {
    var roomy by androidx.compose.runtime.mutableStateOf(false)
}

private val ROOMY: Map<DeskObject, FloatArray> = mapOf(
    DeskObject.BOOKSHELF to floatArrayOf(-402f, 420f, 118f, 320f),
    DeskObject.BIG_TV to floatArrayOf(-312f, 244f, 270f, 180f),
    DeskObject.TOWER_LIVING to floatArrayOf(-284f, 466f, 44f, 274f),
    DeskObject.BED to floatArrayOf(640f, 576f, 184f, 164f),
    DeskObject.FOOD to floatArrayOf(574f, 692f, 62f, 48f),
    DeskObject.BIG_WINDOW to floatArrayOf(478f, 126f, 318f, 260f),
)

/** The three rooms of Meow's place, left to right. Scene x ranges; the office is the original Desk. */
enum class DeskRoom(val label: String, val left: Float, val right: Float) {
    LIVING("Living room", -412f, 0f), OFFICE("Office", 0f, 412f), BEDROOM("Bedroom", 412f, 824f);

    val center: Float get() = (left + right) / 2

    companion object {
        const val APT_LEFT = -412f
        const val APT_RIGHT = 824f
        const val APT_WIDTH = APT_RIGHT - APT_LEFT
        /** The room the cat is in; [catX] is the cat's left edge (the sprite is 110 wide). */
        fun of(catX: Float): DeskRoom { val c = catX + 55f; return when { c < 0f -> LIVING; c < 412f -> OFFICE; else -> BEDROOM } }
    }
}

/** Something playing on the phone, as shown on the stereo, the TV or the bookshelf. */
data class RoomMedia(val title: String, val subtitle: String? = null, val playing: Boolean = false, val app: String? = null)

/** One Development Studio project, as the desk computer lists it. */
data class StudioLine(val name: String, val status: String)

/** Live things in the rest of the apartment. Empty stays empty: nothing is invented. */
data class RoomState(
    val music: RoomMedia? = null,
    val video: RoomMedia? = null,
    val audiobook: RoomMedia? = null,
    /** False when Android's notification access (which media titles need) is off. */
    val mediaAccess: Boolean = true,
    val studio: List<StudioLine> = emptyList(),
)

/** App facts the Desk shows that don't come from Flow items (project status, build, weather). */
data class DeskFacts(
    val projectTitle: String = "",
    val tallyDone: Int = 0,
    val tallyTotal: Int = 0,
    val projectStatus: String = "",
    val pinnedNote: List<String> = emptyList(),
    val monitorLines: List<MonitorLine> = emptyList(),
    val nextEventTime: String? = null,
    val nextEventWords: List<String> = emptyList(),
    val weatherLine: String? = null,
    val night: Boolean = false,
    val clockHour: Int = 9,
    val clockMinute: Int = 0,
    /** Short handwritten money note, e.g. "$412 tmrw"; derived from a MONEY item when null. */
    val jarNote: String? = null,
    /** An AI suggestion is queued (lamp glows). */
    val ideaQueued: Boolean = false,
)

data class MonitorLine(val text: String, val highlight: Boolean = false)

/** Everything the dynamic layer draws. Equality drives redraws: unchanged state = no redraw. */
data class DeskState(
    val clockHour: Int, val clockMinute: Int,
    val night: Boolean, val weatherLine: String?,
    val projectTitle: String, val tallyDone: Int, val tallyTotal: Int, val projectStatus: String,
    val pinnedNote: List<String>,
    val monitorLines: List<MonitorLine>,
    val sticky: List<String>,
    val trayCount: Int,
    val jarNote: String?,
    val lampOn: Boolean,
    val room: RoomState = RoomState(),
) {
    companion object {
        /** Binds a Flow snapshot + app facts to desk objects (master plan §9.1 table). */
        fun from(snapshot: FlowSnapshot, facts: DeskFacts, open: List<Pair<FlowKind, String>>? = null, room: RoomState = RoomState()): DeskState {
            val items = listOfNotNull(snapshot.main) + snapshot.more
            // Prefer every open intention (the tray counts everyone waiting, not just the 4 shown in Flow).
            val people = open?.count { it.first == FlowKind.PEOPLE } ?: items.count { it.kind == FlowKind.PEOPLE }
            val moneyText = (open?.firstOrNull { it.first == FlowKind.MONEY }?.second)
                ?: items.firstOrNull { it.kind == FlowKind.MONEY }?.headline
            val sticky = facts.nextEventTime?.let { listOf(it) + facts.nextEventWords.take(2) }.orEmpty()
            return DeskState(
                clockHour = facts.clockHour, clockMinute = facts.clockMinute,
                night = facts.night, weatherLine = facts.weatherLine,
                projectTitle = facts.projectTitle, tallyDone = facts.tallyDone.coerceIn(0, facts.tallyTotal.coerceAtLeast(0)),
                tallyTotal = facts.tallyTotal.coerceIn(0, MAX_TALLY), projectStatus = facts.projectStatus,
                pinnedNote = facts.pinnedNote.take(2),
                monitorLines = facts.monitorLines.take(4),
                sticky = sticky,
                trayCount = people,
                jarNote = facts.jarNote ?: moneyText?.let(::shortMoney),
                // With the full list, the caller decides (facts.ideaQueued); otherwise fall back to what Flow shows.
                lampOn = facts.ideaQueued || (open == null && snapshot.interrupt == null && items.any { it.kind == FlowKind.OTHER }),
                room = room,
            )
        }

        const val MAX_TALLY = 14

        /** "Card payment, $412" → "$412"; keeps the note short enough for the jar label. */
        internal fun shortMoney(text: String): String =
            Regex("""\$[\d,]+(\.\d\d)?""").find(text)?.value ?: text.take(10)
    }
}
