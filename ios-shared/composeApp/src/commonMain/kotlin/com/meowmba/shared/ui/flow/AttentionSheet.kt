package com.meowmba.shared.ui.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.flow.FlowItem
import com.meowmba.shared.core.flow.FlowSnapshot
import com.meowmba.shared.core.flow.Urgency

// Ported from Android ui/flow/FlowRoutes.kt (build 171): tapping Flow's top bar lists what needs attention.

/** What the top bar opens: urgent and worth-it items (and held interruptions), most urgent first. */
internal fun needsAttention(s: FlowSnapshot): List<FlowItem> =
    (listOfNotNull(s.interrupt?.item, s.main) + s.more)
        .distinctBy { it.intentionId }
        .filter { it.urgency.atLeast(Urgency.WORTH_IT) || it.heldReason != null }
        .sortedByDescending { it.urgency.ordinal }

@Composable
internal fun AttentionSheet(items: List<FlowItem>, c: FlowColors, onPrimary: (FlowItem) -> Unit,
                            onLater: (FlowItem) -> Unit, onClose: () -> Unit) {
    CompositionLocalProvider(LocalFlowColors provides c) {
        Column(Modifier.fillMaxWidth().background(c.paper)
            .drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 1.5.dp.toPx()) }
            .padding(start = 28.dp, end = 26.dp, top = 22.dp, bottom = 28.dp).testTag("flow_attention")) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BasicText("Needs attention", Modifier.weight(1f), style = FlowType.headline.copy(fontSize = 26.sp, lineHeight = 32.sp, color = c.ink))
                FlowLink("Close", c.ink2, onClose)
            }
            if (items.isEmpty()) BasicText("Nothing urgent right now. Everything else is waiting quietly in your flow.",
                Modifier.padding(top = 10.dp), style = FlowType.because.copy(color = c.ink2))
            items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(top = 18.dp)
                    .drawBehind { drawLine(c.rule, Offset(0f, size.height + 9.dp.toPx()), Offset(size.width, size.height + 9.dp.toPx()), 1.dp.toPx()) }) {
                    BasicText((kindLabel(item) + " · " + item.urgency.label).uppercase(),
                        style = FlowType.label.copy(color = if (item.urgency == Urgency.URGENT) c.accent else c.ink2))
                    BasicText(item.headline, Modifier.padding(top = 4.dp), style = FlowType.itemTitle.copy(color = c.ink))
                    item.because.takeIf { it.isNotBlank() }?.let { BasicText(it, Modifier.padding(top = 3.dp), style = FlowType.itemDetail.copy(color = c.ink2)) }
                    item.heldReason?.let { BasicText(it, Modifier.padding(top = 3.dp), style = FlowType.itemDetail.copy(color = c.accent)) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        FlowButton(item.primary.label, c.ink, c.paper) { onPrimary(item) }
                        FlowLink("Later", c.ink) { onLater(item) }
                    }
                }
            }
        }
    }
}

