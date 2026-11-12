package com.meowmba.shared.ui.desk

import org.jetbrains.compose.resources.decodeToImageBitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.ui.desk.DeskPainter.drawCat
import com.meowmba.shared.ui.desk.DeskPainter.drawObjects
import com.meowmba.shared.ui.desk.DeskPainter.drawRoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * Meow's Desk scene (AppTheme.DESK mode). Draws full screen in Desk mode; the app's
 * existing mic/camera/chat controls stay outside it (no Care control, owner D2).
 *
 * Layers (master plan §9.1, same approach as IDLE_LOAD_138): the room is drawn once into
 * its own graphics layer; objects redraw only when [state] changes; the cat animates at the
 * active rate only during a finite performance (via [AvatarFrameCadence]). Rests sleep between
 * irregular blinks. Pause/background or thermal MODERATE stops decorative motion.
 *
 * Tap an object → eyes glance → cat walks there → [lineFor] is shown in the bubble →
 * [onArrive] (the caller opens the bottom sheet with that object's Flow items).
 */
@Composable
fun DeskHabitat(
    state: DeskState,
    modifier: Modifier = Modifier,
    thermalStatus: Int = DeskThermal.NONE,
    fpsTarget: Int = DeskFrameCadence.DEFAULT_ACTIVE_FPS,
    reduceMotion: Boolean = false,
    interactive: Boolean = true,
    /** Centre of the random idle-rest range; 0 disables idle performances. */
    wanderEveryMs: Long = 9_000L,
    lineFor: (DeskObject) -> String? = { null },
    onArrive: (DeskObject) -> Unit = {},
    /** Called the moment an object is tapped (open its panel right away; the cat still walks over). */
    onTap: (DeskObject) -> Unit = {},
    /** Space kept clear at the top (status bar) and bottom (navigation) of the screen. */
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    activity: DeskCatActivity = DeskCatActivity.IDLE,
    reply: DeskCatReply? = null,
    /**
     * Meow's whole place: living room left, office, bedroom right. Unfolded (wide) screens show all
     * three rooms; the folded cover screen shows one room and follows the cat between them.
     * False keeps the original single office.
     */
    apartment: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val cat = remember { CatMotion() }
    val onArriveNow by rememberUpdatedState(onArrive)
    val lineForNow by rememberUpdatedState(lineFor)
    var request by remember { mutableStateOf<Pair<Int, DeskObject>?>(null) }
    var bubble by remember { mutableStateOf<String?>(null) }
    var dismissedReply by remember { mutableStateOf<String?>(null) }
    val random = remember { Random.Default }
    val stateNow by rememberUpdatedState(state)
    // iOS suspends Compose frames in the background; the scene only runs while it is shown.
    val visible = true
    val animate = !reduceMotion && thermalStatus < DeskThermal.MODERATE
    var petting by remember { mutableIntStateOf(0) }
    /** A room the user asked to look at; the camera shows it until the cat gets there. */
    var lookRoom by remember { mutableStateOf<DeskRoom?>(null) }
    var wideLayout by remember { mutableStateOf(false) }

    // One owner cancels idle work immediately when a question, tap, pause or heat arrives.
    LaunchedEffect(request, activity, reply?.key, animate, visible, fpsTarget, wanderEveryMs, lookRoom) {
        cat.walking = false
        cat.phase = 0f
        cat.blink = 0f
        if ((!visible || !animate) && cat.pose == DeskCatPose.WALK) {
            // A suspended hop must not leave a frozen cat hanging between the desk and floor.
            cat.y = if (cat.y > 590f) 656f else 516f
            cat.pose = DeskCatPose.REST
        }
        if (!visible) return@LaunchedEffect
        suspend fun frames(ms: Long, step: (Float) -> Unit) {
            val cadence = DeskFrameCadence()
            val startNs = withFrameNanos { it }
            while (isActive) {
                val now = withFrameNanos { it }
                if (!cadence.admit(now, active = true, fpsTarget = fpsTarget, thermalStatus = thermalStatus)) continue
                val t = ((now - startNs).toFloat() / (ms * 1_000_000L)).coerceIn(0f, 1f)
                step(t)
                if (t >= 1f) break
                cadence.idleNanosAfterAdmit().takeIf { it > 0 }?.let { delay(it / 1_000_000L) }
            }
        }
        suspend fun leg(spot: DeskCatSpot) {
            val sx = cat.x; val sy = cat.y
            val distance = kotlin.math.hypot(spot.x - sx, spot.y - sy)
            if (distance < 1f) return
            cat.pose = DeskCatPose.WALK; cat.walking = true
            cat.face = DeskCatFace.NEUTRAL
            cat.facingLeft = spot.x < sx
            val ms = (distance * 7).toLong().coerceIn(550, 4_500)
            // A hop between desk/floor/perches, higher for tall climbs; level travel has a walking gait.
            val arc = maxOf(32f, abs(spot.y - sy) * .3f)
            frames(ms) { t ->
                val eased = easeInOut(t)
                cat.x = sx + (spot.x - sx) * eased
                cat.y = sy + (spot.y - sy) * eased - if (abs(spot.y - sy) > 20f) sin(t * PI).toFloat() * arc else 0f
                cat.frame = ((t * ms / 110).toInt() % 2)
                cat.tail = sin(t * PI * 4).toFloat() * 8f
            }
            cat.x = spot.x; cat.y = spot.y; cat.walking = false; cat.frame = 0
        }
        suspend fun moveTo(spot: DeskCatSpot) {
            if (!animate) { cat.x = spot.x; cat.y = spot.y; return }
            // Going far (another room, across the office): hop down, walk the floor, hop up.
            val far = DeskRoom.of(cat.x) != DeskRoom.of(spot.x) || abs(spot.x - cat.x) > 200f
            val floor = DeskCatBehavior.FLOOR
            if (far && cat.y < floor - 20f) leg(DeskCatSpot(cat.x, floor))
            if (far && abs(spot.y - floor) > 20f) leg(DeskCatSpot(spot.x, floor))
            leg(spot)
        }
        suspend fun perform(act: DeskCatAct, arrival: (() -> Unit)? = null) {
            moveTo(act.spot)
            cat.pose = act.pose
            cat.face = act.face ?: DeskCatBehavior.defaultFace(act.pose)
            cat.facingLeft = act.facingLeft ?: (act.pose == DeskCatPose.TYPE || act.pose == DeskCatPose.WRITE)
            arrival?.invoke()
            if (animate) frames(act.durationMs) { t ->
                cat.phase = t * (2 * PI).toFloat() * 3
                cat.tail = sin(cat.phase * .5f) * 8f
                cat.blink = if (t in .44f.. .48f) 1f else 0f
            }
        }
        suspend fun rest(ms: Long) {
            var remaining = ms
            while (remaining > 0 && isActive) {
                val wait = minOf(remaining, random.nextLong(2_700, 6_300))
                delay(wait); remaining -= wait
                if (!animate || cat.pose == DeskCatPose.SLEEP) continue
                // Between blinks the face drifts through small moods: a yawn, a slow blink, a blep.
                when (random.nextInt(10)) {
                    0 -> { cat.face = DeskCatFace.YAWN; delay(1_300); cat.face = DeskCatFace.NEUTRAL }
                    1 -> cat.face = DeskCatFace.SLEEPY
                    2 -> { cat.face = DeskCatFace.PLAYFUL; delay(1_600); cat.face = DeskCatFace.NEUTRAL }
                    3 -> cat.face = DeskCatFace.PURR
                    4 -> cat.face = DeskCatFace.CURIOUS
                    else -> { if (cat.face != DeskCatFace.NEUTRAL && random.nextBoolean()) cat.face = DeskCatFace.NEUTRAL
                        cat.blink = 1f; delay(140); cat.blink = 0f }
                }
            }
        }
        try {
            when (activity) {
                DeskCatActivity.THINKING -> {
                    // A visual performance accompanies actual inference; it does not run tools.
                    perform(DeskCatAct(DeskCatBehavior.computer, DeskCatPose.TYPE, 3_600, DeskCatFace.FOCUSED))
                    while (animate && isActive) {
                        rest(random.nextLong(900, 2_000))
                        perform(DeskCatAct(DeskCatBehavior.computer, DeskCatPose.TYPE, random.nextLong(2_500, 4_500), DeskCatFace.FOCUSED))
                    }
                }
                DeskCatActivity.LISTENING, DeskCatActivity.SPEAKING -> {
                    val pose = if (activity == DeskCatActivity.LISTENING) DeskCatPose.LISTEN else DeskCatPose.TALK
                    val face = if (activity == DeskCatActivity.LISTENING) DeskCatFace.CURIOUS else DeskCatFace.HAPPY
                    perform(DeskCatAct(DeskCatBehavior.home, pose, 2_000, face))
                    while (animate && isActive) perform(DeskCatAct(DeskCatBehavior.home, pose, 2_000, face))
                }
                DeskCatActivity.IDLE -> {
                    request?.takeIf { it.first != cat.handledRequest }?.let { (id, obj) ->
                        perform(DeskCatBehavior.atObject(obj)) {
                            cat.handledRequest = id
                            bubble = lineForNow(obj); onArriveNow(obj)
                        }
                    }
                    reply?.takeIf { it.key != cat.handledReply }?.let {
                        cat.handledReply = it.key
                        perform(DeskCatAct(DeskCatBehavior.home, DeskCatPose.TALK, 3_600, DeskCatFace.HAPPY))
                    }
                    lookRoom?.takeIf { apartment && it != DeskRoom.of(cat.x) }?.let { room ->
                        // The user looked into another room: the cat trots over to join them.
                        moveTo(DeskCatSpot(room.center - 55f, DeskCatBehavior.FLOOR))
                        cat.pose = DeskCatPose.REST; cat.face = DeskCatFace.HAPPY
                    }
                    lookRoom = null
                    if (!animate) return@LaunchedEffect
                    // Leave an elevated prop with a visible hop before putting it away.
                    fun settle() = if (DeskRoom.of(cat.x) == DeskRoom.OFFICE || !apartment) DeskCatBehavior.home else DeskCatSpot(cat.x, DeskCatBehavior.FLOOR)
                    if (cat.y < 516f) moveTo(settle())
                    if (cat.pose != DeskCatPose.SLEEP) cat.pose = DeskCatPose.REST
                    var previous = DeskCatPose.REST
                    while (isActive) {
                        rest(DeskCatBehavior.restMs(random, wanderEveryMs.takeIf { it > 0 } ?: 20_000))
                        if (wanderEveryMs <= 0) continue
                        val act = if (!apartment) DeskCatBehavior.idle(random, previous, stateNow.trayCount > 0, stateNow.projectTitle.isNotBlank())
                            else DeskCatBehavior.apartmentIdle(random, previous, DeskRoom.of(cat.x), stateNow.trayCount > 0,
                                stateNow.projectTitle.isNotBlank(), stateNow.room.music?.playing == true, stateNow.room.video?.playing == true,
                                roomChance = if (wideLayout) .3f else .55f)
                        previous = act.pose
                        perform(act)
                        if (cat.y < 516f) moveTo(settle())
                        // Sleeping cats stay curled up until the next thing to do.
                        if (cat.pose != DeskCatPose.SLEEP) { cat.pose = DeskCatPose.REST; cat.face = DeskCatFace.NEUTRAL }
                    }
                }
            }
        } finally { cat.walking = false; cat.blink = 0f }
    }
    LaunchedEffect(bubble) { if (bubble != null) { delay(BUBBLE_MS); bubble = null } }
    LaunchedEffect(petting) {
        if (petting == 0) return@LaunchedEffect
        // Petting: a startled blink, then a happy face (a sleeping cat yawns awake first).
        val wasAsleep = cat.pose == DeskCatPose.SLEEP
        cat.face = if (wasAsleep) DeskCatFace.YAWN else DeskCatFace.SURPRISED
        delay(if (wasAsleep) 1_200 else 450)
        cat.face = listOf(DeskCatFace.HAPPY, DeskCatFace.PURR, DeskCatFace.LOVE).random(random)
        delay(2_400)
        if (cat.pose == DeskCatPose.SLEEP) cat.face = DeskCatFace.ASLEEP
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color(0xFF12232A)).clipToBounds().testTag("desk_habitat")) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val usableHeight = maxHeight - topInset - bottomInset
        // Unfolded (or any wide screen): all three rooms side by side. Folded: one room, following the cat.
        val wide = apartment && (maxWidth >= usableHeight || maxWidth >= 600.dp)
        wideLayout = wide
        val sceneWidth = if (wide) DeskRoom.APT_WIDTH else SCENE_W
        // Fit the part of the scene that has content (clock to desk), not the empty floor below it,
        // so the rooms are as large as the screen allows. The walls and floor extend to every edge.
        // Phone on its side: fill the height and let the camera follow the cat through the rooms.
        val pan = apartment && maxWidth > maxHeight && maxWidth / sceneWidth < usableHeight / (VIEW_BOTTOM - VIEW_TOP)
        // Sideways: zoomed in past fit-to-height; the desk stays anchored at the bottom, the top of the walls crops.
        // Sideways the living room and bedroom get roomier furniture (bookshelf, bed, TV, big window).
        if (DeskLayout.roomy != pan) DeskLayout.roomy = pan
        val zoom by DeskView.landscapeZoom.collectAsState()
        // Sideways default fits from just above the window rod to the floor; the setting zooms further by hand.
        val unit = if (pan) usableHeight / (VIEW_BOTTOM - VIEW_TOP_PAN) * zoom
            else minOf(maxWidth / sceneWidth, usableHeight / (VIEW_BOTTOM - VIEW_TOP)) // dp per scene unit
        // Screen x of scene x = 0 (the office's left wall) with the office centred / the panorama centred.
        val ox = if (wide) (maxWidth - unit * DeskRoom.APT_WIDTH) / 2 - unit * DeskRoom.APT_LEFT else (maxWidth - unit * SCENE_W) / 2
        val oy = maxHeight - bottomInset - unit * VIEW_BOTTOM // the desk sits just above the navigation
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scale = with(density) { unit.toPx() }
        val oxPx = with(density) { ox.toPx() }
        val oyPx = with(density) { oy.toPx() }
        // Folded: the camera pans (a layer translation, no redraw) to the room in view.
        val catRoom by remember { androidx.compose.runtime.derivedStateOf { DeskRoom.of(cat.x) } }
        val focus = if (!apartment || (wide && !pan)) DeskRoom.OFFICE else lookRoom ?: catRoom
        val viewPx = with(density) { maxWidth.toPx() }
        val camTarget = if (pan) {
            val target = lookRoom?.center ?: (cat.x + 55f)
            (viewPx / 2 - (oxPx + target * scale)).coerceIn(viewPx - (oxPx + DeskRoom.APT_RIGHT * scale), -(oxPx + DeskRoom.APT_LEFT * scale))
        } else (DeskRoom.OFFICE.center - focus.center) * scale
        val cam by androidx.compose.animation.core.animateFloatAsState(camTarget,
            if (pan) androidx.compose.animation.core.spring(stiffness = 60f)
            else if (animate) androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            else androidx.compose.animation.core.snap(), label = "desk_camera")
        // Zoomed sideways: the camera also rises and falls with the cat (desk, floor, cat towers).
        val viewHPx = with(density) { maxHeight.toPx() }
        val topPx = with(density) { topInset.toPx() }
        val bottomPx = with(density) { (maxHeight - bottomInset).toPx() }
        val camYTarget = if (pan) {
            val catMid = oyPx + (cat.y + 50f) * scale
            val want = (topPx + bottomPx) / 2 - catMid
            // Never lift the floor off the bottom or pull the ceiling below the top.
            want.coerceIn(bottomPx - (oyPx + VIEW_BOTTOM * scale), maxOf(0f, topPx - (oyPx + VIEW_TOP * scale)))
        } else 0f
        val camY by androidx.compose.animation.core.animateFloatAsState(camYTarget,
            androidx.compose.animation.core.spring(stiffness = 60f), label = "desk_camera_y")
        // Render and hit-test through the same transform.
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = cam; translationY = camY }) {

        // 1. Static rooms: their own graphics layer, drawn once.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            withTransform({ translate(oxPx, oyPx); scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) { drawRoom() }
        }
        // 1b. The hanging pictures (built-in art or the user's photos).
        val pictures by DeskPictures.choices.collectAsState()
        val photos = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>() }
        LaunchedEffect(pictures) {
            pictures.values.filter { !it.startsWith("art:") && it !in photos }.forEach { path ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { com.meowmba.shared.agent.ModelFile(path).readBytes()?.decodeToImageBitmap() }.getOrNull()
                }?.let { photos[path] = it }
            }
        }
        if (apartment) Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            withTransform({ translate(oxPx, oyPx); scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) {
                with(DeskPictures) { drawPictures(pictures, photos) }
            }
        }
        // 2. Objects: redraw only when state changes.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            withTransform({ translate(oxPx, oyPx); scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) { drawObjects(state, measurer) }
        }
        // 3. Cat (and the litter box's front lip when it's inside).
        Canvas(Modifier.fillMaxSize().testTag("desk_cat").semantics {
            stateDescription = DeskCatBehavior.description(cat.pose, cat.x)
        }) {
            withTransform({ translate(oxPx, oyPx); scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero) }) {
                drawCat(cat.x, cat.y, cat.walking, cat.frame, cat.blink, cat.tail, cat.gaze, cat.facingLeft, cat.pose, cat.phase, cat.face)
                if (cat.pose == DeskCatPose.DIG) with(DeskApartment) { drawLitterFront() }
            }
        }
        // The cat's head can be petted. It sits under the room's objects, so the cat never blocks one.
        if (interactive) Box(Modifier
            .offset { androidx.compose.ui.unit.IntOffset((oxPx + (cat.x + 46f) * scale).toInt(), (oyPx + (cat.y + 8f) * scale).toInt()) }
            .size(unit * 58f, unit * 62f)
            .clickable(role = Role.Button, onClickLabel = "Pet Meow") {
                petting++
                bubble = DeskCatBehavior.petLines.random(random)
            }
            .semantics { contentDescription = "Meow, " + DeskCatBehavior.description(cat.pose, cat.x) }
            .testTag("desk_cat_pet"))
        // Tap targets share the room transform and remain immediate, independent of walking.
        if (interactive) DeskObject.entries.filter { apartment || it.room == DeskRoom.OFFICE }.forEach { o ->
            val h = o.hit
            Box(Modifier.offset(ox + unit * h[0], oy + unit * h[1]).size(unit * h[2], unit * h[3])
                .clickable(role = Role.Button, onClickLabel = o.label) {
                    bubble = lineForNow(o) ?: o.label
                    request = ((request?.first ?: 0) + 1) to o
                    onTap(o)
                }.semantics { contentDescription = describe(o, state) }.testTag("desk_" + o.name.lowercase()))
        }
        }
        // Folded: step into the next room (the cat comes along).
        if (apartment && (!wide || pan) && interactive) Row(Modifier.align(Alignment.TopStart).padding(top = topInset, start = 8.dp)) {
            val left = DeskRoom.entries.getOrNull(focus.ordinal - 1)
            val right = DeskRoom.entries.getOrNull(focus.ordinal + 1)
            left?.let { r -> RoomStep("‹ ${r.label}", "desk_room_left") { lookRoom = r } }
            right?.let { r -> RoomStep("${r.label} ›", "desk_room_right") { lookRoom = r } }
        }
        // Actual replies have priority over decorative object hints and can be read in full.
        val shownReply = reply?.takeIf { it.key != dismissedReply && it.text.isNotBlank() }
        val shownText = shownReply?.text ?: bubble
        shownText?.let { text ->
            val bubbleWidth = minOf(if (shownReply != null) 320.dp else unit * 210f, viewportWidth - 24.dp)
            val bubbleHeight = minOf(300.dp, (viewportHeight - topInset - bottomInset) * .42f)
            Box(
                Modifier
                    .offset {
                        androidx.compose.ui.unit.IntOffset(
                            (ox + unit * (cat.x + 55f) + cam.toDp() - bubbleWidth / 2).coerceIn(12.dp, viewportWidth - bubbleWidth - 12.dp).roundToPx(), 0)
                    }
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        val top = (oyPx + camY + (cat.y - 8f) * scale).toInt() - p.height
                        val minTop = with(density) { (topInset + 8.dp).roundToPx() }
                        val maxTop = with(density) { (viewportHeight - bottomInset - 8.dp).roundToPx() } - p.height
                        layout(p.width, p.height) { p.place(0, top.coerceIn(minTop, maxOf(minTop, maxTop))) }
                    }
                    .width(bubbleWidth).heightIn(max = bubbleHeight)
                    .drawBehind { drawRoundRect(DeskPainter.INK, topLeft = androidx.compose.ui.geometry.Offset(0f, 3.dp.toPx()), size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())) }
                    .background(Color(0xFFF4EEDB), RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                    .border(2.5.dp, DeskPainter.INK, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .semantics { contentDescription = "Meow says: $text" }
                    .testTag("desk_bubble"),
            ) {
                Column {
                    if (shownReply != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("Meow", Modifier.weight(1f), style = TextStyle(color = DeskPainter.INK, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                        BasicText("Close", Modifier.clickable(role = Role.Button) { dismissedReply = shownReply.key }
                            .padding(horizontal = 8.dp, vertical = 10.dp).testTag("desk_reply_close"),
                            style = TextStyle(color = DeskPainter.INK, fontSize = 13.sp))
                    }
                    BasicText(text, modifier = if (shownReply != null) Modifier.verticalScroll(rememberScrollState()).testTag("desk_reply_text") else Modifier,
                        style = TextStyle(color = DeskPainter.INK, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun RoomStep(label: String, tag: String, onClick: () -> Unit) {
    BasicText(label, Modifier
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 12.dp)
        .testTag(tag),
        style = TextStyle(color = Color(0xFFF4EEDB), fontSize = 14.sp, fontWeight = FontWeight.Bold))
}

/** Screen-reader description that states the object's data, not just its name. */
internal fun describe(o: DeskObject, s: DeskState): String = when (o) {
    DeskObject.INBOX -> if (s.trayCount == 0) "Letter tray: nothing waiting" else "Letter tray: ${s.trayCount} ${if (s.trayCount == 1) "person needs" else "people need"} you"
    DeskObject.MONEY -> s.jarNote?.let { "Jar: $it" } ?: "Jar: nothing due"
    DeskObject.TODAY -> if (s.sticky.isEmpty()) "Sticky note: nothing next" else "Sticky note: next up ${s.sticky.joinToString(" ")}"
    DeskObject.PROJECT -> if (s.projectTitle.isBlank()) "Corkboard: no project pinned" else "Corkboard: ${s.projectTitle}, ${s.projectStatus}"
    DeskObject.BUILD -> if (s.monitorLines.isEmpty()) "Monitor: nothing running" else "Monitor: " + s.monitorLines.joinToString(", ") { it.text }
    DeskObject.IDEA -> if (s.lampOn) "Lamp: Meow has an idea for you" else "Lamp: off"
    DeskObject.WINDOW -> "Window: " + (s.weatherLine ?: "weather")
    DeskObject.CLOCK -> "Clock: open alarms and timers"
    DeskObject.DRAWER_TOP -> "Top drawer: your apps and tools"
    DeskObject.DRAWER_BOTTOM -> "Bottom drawer: your apps and tools"
    DeskObject.BIG_WINDOW -> "Big window: " + (s.weatherLine ?: "the view")
    DeskObject.ARCADE -> "Old TV and game console: open the arcade"
    DeskObject.TOWER_BED -> "Cat tower by the window"
    DeskObject.BED -> "Bed: nap time"
    DeskObject.FOOD -> "Food tray"
    DeskObject.LITTER -> "Litter box"
    DeskObject.BOOKSHELF -> "Bookshelf: " + (s.room.audiobook?.let { "listening to ${it.title}" } ?: "audiobooks and the Meow manual")
    DeskObject.BIG_TV -> "TV: " + (s.room.video?.let { "${it.title}${it.subtitle?.let { c -> ", $c" } ?: ""}" } ?: "nothing on YouTube")
    DeskObject.STEREO -> "Stereo: " + (s.room.music?.let { "${it.title}${it.subtitle?.let { a -> " by $a" } ?: ""}" } ?: "nothing playing")
    DeskObject.COUCH -> "Couch"
    DeskObject.TOWER_LIVING -> "Cat tower"
    DeskObject.PICTURE_LIVING_A, DeskObject.PICTURE_LIVING_B, DeskObject.PICTURE_BED_A, DeskObject.PICTURE_BED_B -> "Picture on the wall: tap to change it"
}

private class CatMotion {
    var x by mutableFloatStateOf(180f)
    var y by mutableFloatStateOf(516f)
    var pose by mutableStateOf(DeskCatPose.REST)
    var phase by mutableFloatStateOf(0f)
    var handledRequest: Int = 0
    var handledReply: String? = null
    var walking by mutableStateOf(false)
    var frame by mutableIntStateOf(0)
    var blink by mutableFloatStateOf(0f)
    var tail by mutableFloatStateOf(0f)
    var gaze by mutableFloatStateOf(0f)
    var facingLeft by mutableStateOf(false)
    var face by mutableStateOf(DeskCatFace.NEUTRAL)
}

private fun easeInOut(t: Float) = if (t < .5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

private const val SCENE_W = 412f
private const val VIEW_TOP_PAN = 54f
/** Scene rows with content: from above the window/clock to the skirting under the desk. */
private const val VIEW_TOP = 44f
private const val VIEW_BOTTOM = 748f
private const val BUBBLE_MS = 5_200L

/** Desk view preferences (Settings › Appearance). */
object DeskView {
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3.5f
    val landscapeZoom = kotlinx.coroutines.flow.MutableStateFlow(
        com.meowmba.shared.core.DefaultTextStorage.readText("desk_landscape_zoom")?.toFloatOrNull()?.coerceIn(MIN_ZOOM, MAX_ZOOM) ?: 1f)
    fun setLandscapeZoom(v: Float) {
        landscapeZoom.value = v.coerceIn(MIN_ZOOM, MAX_ZOOM)
        com.meowmba.shared.core.DefaultTextStorage.writeTextAtomic("desk_landscape_zoom", landscapeZoom.value.toString())
    }
}
