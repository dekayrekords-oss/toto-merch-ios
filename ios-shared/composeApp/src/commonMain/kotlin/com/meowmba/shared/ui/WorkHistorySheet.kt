package com.meowmba.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.northstar.StarView
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.work.WorkJournal
import com.meowmba.shared.work.WorkStatus
import com.meowmba.shared.work.WorkTask

/** Android TaskHistorySheet: live work and saved receipts. No replay button for external actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkHistorySheet(tasks: List<WorkTask>, initialId: String?, stars: List<StarView>,
    onClose: () -> Unit, onLink: (WorkTask, String) -> String, onRemove: (String) -> Unit) {
    var selectedId by remember(initialId) { mutableStateOf(initialId) }
    val selected = tasks.firstOrNull { it.id == selectedId }
    var chooseGoal by remember(selectedId) { mutableStateOf(false) }
    var removing by remember(selectedId) { mutableStateOf(false) }
    var notice by remember(selectedId) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val offset = PlatformTime.utcOffsetMinutes()
    val links = remember(selected?.result) { Regex("https?://[^\\s<>\"]+").findAll(selected?.result.orEmpty())
        .map { it.value.trimEnd('.', ',', ')', ']', ';') }.distinct().take(8).toList() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Text(if (selected == null) "Meow’s work" else "Task result", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            if (selected == null) {
                item { Text("Saved results stay here when you reopen Meow. Interrupted actions are never repeated automatically.", style = MaterialTheme.typography.bodySmall) }
                if (tasks.isEmpty()) item { Text("No saved work yet. Ask Meow to set a reminder, draft a message, read a screen or run a shortcut.") }
                items(tasks, key = { it.id }) { task ->
                    OutlinedCard(onClick = { selectedId = task.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(WorkJournal.label(task), style = MaterialTheme.typography.labelLarge)
                            Text(DateText.weekdayMonthDay(task.createdAt, offset) + " · " + DateText.timeLine(task.createdAt, offset), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                item {
                    TextButton(onClick = { selectedId = null }) { Text("All work") }
                    Text(selected.title, style = MaterialTheme.typography.titleLarge)
                    Text(WorkJournal.label(selected), style = MaterialTheme.typography.labelLarge)
                    if (selected.recovered) Text("Saved from an earlier session", style = MaterialTheme.typography.bodySmall)
                    if (selected.status == WorkStatus.CANCELLED && selected.executionStarted)
                        Text("Further steps were stopped. An action already sent may still finish; check its destination before repeating it.")
                    SelectionContainer { Text(selected.result ?: if (selected.isTerminal) "No result was recorded." else "The result will appear here when this step finishes.",
                        Modifier.padding(vertical = 12.dp)) }
                    if (selected.log.isNotEmpty()) Text(selected.log.joinToString(" → "), style = MaterialTheme.typography.bodySmall)
                }
                items(links) { url -> TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) { Text(url, maxLines = 2) } }
                if (selected.isTerminal) item { TextButton(onClick = { removing = true }) { Text("Remove saved result") } }
                if (selected.isTerminal && !selected.result.isNullOrBlank() && stars.isNotEmpty()) item {
                    OutlinedButton(onClick = { chooseGoal = !chooseGoal }) { Text("Link to a North Star") }
                    if (chooseGoal) stars.forEach { view -> TextButton(onClick = { notice = onLink(selected, view.star.id); chooseGoal = false }) { Text(view.star.title) } }
                }
            }
        }
    }
    if (removing && selected != null) AlertDialog(onDismissRequest = { removing = false },
        title = { Text("Remove saved result?") },
        text = { Text("This removes the task record. Anything it created (a reminder, an event, a draft) stays.") },
        confirmButton = { TextButton(onClick = { removing = false; onRemove(selected.id); selectedId = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep") } })
}
