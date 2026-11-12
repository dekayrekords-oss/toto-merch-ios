package com.meowmba.shared.ui.flow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.flow.FlowComments
import com.meowmba.shared.core.flow.FlowItem
import com.meowmba.shared.memory.DateText

/** Android FlowCommentSheet: a note for Meow, or "this event is canceled" for one calendar occurrence. */
@Composable
internal fun FlowCommentSheet(item: FlowItem, lastComment: String?, busy: Boolean, result: String?,
    onSubmit: (String) -> Unit, onSetup: () -> Unit, onClose: () -> Unit) {
    var text by remember(item.intentionId) { mutableStateOf("") }
    val cancel = FlowComments.requestsCancellation(text)
    val target = FlowComments.target(item.intentionId)
    val offset = PlatformTime.utcOffsetMinutes()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Comment on this Flow", style = MaterialTheme.typography.titleLarge)
        Text(item.headline, style = MaterialTheme.typography.titleMedium)
        target?.let { Text(DateText.weekdayMonthDay(it.startMs, offset) + " · " + DateText.timeLine(it.startMs, offset)) }
        Text("A comment is for Meow, not a reply sent to the person. Ordinary comments are saved on this Flow. " +
            "For a calendar event, say ‘this event is canceled’ to cancel that occurrence.")
        lastComment?.let { Text("Last comment: $it") }
        OutlinedTextField(text, { text = it.take(1000) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Your comment") }, minLines = 2, maxLines = 5)
        if (cancel && target != null) Text("This will cancel only the occurrence shown above in its calendar, then remove its Flow item after checking. It will not cancel the whole series.")
        if (cancel && target == null) Text("This Flow does not have an exact calendar event attached. Nothing will be canceled.")
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank() && !busy && (!cancel || target != null)) {
            Text(if (busy) "Working…" else if (cancel) "Cancel this occurrence" else "Save comment")
        }
        TextButton(onClick = onSetup, enabled = !busy) { Text("Calendar & tool access guide") }
        TextButton(onClick = onClose, enabled = !busy) { Text("Close") }
    }
}
