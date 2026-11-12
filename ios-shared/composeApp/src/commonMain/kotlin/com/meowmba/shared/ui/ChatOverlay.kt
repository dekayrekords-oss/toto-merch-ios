package com.meowmba.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.MessageRole
import com.meowmba.shared.ui.theme.Surface

private enum class CompanionChatPane { Chat, Terminal }

/**
 * Android `ChatOverlay`: a sheet that rises over the home, reply first and full history on request.
 * Header (title · history · call · collapse), Chat/Terminal tabs, then `+ · field · mic · send`.
 */
@Composable
internal fun ChatOverlay(
    isExpanded: Boolean,
    onClose: () -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onDeleteMessage: (Int) -> Unit,
    messages: List<ChatMessage>,
    companionName: String,
    thinking: Boolean,
    listening: Boolean,
    listenPartial: String?,
    onMicClick: () -> Unit,
    callActive: Boolean,
    onCallClick: () -> Unit,
    recognitionIssue: String?,
    errorMessage: String?,
    onStop: (() -> Unit)?,
    onAttach: (() -> Unit)?,
    terminal: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Conversation",
) {
    var history by remember { mutableStateOf(false) }
    var pane by remember { mutableStateOf(CompanionChatPane.Chat) }
    val historyState = rememberLazyListState()
    val replyState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun collapse() { keyboard?.hide(); focus.clearFocus(); history = false; onClose() }
    val following by remember { derivedStateOf { historyState.firstVisibleItemIndex == 0 } }
    LaunchedEffect(messages.lastOrNull { it.role == MessageRole.USER }?.timestamp) { replyState.scrollToItem(0) }
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (following && !historyState.isScrollInProgress) historyState.scrollToItem(0)
    }
    AnimatedVisibility(isExpanded, enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(), modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            val limit = when {
                pane == CompanionChatPane.Terminal -> maxHeight * .78f
                history -> maxHeight * .82f
                else -> minOf(maxHeight, 340.dp)
            }
            Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().heightIn(max = limit).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        if (pane == CompanionChatPane.Chat) {
                            IconButton(onClick = { history = !history }) {
                                Icon(if (history) Icons.Default.UnfoldLess else Icons.Default.History,
                                    if (history) "Show compact reply" else "Show full conversation")
                            }
                            IconButton(onClick = onCallClick) {
                                Icon(if (callActive) Icons.Default.CallEnd else Icons.Default.Call,
                                    if (callActive) "End voice call" else "Start voice call",
                                    tint = if (callActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                        IconButton(onClick = { collapse() }) { Icon(Icons.Default.KeyboardArrowDown, "Collapse chat") }
                    }
                    TabRow(selectedTabIndex = pane.ordinal, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Tab(selected = pane == CompanionChatPane.Chat, onClick = { pane = CompanionChatPane.Chat },
                            text = { Text("Chat") })
                        Tab(selected = pane == CompanionChatPane.Terminal, onClick = { pane = CompanionChatPane.Terminal },
                            text = { Text("Terminal") })
                    }
                    if (pane == CompanionChatPane.Terminal) {
                        terminal(Modifier.weight(1f, fill = true).fillMaxWidth())
                    } else {
                        if (callActive) Text("Voice call active", style = MaterialTheme.typography.labelMedium)
                        if (history) {
                            val indexed = messages.withIndex().filter { it.value.role != MessageRole.SYSTEM && it.value.content.isNotBlank() }
                            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = historyState, reverseLayout = true) {
                                itemsIndexed(indexed.asReversed(), key = { _, it -> "${it.index}-${it.value.timestamp}" }) { _, entry ->
                                    val companion = entry.value.role == MessageRole.ASSISTANT
                                    ChatMessageRow(if (companion) companionName else (entry.value.speakerName ?: "You"),
                                        entry.value.content, companion) { onDeleteMessage(entry.index) }
                                }
                            }
                        } else {
                            val reply = messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.content
                            if (!reply.isNullOrBlank()) {
                                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = replyState) {
                                    item { SelectionContainer { Text(ChatMarkdown.render(reply), Modifier.padding(vertical = 8.dp),
                                        style = MaterialTheme.typography.bodyLarge) } }
                                }
                            }
                            if (thinking) Text("$companionName is thinking…", style = MaterialTheme.typography.labelMedium)
                        }
                        if (callActive) Text("Voice conversation active · tap the phone to end", style = MaterialTheme.typography.labelMedium)
                        (recognitionIssue ?: errorMessage)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
                        }
                        if (listening || !listenPartial.isNullOrBlank()) Text(
                            listenPartial?.takeIf { it.isNotBlank() } ?: "Listening… Tap the mic to finish.",
                            maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        // Android OpenAppCompletion (176): suggestions only; Send stays the boundary.
                        val completions = remember(draft) { com.meowmba.shared.platform.IosApps.completions(draft) }
                        if (completions.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                            completions.forEach { androidx.compose.material3.SuggestionChip(onClick = { onDraftChange(it) }, label = { Text(it, maxLines = 1) }) }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onAttach != null) IconButton(onClick = onAttach) { Icon(Icons.Default.Add, "Add a photo or file") }
                            OutlinedTextField(value = draft, onValueChange = onDraftChange,
                                modifier = Modifier.weight(1f).heightIn(max = 112.dp),
                                placeholder = { Text("Message $companionName") }, minLines = 1, maxLines = 4,
                                shape = RoundedCornerShape(22.dp))
                            IconButton(onClick = onMicClick) {
                                Icon(Icons.Default.Mic, if (listening) "Stop listening" else "Speak",
                                    tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (onStop != null) {
                                IconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop reply") }
                            } else {
                                IconButton(enabled = draft.isNotBlank(), onClick = {
                                    if (draft.isNotBlank()) { onSend(); keyboard?.hide(); focus.clearFocus() }
                                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Android `ChatMessage`: sender label with delete, then a tinted bubble. */
@Composable
internal fun ChatMessageRow(sender: String, message: String, isCompanion: Boolean, onDelete: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sender, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, "Delete message", modifier = Modifier.size(16.dp))
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = if (isCompanion)
            MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer) {
            SelectionContainer { Text(ChatMarkdown.render(message), Modifier.fillMaxWidth().padding(12.dp),
                color = if (isCompanion) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}
