package com.meowmba.shared.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.core.screen.ScreenHelpState

/** Android ScreenHelpSheet: ask about one shared screen; Meow isn't watching or controlling the other app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenHelpSheet(state: ScreenHelpState, onAsk: (String, Boolean) -> Unit, onClose: () -> Unit) {
    var question by remember(state.observedAt) { mutableStateOf("") }
    var configuredAi by remember(state.observedAt) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 740.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ask Meow about this screen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text("One shared picture, read on this phone. Meow isn't watching or controlling the other app.", style = MaterialTheme.typography.bodySmall)
            if (state.reading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.text.isNotBlank()) {
                Text("Read from your screen", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                Text(state.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()))
                OutlinedTextField(question, { question = it.take(1000) }, label = { Text("What would you like help with?") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2)
                // The label toggles too; a bare Checkbox ignored taps on its text.
                Row(Modifier.toggleable(configuredAi, role = androidx.compose.ui.semantics.Role.Checkbox) { configuredAi = it },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(configuredAi, onCheckedChange = null)
                    Text("Use my configured AI for this question", Modifier.weight(1f))
                }
                Text(if (configuredAi) "The screen text and your question may be sent to your configured AI provider. The picture stays on the phone."
                    else "Uses the on-device model. Reading the picture itself stays on this phone.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onAsk(question.trim().ifBlank { "Explain what this screen is showing and what I can do next." }, configuredAi) },
                    enabled = !state.reading && !state.busy, modifier = Modifier.padding(top = 12.dp)) { Text("Ask Meow") }
            }
            state.answer?.let { Text(it, Modifier.padding(top = 18.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
