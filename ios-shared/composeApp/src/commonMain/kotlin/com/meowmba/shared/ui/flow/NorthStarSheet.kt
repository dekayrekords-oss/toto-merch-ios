package com.meowmba.shared.ui.flow

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.northstar.NorthStar
import com.meowmba.shared.core.northstar.NorthStarPolicy
import com.meowmba.shared.core.northstar.NorthStars
import com.meowmba.shared.core.northstar.StarView
import com.meowmba.shared.memory.DateText

/** Android NorthStarLine: the pinned goal and one next step, under Flow's dateline. */
@Composable
internal fun NorthStarLine(view: StarView?, onOpen: () -> Unit) {
    val c = LocalFlowColors.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp).clickable(onClick = onOpen).padding(vertical = 6.dp)) {
        BasicText(view?.let { "NORTH STAR → ${it.star.title}" } ?: "NORTH STAR · What are you working toward?",
            style = FlowType.label.copy(color = c.accent))
        if (view != null) {
            BasicText(view.nextStep, Modifier.padding(top = 5.dp), style = FlowType.itemDetail.copy(color = c.ink), maxLines = 2)
            BasicText(view.momentum, Modifier.padding(top = 4.dp), style = FlowType.itemDetail.copy(color = c.ink2), maxLines = 1)
        }
    }
}

/** Goal, evidence, edit and delete all have one visible home. No hidden autonomous action. */
@Composable
internal fun NorthStarSheet(stars: NorthStars, onClose: () -> Unit, onOpenSource: (String) -> Unit = {}) {
    NorthStarContent(stars, onClose, onOpenSource)
}

@Composable
private fun NorthStarContent(stars: NorthStars, onClose: () -> Unit, onOpenSource: (String) -> Unit) {
    val views by stars.views.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var why by remember { mutableStateOf("") }
    var horizon by remember { mutableStateOf("Someday") }
    var steps by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<NorthStar?>(null) }
    fun edit(star: NorthStar?) {
        editId = star?.id; title = star?.title.orEmpty(); why = star?.why.orEmpty()
        horizon = star?.horizon ?: "Someday"; steps = star?.ingredients()?.joinToString("\n") { it.text }.orEmpty()
        error = null; editing = true
    }
    fun action(block: () -> Unit) {
        try { block(); error = null } catch (e: Exception) { error = e.message ?: "That change couldn't be saved. Please try again." }
    }
    val offset = PlatformTime.utcOffsetMinutes()
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("North Stars", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text("The life you're building, one step at a time. Goals and connections stay on this phone.", style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (editing) {
            OutlinedTextField(title, { title = it.take(240) }, label = { Text("What do you want to achieve?") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            OutlinedTextField(why, { why = it.take(600) }, label = { Text("Why it matters to you (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(horizon, { horizon = it.take(120) }, label = { Text("Horizon · this year, someday…") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(steps, { steps = it.take(4000) }, label = { Text("Ingredients / small steps · one per line") },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { steps = NorthStarPolicy.suggestedIngredients(title).joinToString("\n") { it.text } }) { Text("Suggest starting steps") }
            Text("These are starting ideas. Edit them to fit your goal.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = title.trim().length >= 3, onClick = {
                    action { stars.save(editId, title, why, horizon, steps.lines()); editing = false }
                }) { Text("Save goal") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        } else Button(onClick = { edit(null) }, modifier = Modifier.padding(top = 12.dp)) { Text("Add a North Star") }
        if (views.isEmpty() && !editing) Text("Try “My dream is to play in Europe with my band”, or add a goal above.", Modifier.padding(vertical = 16.dp))
        views.forEachIndexed { index, view ->
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(view.star.title, style = MaterialTheme.typography.titleLarge)
            Text(view.star.horizon, style = MaterialTheme.typography.labelLarge)
            if (view.star.why.isNotBlank()) Text(view.star.why, Modifier.padding(top = 4.dp))
            Text(view.momentum, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Text("One next step: ${view.nextStep}", Modifier.padding(top = 8.dp))
            Text("This week", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
            Text(view.weeklyReview, style = MaterialTheme.typography.bodyMedium)
            Text("What makes it real", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
            view.star.ingredients().forEach { ingredient ->
                Row(Modifier.fillMaxWidth().toggleable(ingredient.doneAt != null, role = androidx.compose.ui.semantics.Role.Checkbox) { done ->
                    action { stars.markIngredient(view.star.id, ingredient.text, done) } }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ingredient.doneAt != null, onCheckedChange = null)
                    Text(ingredient.text, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { edit(view.star) }) { Text("Edit") }
                if (index != 0) TextButton(onClick = { action { stars.pin(view.star.id) } }) { Text("Pin on Desk") }
                else Text("Pinned on Desk", Modifier.padding(vertical = 13.dp), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { delete = view.star }) { Text("Delete") }
            }
            Text("Recent connections · up to 500 across your goals", style = MaterialTheme.typography.titleMedium)
            if (view.links.isEmpty()) Text("No connections yet. Relevant Flow items, events and things you tell Meow can appear here.", style = MaterialTheme.typography.bodySmall)
            view.links.forEach { link ->
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(DateText.weekdayMonthDay(link.at, offset) + " · " + DateText.timeLine(link.at, offset) + " · " + when (link.kind) {
                        "PROGRESS" -> "Reported progress"; "OPPORTUNITY" -> "Possible opening"; "MISSED" -> "Reported missed opening"; else -> "Connection"
                    }, style = MaterialTheme.typography.labelSmall)
                    Text(link.text, style = MaterialTheme.typography.bodyMedium)
                    Text(link.reason, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (link.sourceKey.startsWith("flow:") || link.sourceKey.startsWith("task:")) TextButton(onClick = {
                            onOpenSource(link.sourceKey)
                        }) { Text(if (link.sourceKey.startsWith("task:")) "Open task result" else "Open item") }
                        TextButton(onClick = { action { stars.dismiss(link.id) } }) { Text("Remove connection") }
                    }
                }
            }
        }
    }
    delete?.let { star -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this North Star?") },
        text = { Text("“${star.title}” and its saved connections will be removed. Your original messages and other app data stay saved.") },
        confirmButton = { TextButton(onClick = { action { stars.remove(star.id); delete = null } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } }) }
}
