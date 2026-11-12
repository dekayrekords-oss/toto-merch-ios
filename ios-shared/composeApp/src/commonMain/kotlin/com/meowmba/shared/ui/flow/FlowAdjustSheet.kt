package com.meowmba.shared.ui.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.flow.EveningLook
import com.meowmba.shared.core.flow.FlowSettings
import com.meowmba.shared.core.flow.FlowThemes
import com.meowmba.shared.core.flow.Urgency

/**
 * The in-mode "Adjust" sheet (docs/design/screens/flow-11 and flow-12). Stateless: every
 * change is reported through [onChange]; FlowController re-applies policy without a model call.
 * Place inside the app's bottom-sheet host; this draws the sheet body only.
 *
 * Owner decisions: no "Runs on" choice (Laya runs on the phone only); Jev is opt-in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowAdjustSheet(
    settings: FlowSettings,
    learnedHours: String,
    night: Boolean,
    onChange: (FlowSettings) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** The rest of Meowmba's settings (the Flow screen has no button bar). */
    onAppSettings: (() -> Unit)? = null,
    /** Laya on this phone: what to say, and the one action that fits (Download / Cancel / Retry). */
    thinking: ThinkingStatus = ThinkingStatus.RULES_ONLY,
    /** App-wide voice level; null hides the control (previews/tests). */
    speechMode: SpeechMode? = null,
    onSpeechMode: (SpeechMode) -> Unit = {},
    /** Idle avatar key ("lumen"/"orb"/"off") and minutes; null hides the group. */
    idleAvatar: String? = null,
    idleMinutes: Int = IdleAvatarOptions.DEFAULT_MINUTES,
    onIdleAvatar: (key: String, minutes: Int) -> Unit = { _, _ -> },
    onNorthStars: (() -> Unit)? = null,
    onAccessSetup: (() -> Unit)? = null,
) {
    val colors = FlowColors.from(FlowThemes.palette(settings.theme, night, settings.accent))
    CompositionLocalProvider(LocalFlowColors provides colors) {
        val c = colors
        Column(
            modifier
                .fillMaxWidth()
                .background(c.paper)
                .drawBehind { drawLine(c.ink, Offset(0f, 0.75.dp.toPx()), Offset(size.width, 0.75.dp.toPx()), 1.5.dp.toPx()) }
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 30.dp)
                .testTag("flow_adjust_sheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("Settings", Modifier.weight(1f), style = FlowType.headline.copy(fontSize = 26.sp, lineHeight = 32.sp, color = c.ink))
                onAppSettings?.let { FlowLink("App settings", c.ink2, it); Spacer(Modifier.width(20.dp)) }
                FlowLink("Done", c.ink, onDone)
            }

            onNorthStars?.let { open ->
                Group("North Stars", "Your bigger goals, their connections, and one next step.") {
                    Box(Modifier.padding(top = 10.dp)) { FlowLink("Goals & progress", c.ink, open) }
                }
            }

            AskMeowSetup()
            onAccessSetup?.let { open ->
                Group("App & tool access", "Calendar and Reminders, mail drafts, location, Siri and Shortcuts.") {
                    FlowLink("Connect apps & tools", c.ink, open)
                }
            }

            Group("Colour") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 12.dp)) {
                    FlowThemes.all.forEach { t ->
                        val selected = t.key == settings.theme
                        Box(
                            Modifier
                                .size(52.dp)
                                .then(if (selected) Modifier.border(2.dp, c.ink, RoundedCornerShape(8.dp)).padding(4.dp) else Modifier)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(t.day[0]))
                                .border(1.5.dp, c.rule, RoundedCornerShape(6.dp))
                                .clickable(role = Role.RadioButton) { onChange(settings.copy(theme = t.key, accent = null)) }
                                .semantics { contentDescription = t.label + " theme"; this.selected = selected }
                                .testTag("flow_theme_" + t.key),
                        ) {
                            Box(Modifier.align(Alignment.BottomEnd).offset((-6).dp, (-6).dp).size(12.dp).clip(CircleShape).background(Color(t.day[5])))
                        }
                    }
                }
                BasicText("Accent", Modifier.padding(top = 14.dp), style = FlowType.itemDetail.copy(color = c.ink2))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    val current = settings.accent
                    Swatch(null, current == null, c) { onChange(settings.copy(accent = null)) }
                    ACCENTS.forEach { a -> Swatch(a, current == a, c) { onChange(settings.copy(accent = a)) } }
                }
                SettingRow("After work look", null) {
                    Segmented(EveningLook.entries.map { it to it.key.replaceFirstChar { ch -> ch.uppercase() } }, settings.eveningLook) {
                        onChange(settings.copy(eveningLook = it))
                    }
                }
            }

            Group("Interruptions", "When something can't wait. Everything else waits quietly in the flow.") {
                SettingRow("How many a day", "Most interruptions per day") {
                    BasicText(settings.maxPerDay.toString(), style = FlowType.itemDetail.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = c.ink))
                }
                androidx.compose.material3.Slider(
                    value = settings.maxPerDay.toFloat(),
                    onValueChange = { onChange(settings.copy(maxPerDay = it.toInt())) },
                    valueRange = 0f..FlowSettings.MAX_PER_DAY_LIMIT.toFloat(),
                    steps = FlowSettings.MAX_PER_DAY_LIMIT - 1,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.rule,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Interruptions per day" }.testTag("flow_max_per_day"),
                )
                SettingRow("How often", "Minimum time between") {
                    Segmented(FlowSettings.GAP_CHOICES.map { it to gapLabel(it) }, settings.minGapMinutes) { onChange(settings.copy(minGapMinutes = it)) }
                }
                SettingRow("How urgent", "Least urgent that can interrupt") {
                    Segmented(listOf(Urgency.URGENT, Urgency.WORTH_IT, Urgency.USEFUL).map { it to it.label }, settings.minUrgency) {
                        onChange(settings.copy(minUrgency = it))
                    }
                }
            }

            Group("How it reaches you") {
                if (speechMode != null) SettingRow("Meow speaks", speechMode.detail) {
                    Segmented(SpeechMode.entries.map { it to it.label }, speechMode, onSpeechMode)
                } else SettingRow("Speak out loud", "Say interruptions aloud, hands-free in the car") {
                    Toggle(settings.voice, "Speak out loud") { onChange(settings.copy(voice = it)) }
                }
                SettingRow("Notify in other apps", "Show as a notification when Meow isn't on screen") {
                    Toggle(settings.notifyOtherApps, "Notify in other apps") { onChange(settings.copy(notifyOtherApps = it)) }
                }
            }

            Group("Thinking", thinking.text) {
                thinking.action?.let { (label, act) ->
                    Box(Modifier.padding(top = 10.dp)) { FlowLink(label, c.ink, act) }
                }
            }

            if (idleAvatar != null) Group("When idle", "After a while without a touch, Meow rests as an avatar. New things cross the screen; tap to come back.") {
                SettingRow("Show", null) {
                    Segmented(IdleAvatarOptions.MODES, idleAvatar) { onIdleAvatar(it, idleMinutes) }
                }
                if (idleAvatar != "off") SettingRow("After", "Minutes without a touch") {
                    Segmented(IdleAvatarOptions.MINUTES.map { it to "${it}m" }, idleMinutes) { onIdleAvatar(idleAvatar, it) }
                }
            }

            Group("Work hours") {
                BasicText("Learned so far: $learnedHours", Modifier.padding(top = 10.dp), style = FlowType.itemDetail.copy(color = c.ink2))
                SettingRow("Detect when work ends", "Use what you're doing, not just the clock") {
                    Toggle(settings.detectWorkEnd, "Detect when work ends") { onChange(settings.copy(detectWorkEnd = it)) }
                }
            }
        }
    }
}

private val ACCENTS = listOf(0xFFC23B16, 0xFF1F4FBF, 0xFF2F7A3E, 0xFF8A2E5C, 0xFFB54708, 0xFF1C1B19).map { it.toInt() }

private fun gapLabel(minutes: Int) = if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"

@Composable
private fun Group(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    val c = LocalFlowColors.current
    Column(Modifier.padding(top = 20.dp).fillMaxWidth().drawBehind {
        drawLine(c.rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
    }.padding(top = 12.dp)) {
        BasicText(title.uppercase(), style = FlowType.label.copy(color = c.ink2))
        subtitle?.let { BasicText(it, Modifier.padding(top = 3.dp), style = FlowType.itemDetail.copy(fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)) }
        content()
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String?, control: @Composable () -> Unit) {
    val c = LocalFlowColors.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = FlowType.itemDetail.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink))
            subtitle?.let { BasicText(it, style = FlowType.itemDetail.copy(fontSize = 13.sp, lineHeight = 17.sp, color = c.ink2)) }
        }
        control()
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalFlowColors.current
    Row(Modifier.clip(RoundedCornerShape(4.dp)).border(1.5.dp, c.ink, RoundedCornerShape(4.dp))) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .height(36.dp)
                    .background(if (on) c.ink else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics { this.selected = on }
                    .drawBehind { if (i > 0) drawLine(c.rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) { BasicText(label, style = FlowType.itemDetail.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.paper else c.ink)) }
        }
    }
}

@Composable
private fun Toggle(on: Boolean, label: String, onToggle: (Boolean) -> Unit) {
    val c = LocalFlowColors.current
    Box(
        Modifier
            .size(width = 46.dp, height = 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .border(1.5.dp, c.ink, RoundedCornerShape(13.dp))
            .background(if (on) c.ink else Color.Transparent)
            .clickable(role = Role.Switch) { onToggle(!on) }
            .semantics { contentDescription = label; stateDescription = if (on) "On" else "Off" }
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(17.dp).clip(CircleShape).background(if (on) c.paper else c.ink3)) }
}

@Composable
private fun Swatch(argb: Int?, selected: Boolean, c: FlowColors, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .then(if (selected) Modifier.border(2.dp, c.ink, CircleShape).padding(3.dp) else Modifier)
            .clip(CircleShape)
            .background(argb?.let { Color(it) } ?: c.paper)
            .border(1.5.dp, c.rule, CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (argb == null) "Theme accent" else "Accent colour"; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { if (argb == null) BasicText("A", style = FlowType.itemAction.copy(fontSize = 12.sp, color = c.accent)) }
}

/** Status line for the Adjust sheet's "Thinking" group. */
data class ThinkingStatus(val text: String, val action: Pair<String, () -> Unit>? = null) {
    companion object {
        val RULES_ONLY = ThinkingStatus("Local rules decide. Nothing is sent to a decision service.")
    }
}
