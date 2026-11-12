package com.meowmba.shared.ui.flow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.flow.FlowInterrupt
import com.meowmba.shared.core.flow.FlowItem
import com.meowmba.shared.core.flow.FlowKind
import com.meowmba.shared.core.flow.FlowSettings
import com.meowmba.shared.core.flow.FlowSnapshot
import com.meowmba.shared.core.flow.FlowThemes
import com.meowmba.shared.core.flow.Signal
import com.meowmba.shared.core.flow.Urgency
import com.meowmba.shared.core.flow.WorkState

/** What the band shows after the user answered it (or the chance passed). */
data class BandResolution(val message: String)

/**
 * Meow Flow home mode (AppTheme.FLOW). Stateless: the caller supplies the snapshot from
 * FlowController and handles every action. Matches docs/design/meow-flow.html and
 * docs/design/README.md; screenshots in docs/design/screens/.
 *
 * Heat: everything is static between snapshots. The only animations are one-shot
 * (theme cross-fade, item enter, leader-line draw + travelling dot, band slide) and the
 * voice bars while [speaking] is true.
 */
@Composable
fun MeowFlowHome(
    snapshot: FlowSnapshot,
    settings: FlowSettings,
    dateLabel: String,
    modifier: Modifier = Modifier,
    distanceRemainingMeters: Int? = null,
    speaking: Boolean = false,
    bandResolution: BandResolution? = null,
    reduceMotion: Boolean = false,
    onPrimary: (FlowItem) -> Unit = {},
    onLater: (FlowItem) -> Unit = {},
    onInterruptAccept: (FlowInterrupt) -> Unit = {},
    onInterruptDecline: (FlowInterrupt) -> Unit = {},
    onAdjust: () -> Unit = {},
    /** Tap on the dateline: the "needs attention" list. Null = not tappable. */
    onTopBar: (() -> Unit)? = null,
    onAsk: () -> Unit = {},
    onMic: () -> Unit = {},
    /** Deprecated: the design has no button bar. Use [onMenu]/[onDesk] (dateline links). */
    navigation: (@Composable () -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onDesk: (() -> Unit)? = null,
    /** Voice state for the ask line: listening/thinking/speaking, with what was heard or said. */
    voice: FlowVoice = FlowVoice.Idle,
    /** Microphone privacy switch; null hides the button (previews). */
    micOn: Boolean? = null,
    onMicToggle: () -> Unit = {},
    /** The + button: camera, photo or file. Null hides it. */
    onAttach: (() -> Unit)? = null,
    northStar: (@Composable () -> Unit)? = null,
    onComment: (FlowItem) -> Unit = {},
) {
    val at = snapshot.at.takeIf { it > 0 } ?: com.meowmba.shared.core.PlatformTime.currentMillis()
    val hour = ((at / 60_000 + com.meowmba.shared.core.PlatformTime.utcOffsetMinutes()) % 1440 / 60).toInt()
    val night = when (settings.eveningLook) {
        com.meowmba.shared.core.flow.EveningLook.AUTO -> snapshot.evening && (hour >= 17 || hour < 6)
        else -> FlowThemes.isNight(settings.eveningLook, WorkState.OFF)
    }
    val target = FlowColors.from(FlowThemes.palette(settings.theme, night, settings.accent))
    val colors = animatedColors(target, if (reduceMotion) 0 else FlowDims.THEME_ANIM_MS)

    CompositionLocalProvider(LocalFlowColors provides colors) {
        val anchors = remember { mutableStateMapOf<String, LayoutCoordinates>() }
        var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var bandHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val interrupt = snapshot.interrupt
        val pushed = interrupt != null
        val streamTop by animateDpAsState(
            if (pushed) bandHeight + 14.dp else 12.dp,
            tween(if (reduceMotion) 0 else FlowDims.BAND_ANIM_MS, easing = BAND_EASING), label = "streamTop",
        )

        Box(
            modifier
                .fillMaxSize()
                .background(colors.paper)
                .onGloballyPositioned { root = it }
                .testTag("flow_home"),
        ) {
            SpineLine(snapshot, anchors, root, voice, reduceMotion)
            LeaderLines(snapshot, anchors, root, reduceMotion)

            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = FlowDims.sidePadStart, end = FlowDims.sidePadEnd, top = streamTop,
                        bottom = FlowDims.streamBottom + if (navigation != null) 48.dp else 0.dp),
            ) {
                // Tapping the top bar (anywhere but its links) opens what needs attention.
                Box(Modifier.onGloballyPositioned { anchors["dateline"] = it }
                    .then(if (onTopBar != null) Modifier.clickable(onClickLabel = "Show what needs attention", onClick = onTopBar)
                        .testTag("flow_topbar") else Modifier)) {
                    Dateline(dateLabel, snapshot.signals, anchors, onAdjust, onMenu, onDesk)
                }
                northStar?.invoke()
                snapshot.main?.let { main ->
                    key(main.intentionId) { MainThought(main, pushed, anchors, reduceMotion, onPrimary, onLater, onComment) }
                }
                if (snapshot.main == null && snapshot.more.isEmpty()) {
                    BasicText("Nothing needs you right now.", Modifier.padding(top = 30.dp).testTag("flow_empty"),
                        style = FlowType.headline.copy(color = colors.ink))
                    BasicText("Tell me what's on your mind below and I'll keep it for the right moment.",
                        Modifier.padding(top = 12.dp), style = FlowType.because.copy(color = colors.ink2))
                }
                Spacer(Modifier.weight(1f))
                // Keep a gap even when the screen is short (cover display) and the spacer collapses.
                Spacer(Modifier.height(20.dp))
                if (snapshot.more.isNotEmpty()) {
                    FlowList(snapshot.more, snapshot.evening, pushed, anchors, reduceMotion, onPrimary, onLater, onComment)
                }
            }

            AskLine(Modifier.align(Alignment.BottomCenter).padding(bottom = if (navigation != null) 48.dp else 0.dp)
                .onGloballyPositioned { anchors["ask"] = it }, onAsk, onMic, voice, micOn, onMicToggle, onAttach)
            if (navigation != null) Box(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)) { navigation() }

            AnimatedVisibility(
                visible = interrupt != null,
                enter = slideInVertically(tween(if (reduceMotion) 0 else FlowDims.BAND_ANIM_MS, easing = BAND_EASING)) { -it },
                exit = slideOutVertically(tween(if (reduceMotion) 0 else FlowDims.BAND_ANIM_MS, easing = BAND_EASING)) { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                val last = remember { mutableStateOf(interrupt) }
                if (interrupt != null) last.value = interrupt
                last.value?.let { shown ->
                    InterruptBand(
                        shown, distanceRemainingMeters, speaking, bandResolution,
                        Modifier.onSizeChanged { with(density) { bandHeight = it.height.toDp() } },
                        onInterruptAccept, onInterruptDecline, onComment,
                    )
                }
            }
        }
    }
}

private val BAND_EASING = CubicBezierEasing(.2f, .9f, .2f, 1f)

@Composable
private fun animatedColors(t: FlowColors, ms: Int): FlowColors {
    val spec = tween<Color>(ms)
    return FlowColors(
        animateColorAsState(t.paper, spec, label = "paper").value,
        animateColorAsState(t.ink, spec, label = "ink").value,
        animateColorAsState(t.ink2, spec, label = "ink2").value,
        animateColorAsState(t.ink3, spec, label = "ink3").value,
        animateColorAsState(t.rule, spec, label = "rule").value,
        animateColorAsState(t.accent, spec, label = "accent").value,
        animateColorAsState(t.onAccent, spec, label = "onAccent").value,
    )
}

// ---- dateline ------------------------------------------------------------------------------

@Composable
private fun Dateline(dateLabel: String, signals: List<Signal>, anchors: MutableMap<String, LayoutCoordinates>, onAdjust: () -> Unit,
                     onMenu: (() -> Unit)? = null, onDesk: (() -> Unit)? = null) {
    val c = LocalFlowColors.current
    Column(Modifier.fillMaxWidth().drawBehind {
        val y = size.height - 0.75.dp.toPx()
        drawLine(c.ink, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
    }.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(dateLabel.uppercase(), Modifier.weight(1f), style = FlowType.dateline.copy(color = c.ink))
            // Small-caps links, like the approved design's ADJUST: no separate button bar.
            onMenu?.let { DatelineLink("MENU", "Open the Meow menu", "flow_menu", it); DatelineSep() }
            onDesk?.let { DatelineLink("DESK", "Switch to Meow's Desk", "flow_desk", it); DatelineSep() }
            BasicText(
                "ADJUST",
                Modifier
                    .clickable(role = Role.Button, onClickLabel = "Adjust Meow Flow", onClick = onAdjust)
                    .underline(c.ink, 1.dp, 0.dp)
                    .testTag("flow_adjust"),
                style = FlowType.dateline.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            )
        }
        if (signals.isNotEmpty()) SignalLine(signals, anchors)
    }
}

@Composable
private fun DatelineLink(label: String, description: String, tag: String, onClick: () -> Unit) {
    val c = LocalFlowColors.current
    BasicText(label, Modifier.clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
        .underline(c.ink, 1.dp, 0.dp).testTag(tag),
        style = FlowType.dateline.copy(color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
}

@Composable
private fun DatelineSep() {
    BasicText("·", Modifier.padding(horizontal = 7.dp), style = FlowType.dateline.copy(color = LocalFlowColors.current.ink3))
}

/**
 * Signals: shrink 12 → 11 sp so short lines stay on one row, then wrap to further rows.
 * Nothing is cut off with "…" (owner review, build 158: the line was unreadable).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SignalLine(signals: List<Signal>, anchors: MutableMap<String, LayoutCoordinates>) {
    val c = LocalFlowColors.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val density = LocalDensity.current
        val sepPx = with(density) { 10.dp.roundToPx() }
        var size = 12f
        val datelineStyle = FlowType.dateline
        fun width(sz: Float): Int {
            val st = datelineStyle.copy(fontSize = sz.sp)
            return signals.sumOf { measurer.measure(it.label.uppercase(), st).size.width } +
                (signals.size - 1) * (measurer.measure("/", st).size.width + sepPx)
        }
        while (size > SIGNAL_MIN_SP && width(size) > maxPx) size -= 0.5f
        val style = FlowType.dateline.copy(fontSize = size.sp)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth().testTag("flow_signals")
                .semantics { contentDescription = "Right now: " + signals.joinToString(", ") { it.label } },
        ) {
            signals.forEachIndexed { i, s ->
                Row {
                    BasicText(
                        s.label.uppercase(),
                        Modifier.onGloballyPositioned { anchors["sig:" + s.key] = it }
                            .then(if (s.fresh) Modifier.underline(c.accent, 2.dp, 0.dp) else Modifier),
                        style = style.copy(color = if (s.fresh) c.accent else c.ink2),
                    )
                    if (i < signals.lastIndex) BasicText("/", Modifier.padding(horizontal = 5.dp), style = style.copy(color = c.ink3))
                }
            }
        }
    }
}

private const val SIGNAL_MIN_SP = 11f

// ---- main thought ----------------------------------------------------------------------------

internal fun kindLabel(item: FlowItem, evening: Boolean = false): String = when (item.kind) {
    FlowKind.WORK -> "Work"
    FlowKind.MONEY -> "Money"
    FlowKind.PEOPLE -> "People"
    FlowKind.HOME -> "Home"
    FlowKind.ERRAND -> "On the way"
    FlowKind.LEISURE -> if (evening) "Tonight" else "After work"
    FlowKind.HEALTH -> "Health"
    FlowKind.OTHER -> "For you"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainThought(
    item: FlowItem, compact: Boolean, anchors: MutableMap<String, LayoutCoordinates>, reduceMotion: Boolean,
    onPrimary: (FlowItem) -> Unit, onLater: (FlowItem) -> Unit, onComment: (FlowItem) -> Unit,
) {
    val c = LocalFlowColors.current
    Column(Modifier.padding(top = if (compact) 18.dp else 30.dp).enterAnimation(0, reduceMotion).testTag("flow_main")) {
        BasicText(
            kindLabel(item).uppercase(),
            Modifier.onGloballyPositioned { anchors["main:" + item.intentionId] = it },
            style = FlowType.label.copy(color = if (item.urgency == Urgency.URGENT) c.accent else c.ink2),
        )
        BasicText(item.headline, Modifier.padding(top = 8.dp).semantics { heading() }.testTag("flow_main_headline"),
            style = (if (compact) FlowType.headlineCompact else FlowType.headline).copy(color = c.ink))
        if (!compact && item.because.isNotBlank()) {
            BasicText(item.because, Modifier.padding(top = 12.dp), style = FlowType.because.copy(color = c.ink2))
        }
        FlowRow(Modifier.padding(top = if (compact) 14.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowButton(item.primary.label, c.ink, c.paper) { onPrimary(item) }
            item.secondary?.let { FlowLink(it.label, c.ink) { onLater(item) } }
            FlowLink("Comment", c.ink) { onComment(item) }
        }
    }
}

@Composable
internal fun FlowButton(label: String, fill: Color, text: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(FlowDims.buttonHeight)
            .clip(RoundedCornerShape(FlowDims.radius))
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { BasicText(label, style = FlowType.button.copy(color = text)) }
}

@Composable
internal fun FlowLink(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.height(48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        BasicText(label, Modifier.underline(color, 1.dp, 4.dp), style = FlowType.link.copy(color = color))
    }
}

// ---- more items ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowList(
    items: List<FlowItem>, evening: Boolean, compact: Boolean, anchors: MutableMap<String, LayoutCoordinates>,
    reduceMotion: Boolean, onPrimary: (FlowItem) -> Unit, onLater: (FlowItem) -> Unit, onComment: (FlowItem) -> Unit,
) {
    val c = LocalFlowColors.current
    Column(Modifier.fillMaxWidth().testTag("flow_more")) {
        BasicText(
            (if (evening) "If you feel like it" else "Also for you").uppercase(),
            Modifier.fillMaxWidth().ruleBelow(c.rule, 1.dp).padding(bottom = 8.dp),
            style = FlowType.label.copy(color = c.ink2),
        )
        items.forEachIndexed { index, item ->
            key(item.intentionId) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .enterAnimation(200 + index * FlowDims.ITEM_STAGGER_MS, reduceMotion)
                        .ruleBelow(c.rule, 1.dp)
                        .padding(vertical = 13.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag("flow_item_" + item.intentionId),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        item.heldReason?.let {
                            BasicText(it.uppercase(), Modifier.padding(bottom = 3.dp), style = FlowType.held.copy(color = c.accent))
                        }
                        Box {
                            // Leader-line dot, 22 dp left of the text, centred on the title's first line.
                            Box(
                                Modifier
                                    .offset(x = -FlowDims.dotInset, y = 8.5.dp)
                                    .size(FlowDims.dotSize)
                                    .clip(CircleShape)
                                    .background(c.ink)
                                    .onGloballyPositioned { anchors["item:" + item.intentionId] = it },
                            )
                            BasicText(item.headline, style = FlowType.itemTitle.copy(color = c.ink))
                        }
                        if (!compact && item.because.isNotBlank()) {
                            BasicText(item.because, Modifier.padding(top = 2.dp), style = FlowType.itemDetail.copy(color = c.ink2))
                        }
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FlowLink(item.primary.label, c.ink) { onPrimary(item) }
                            FlowLink("Later", c.ink) { onLater(item) }
                            FlowLink("Comment", c.ink) { onComment(item) }
                        }
                    }
                }
            }
        }
    }
}

// ---- ask line ------------------------------------------------------------------------------

@Composable
private fun AskLine(modifier: Modifier, onAsk: () -> Unit, onMic: () -> Unit, voice: FlowVoice = FlowVoice.Idle,
                    micOn: Boolean? = null, onMicToggle: () -> Unit = {}, onAttach: (() -> Unit)? = null) {
    val c = LocalFlowColors.current
    val live = voice !is FlowVoice.Idle
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = FlowDims.sidePadStart, end = FlowDims.sidePadEnd, bottom = FlowDims.askBottom)
            .fillMaxWidth()
            .height(FlowDims.askHeight)
            .drawBehind { drawLine(c.ink, Offset(0f, 0.75.dp.toPx()), Offset(size.width, 0.75.dp.toPx()), 1.5.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        onAttach?.let { attach ->
            Box(Modifier.size(FlowDims.micSize * 0.8f).clip(CircleShape).border(1.5.dp, c.ink, CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Add a photo or file", onClick = attach)
                .semantics { contentDescription = "Add a photo or file" }.testTag("flow_attach"),
                contentAlignment = Alignment.Center) { PlusGlyph(c.ink) }
        }
        BasicText(voice.line ?: if (micOn == false) "Mic is off. Type, or tap the mic to talk." else "Ask, or tell me what you're doing…",
            Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Ask Meow", onClick = onAsk)
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("flow_ask_line"),
            style = FlowType.ask.copy(color = if (live) c.ink else c.ink2), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Filled while Meow is listening, so a tap always shows a visible result.
        val listening = voice is FlowVoice.Listening
        Box(
            Modifier.size(FlowDims.micSize).clip(CircleShape)
                .then(if (listening) Modifier.background(c.ink, CircleShape) else Modifier)
                .border(1.5.dp, c.ink, CircleShape)
                .clickable(role = Role.Button, onClickLabel = if (listening) "Stop listening" else "Talk to Meow", onClick = onMic)
                .semantics { contentDescription = if (listening) "Listening. Tap to finish" else "Talk to Meow" }.testTag("flow_mic"),
            contentAlignment = Alignment.Center,
        ) { MicGlyph(if (listening) c.paper else if (micOn == false) c.ink3 else c.ink) }
        // Privacy switch: filled when the mic is off, so its state reads at a glance.
        if (micOn != null) Box(
            Modifier.size(FlowDims.micSize * 0.8f).clip(CircleShape)
                .then(if (!micOn) Modifier.background(c.ink, CircleShape) else Modifier)
                .border(1.5.dp, c.ink, CircleShape)
                .clickable(role = Role.Switch, onClickLabel = if (micOn) "Turn the microphone off" else "Turn the microphone on", onClick = onMicToggle)
                .semantics { contentDescription = if (micOn) "Microphone on. Tap to turn off" else "Microphone off. Tap to turn on" }
                .testTag("flow_mic_toggle"),
            contentAlignment = Alignment.Center,
        ) { MicGlyph(if (!micOn) c.paper else c.ink, slashed = true) }
    }
}

@Composable
private fun PlusGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val s = 2.dp.toPx()
        drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), s, StrokeCap.Round)
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), s, StrokeCap.Round)
    }
}

@Composable
private fun MicGlyph(color: Color, slashed: Boolean = false) {
    Canvas(Modifier.size(width = 18.dp, height = 22.dp)) {
        val s = 1.8.dp.toPx()
        val u = size.width / 18f
        drawRoundRect(color, topLeft = Offset(5 * u, 1 * u), size = androidx.compose.ui.geometry.Size(8 * u, 13 * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4 * u), style = Stroke(s))
        drawArc(color, 0f, 180f, false, topLeft = Offset(1.5f * u, 3f * u), size = androidx.compose.ui.geometry.Size(15 * u, 15 * u),
            style = Stroke(s, cap = StrokeCap.Round))
        drawLine(color, Offset(9 * u, 18 * u), Offset(9 * u, 21 * u), s, StrokeCap.Round)
        if (slashed) drawLine(color, Offset(1 * u, 1 * u), Offset(17 * u, 21 * u), s, StrokeCap.Round)
    }
}

// ---- interruption band -----------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterruptBand(
    interrupt: FlowInterrupt, remainingMeters: Int?, speaking: Boolean, resolution: BandResolution?,
    modifier: Modifier, onAccept: (FlowInterrupt) -> Unit, onDecline: (FlowInterrupt) -> Unit, onComment: (FlowItem) -> Unit,
) {
    val c = LocalFlowColors.current
    val item = interrupt.item
    Column(
        modifier
            .fillMaxWidth()
            .background(c.accent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = FlowDims.bandSidePad, end = FlowDims.bandSidePad, top = 12.dp, bottom = FlowDims.bandBottomPad)
            .semantics(mergeDescendants = false) { contentDescription = "Meow: " + item.headline }
            .testTag("flow_band"),
    ) {
        val kind = if (interrupt.countdown != null) "Stop here?" else kindLabel(item)
        BasicText("$kind · ${item.urgency.label}".uppercase(), Modifier.alpha(0.8f), style = FlowType.label.copy(color = c.onAccent))
        BasicText(item.headline, Modifier.padding(top = 8.dp).semantics { heading() }, style = FlowType.bandHeadline.copy(color = c.onAccent))
        BasicText(resolution?.message ?: item.because, Modifier.padding(top = 10.dp).alpha(0.92f), style = FlowType.bandBody.copy(color = c.onAccent))
        if (resolution == null) {
            interrupt.spokenLine?.takeIf { it != item.headline }?.let { line -> VoiceCaption(line, speaking, c.onAccent) }
            val cd = interrupt.countdown
            if (cd != null && remainingMeters != null) Distance(remainingMeters, cd.startMeters, c.onAccent)
            FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowButton(item.primary.label, c.onAccent, c.accent) { onAccept(interrupt) }
                FlowLink("Later", c.onAccent) { onDecline(interrupt) }
                FlowLink("Comment", c.onAccent) { onComment(item) }
            }
        }
    }
}

@Composable
private fun VoiceCaption(line: String, speaking: Boolean, color: Color) {
    Row(Modifier.padding(top = 12.dp).drawBehind {
        drawLine(color, Offset(0f, 2.dp.toPx()), Offset(0f, size.height - 2.dp.toPx()), 2.dp.toPx())
    }.padding(start = 14.dp), verticalAlignment = Alignment.Top) {
        if (speaking) VoiceBars(color)
        BasicText(if (speaking) "Speaking: \"$line\"" else line, style = FlowType.bandVoice.copy(color = color))
    }
}

@Composable
private fun VoiceBars(color: Color) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { while (true) { t.animateTo(1f, tween(900, easing = LinearEasing)); t.snapTo(0f) } }
    Canvas(Modifier.padding(top = 5.dp, end = 8.dp).size(width = 15.dp, height = 12.dp)) {
        repeat(3) { i ->
            val phase = ((t.value + i * 0.17f) % 1f)
            val h = size.height * (0.55f + 0.45f * kotlin.math.sin(phase * kotlin.math.PI).toFloat())
            val x = i * 5.dp.toPx() + 1.5.dp.toPx()
            drawLine(color, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), 3.dp.toPx())
        }
    }
}

@Composable
private fun Distance(remainingMeters: Int, startMeters: Int, color: Color) {
    val feet = (remainingMeters * 3.28084).toInt().coerceAtLeast(0)
    val fraction = if (startMeters <= 0) 1f else (1f - remainingMeters.toFloat() / startMeters).coerceIn(0f, 1f)
    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(if (feet > 0) "$feet ft" else "passed", style = FlowType.distance.copy(color = color))
        Canvas(Modifier.weight(1f).height(15.dp)) {
            val y = size.height / 2
            val dot = 3.dp.toPx()
            var x = 0f
            while (x < size.width - 4.dp.toPx()) { drawCircle(color, dot / 2, Offset(x + dot / 2, y)); x += dot * 2 }
            drawCircle(color, 4.5.dp.toPx(), Offset(size.width * fraction, y))
            drawLine(color, Offset(size.width - 1.dp.toPx(), 0f), Offset(size.width - 1.dp.toPx(), size.height), 2.dp.toPx())
        }
        BasicText("store", style = FlowType.distance.copy(color = color))
    }
}

// ---- leader lines ------------------------------------------------------------------------------

private data class Line(val path: Path, val hot: Boolean, val delayMs: Int)

/**
 * Thin ink lines from a dateline signal, down a lane on the left edge, to the item it caused
 * (README "Leader lines"). Drawn in once per snapshot with a travelling dot, then static.
 */
@Composable
private fun LeaderLines(
    snapshot: FlowSnapshot, anchors: Map<String, LayoutCoordinates>, root: LayoutCoordinates?, reduceMotion: Boolean,
) {
    val c = LocalFlowColors.current
    val progress = remember { Animatable(1f) }
    val total = (1 + (snapshot.more.size)) * FlowDims.LINE_STAGGER_ITEM_MS + 3 * FlowDims.LINE_STAGGER_SOURCE_MS + FlowDims.LINE_DRAW_MS + 300
    LaunchedEffect(snapshot.main?.intentionId, snapshot.more.map { it.intentionId }, snapshot.signals.map { it.key }, reduceMotion) {
        if (reduceMotion) { progress.snapTo(1f); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(total, easing = LinearEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = root ?: return@Canvas
        if (!r.isAttached) return@Canvas
        fun box(key: String): Rect? = anchors[key]?.takeIf { it.isAttached }?.let { r.localBoundingBoxOf(it) }
        val signalKeys = snapshot.signals.map { it.key }
        val hotKeys = snapshot.signals.filter { it.fresh }.map { it.key }.toSet()
        val lines = ArrayList<Line>()
        val targets = listOfNotNull(snapshot.main?.let { "main:" + it.intentionId to it }) +
            snapshot.more.map { "item:" + it.intentionId to it }
        targets.forEachIndexed { i, (anchorKey, item) ->
            val t = box(anchorKey) ?: return@forEachIndexed
            val isMain = anchorKey.startsWith("main:")
            val tx = if (isMain) t.left - 6.dp.toPx() else t.center.x
            val ty = t.center.y
            // Every item hangs from the dateline, as in the design: its own sources when they are showing,
            // else the nearest related signal, else the first one (the day's context).
            val shown = item.sources.filter { it in signalKeys }.ifEmpty {
                listOfNotNull(kindSignals(item.kind).firstOrNull { it in signalKeys } ?: signalKeys.firstOrNull())
            }
            shown.forEachIndexed { j, key ->
                val sIdx = signalKeys.indexOf(key)
                val s = box("sig:$key") ?: return@forEachIndexed
                val sx = s.left + minOf(14.dp.toPx(), s.width / 2)
                val sy = s.bottom - 2.dp.toPx()
                val lane = (11 + 4 * sIdx.coerceAtLeast(0)).dp.toPx()
                val path = Path().apply {
                    moveTo(sx, sy); lineTo(sx, sy + 8.dp.toPx())
                    quadraticBezierTo(sx, sy + 16.dp.toPx(), sx - 8.dp.toPx(), sy + 16.dp.toPx())
                    lineTo(lane + 6.dp.toPx(), sy + 16.dp.toPx())
                    quadraticBezierTo(lane, sy + 16.dp.toPx(), lane, sy + 22.dp.toPx())
                    lineTo(lane, ty - 6.dp.toPx())
                    quadraticBezierTo(lane, ty, lane + 6.dp.toPx(), ty)
                    lineTo(tx, ty)
                }
                lines += Line(path, key in hotKeys, i * FlowDims.LINE_STAGGER_ITEM_MS + j * FlowDims.LINE_STAGGER_SOURCE_MS)
            }
        }
        val now = progress.value * total
        val measure = PathMeasure()
        lines.forEach { line ->
            val color = if (line.hot) c.accent else c.ink3
            val width = (if (line.hot) 1.4.dp else 1.dp).toPx()
            val f = ((now - line.delayMs) / FlowDims.LINE_DRAW_MS).coerceIn(0f, 1f)
            if (f <= 0f) return@forEach
            if (f >= 1f) {
                drawPath(line.path, color, style = Stroke(width))
            } else {
                measure.setPath(line.path, false)
                val seg = Path()
                measure.getSegment(0f, measure.length * f, seg, true)
                drawPath(seg, color, style = Stroke(width))
            }
            // One travelling dot per line, once (starts 300 ms after the line begins).
            val tf = ((now - line.delayMs - 300) / FlowDims.TRAVEL_MS)
            if (tf in 0f..1f) {
                measure.setPath(line.path, false)
                drawCircle(c.ink, 2.4.dp.toPx(), measure.getPosition(measure.length * tf))
            }
        }
    }
}

/**
 * The permanent line (owner, 2026-09-24): one lane down the left margin from the dateline into the
 * ask box, always drawn. It carries movement tied to the chat box: dots flow down into it while the
 * user types or talks, up out of it while Meow thinks or answers, and one dot runs down when something
 * new arrives. Still otherwise (no idle animation, no heat).
 */
@Composable
private fun SpineLine(
    snapshot: FlowSnapshot, anchors: Map<String, LayoutCoordinates>, root: LayoutCoordinates?,
    voice: FlowVoice, reduceMotion: Boolean,
) {
    val c = LocalFlowColors.current
    val flowing = voice !is FlowVoice.Idle && !reduceMotion
    val downward = voice is FlowVoice.Listening
    val phase = remember { Animatable(0f) }
    // Continuous while the chat box is live; a single pass when the flow changes.
    LaunchedEffect(flowing, downward) {
        if (!flowing) return@LaunchedEffect
        while (true) { phase.snapTo(0f); phase.animateTo(1f, tween(1_400, easing = LinearEasing)) }
    }
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(snapshot.main?.intentionId, snapshot.more.map { it.intentionId }, flowing) {
        if (reduceMotion || flowing) return@LaunchedEffect
        // A pass when the flow changes, then (owner) a small one every ~45 s while idle: one dot, 2.4 s.
        arrival.snapTo(0f); arrival.animateTo(1f, tween(1_800, easing = LinearEasing))
        while (true) {
            kotlinx.coroutines.delay(IDLE_DOT_EVERY_MS)
            arrival.snapTo(0f); arrival.animateTo(1f, tween(2_400, easing = LinearEasing))
        }
    }
    val lineColor by animateColorAsState(if (flowing) c.accent else c.ink3, tween(400), label = "spine")
    Canvas(Modifier.fillMaxSize().testTag("flow_spine")) {
        val r = root ?: return@Canvas
        if (!r.isAttached) return@Canvas
        val top = anchors["dateline"]?.takeIf { it.isAttached }?.let { r.localBoundingBoxOf(it) } ?: return@Canvas
        val ask = anchors["ask"]?.takeIf { it.isAttached }?.let { r.localBoundingBoxOf(it) } ?: return@Canvas
        val lane = 11.dp.toPx()
        val y0 = top.bottom
        val yEnd = ask.top + 26.dp.toPx()
        val xEnd = ask.left + FlowDims.sidePadStart.toPx() - 6.dp.toPx()
        val path = Path().apply {
            moveTo(lane, y0); lineTo(lane, yEnd - 8.dp.toPx())
            quadraticBezierTo(lane, yEnd, lane + 8.dp.toPx(), yEnd); lineTo(xEnd, yEnd)
        }
        drawPath(path, lineColor, style = Stroke((if (flowing) 1.4.dp else 1.dp).toPx()))
        drawCircle(lineColor, 2.4.dp.toPx(), Offset(lane, y0)) // anchor under the dateline
        val m = PathMeasure().apply { setPath(path, false) }
        if (flowing) {
            // Three evenly spaced dots; down toward the ask box while listening, up while Meow answers.
            for (k in 0 until 3) {
                val f = ((phase.value + k / 3f) % 1f).let { if (downward) it else 1f - it }
                drawCircle(c.accent, 2.6.dp.toPx(), m.getPosition(m.length * f))
            }
        } else if (arrival.value < 1f) {
            drawCircle(c.ink, 2.4.dp.toPx(), m.getPosition(m.length * arrival.value))
        }
    }
}

private const val IDLE_DOT_EVERY_MS = 45_000L

/** Dateline signals an item of this kind most naturally comes from, best first. */
internal fun kindSignals(kind: FlowKind): List<String> = when (kind) {
    FlowKind.PEOPLE -> listOf("msg", "mail", "app", "cal")
    FlowKind.WORK -> listOf("app", "cal")
    FlowKind.HOME -> listOf("pkg", "loc", "wx")
    FlowKind.ERRAND -> listOf("loc", "need", "wx")
    FlowKind.LEISURE -> listOf("wx", "cal", "loc")
    FlowKind.MONEY -> listOf("mail", "msg", "cal")
    FlowKind.HEALTH -> listOf("cal", "move")
    FlowKind.OTHER -> listOf("need", "cal")
}

// ---- small modifiers ---------------------------------------------------------------------------

private fun Modifier.underline(color: Color, thickness: Dp, offset: Dp) = drawBehind {
    val y = size.height + offset.toPx() - thickness.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), thickness.toPx())
}

private fun Modifier.ruleBelow(color: Color, thickness: Dp) = drawBehind {
    val y = size.height - thickness.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), thickness.toPx())
}

/** Fade + 8 dp rise, 600 ms (README "Motion rules"). */
@Composable
private fun Modifier.enterAnimation(delayMs: Int, reduceMotion: Boolean): Modifier {
    if (reduceMotion) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(FlowDims.ITEM_IN_MS, delayMillis = delayMs)) }
    return this.alpha(a.value).offset { androidx.compose.ui.unit.IntOffset(0, (8 * (1 - a.value)).dp.roundToPx()) }
}

/** What the ask line shows while Meow is using the voice. */
sealed class FlowVoice(val line: String?) {
    data object Idle : FlowVoice(null)
    class Listening(heard: String?) : FlowVoice(heard?.takeIf { it.isNotBlank() }?.let { "“$it”" } ?: "Listening…")
    data object Thinking : FlowVoice("Thinking…")
    class Speaking(said: String?) : FlowVoice(said?.takeIf { it.isNotBlank() } ?: "Meow is answering…")
    /** A note from Meow, e.g. why the microphone can't start. */
    class Note(text: String) : FlowVoice(text)
}
