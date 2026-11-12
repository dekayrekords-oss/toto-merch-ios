package com.meowmba.shared.ui.vending

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.meowmba.shared.ui.lumen.LumenFrame
import com.meowmba.shared.ui.lumen.lumenCenter

private enum class VendingPage(val title: String) {
    HUB("Vending Machine"), AVATAR_SIMPLE("Become anything"), AVATAR_WORKSHOP("Avatar workshop"),
    PROP_GENERATE("Prop dispenser"), PROP_LIBRARY("Prop library"),
}

private fun fmt2(v: Float): String { val r = kotlin.math.round(v * 100).toInt(); return "${if (r < 0) "-" else ""}${kotlin.math.abs(r) / 100}.${(kotlin.math.abs(r) % 100).toString().padStart(2, '0')}" }

/** A single measured root for generated paint (Android GeneratedAvatarPlacement). */
internal data class GeneratedAvatarPlacement(val center: Offset, val unit: Float)
internal fun generatedAvatarPlacement(width: Float, height: Float, frame: LumenFrame, scale: Float): GeneratedAvatarPlacement {
    val w = width.coerceAtLeast(1f); val h = height.coerceAtLeast(1f)
    val size = minOf(w, h)
    val unit = size * .42f * (scale.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 2f)
    val intended = lumenCenter(w, h, frame)
    val margin = minOf(unit * .55f, size * .47f)
    return GeneratedAvatarPlacement(Offset(intended.x.coerceIn(margin, w - margin), intended.y.coerceIn(margin, h - margin)), unit)
}

/** Android VendingMachineScreen (builds 122–183): dispense avatar forms and props for Lumen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendingMachineScreen(onClose: () -> Unit) {
    var page by remember { mutableStateOf(VendingPage.HUB) }
    val placing by Vending.placing.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (page != VendingPage.HUB) IconButton(onClick = { page = VendingPage.HUB }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to vending hub") }
                else { Icon(Icons.Default.Storefront, null); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(page.title, style = MaterialTheme.typography.headlineSmall)
                    if (page == VendingPage.HUB) Text("Insert an idea · dispense a creation", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close vending machine") }
            }
            Spacer(Modifier.height(12.dp))
            when (page) {
                VendingPage.HUB -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("Pick a lane. New creations use your selected AI model; saved ones work offline.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Lane("Avatars · simple", "Type or say “become a …” — Lumen turns into it.", Icons.Default.Face) { page = VendingPage.AVATAR_SIMPLE } }
                    item { Lane("Avatars · workshop", "Dial look, parts, style and detail into a new form.", Icons.Default.AutoAwesome) { page = VendingPage.AVATAR_WORKSHOP } }
                    item { Lane("Props · generate", "Dispense a prop the companion can hold, wear or place.", Icons.Default.Toys) { page = VendingPage.PROP_GENERATE } }
                    item { Lane("Props · library", "Browse, place and use your saved props.", Icons.Default.Inventory2) { page = VendingPage.PROP_LIBRARY } }
                }
                VendingPage.AVATAR_SIMPLE -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { BecomeAnything() }
                VendingPage.AVATAR_WORKSHOP -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Workshop() }
                VendingPage.PROP_GENERATE -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    PropGenerate { page = VendingPage.PROP_LIBRARY }
                }
                VendingPage.PROP_LIBRARY -> PropLibrary(Modifier.weight(1f))
            }
        }
        placing?.let { prop -> PropPlacementEditor(prop, Vending.worn.collectAsState().value,
            onSave = { Vending.savePlacement(prop, it) }, onDismiss = { Vending.placing.value = null }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Lane(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FormPreview(form: GeneratedAvatarForm?, prop: GeneratedPropForm? = null, height: androidx.compose.ui.unit.Dp = 180.dp) {
    val painter = remember { GeneratedAvatarPainter() }
    Canvas(Modifier.fillMaxWidth().height(height).background(Color(0xFF1A2030), RoundedCornerShape(16.dp))) {
        val p = generatedAvatarPlacement(size.width, size.height, LumenFrame(), 1.2f)
        if (form != null) painter.draw(this, form, LumenFrame(), 0f, p.center, p.unit)
        else {
            drawCircle(Color(0xFFE8D5B9).copy(alpha = .85f), p.unit * .36f, p.center)
            drawCircle(Color(0xFF182032), p.unit * .055f, p.center + Offset(-p.unit * .11f, -p.unit * .05f))
            drawCircle(Color(0xFF182032), p.unit * .055f, p.center + Offset(p.unit * .11f, -p.unit * .05f))
        }
        prop?.let { painter.drawProp(this, it, LumenFrame(), 0f, p.center, p.unit) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasonChips(level: InventReasonLevel, enabled: Boolean, onPick: (InventReasonLevel) -> Unit) {
    Text("Reason level", style = MaterialTheme.typography.titleSmall)
    Text(level.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        InventReasonLevel.entries.forEach { l -> FilterChip(level == l, { onPick(l) }, { Text(l.label) }, enabled = enabled) }
    }
}

@Composable
private fun Message(message: Pair<String, Boolean>?) {
    message?.let { (text, error) ->
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Android GeneratedAvatarControls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BecomeAnything() {
    val worn by Vending.worn.collectAsState()
    val saved by Vending.savedForms.collectAsState()
    val busy by Vending.avatarBusy.collectAsState()
    val message by Vending.avatarMessage.collectAsState()
    var request by remember { mutableStateOf("") }
    var refine by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(InventReasonLevel.BALANCED) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormPreview(worn)
        Text("Describe a creature, character or object. Your selected AI model draws a new cartoon form for Lumen, with the same brain, face and memory.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReasonChips(level, !busy) { level = it }
        OutlinedTextField(request, { request = it.take(GeneratedAvatarForms.MAX_REQUEST) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("What should I become?") }, placeholder = { Text("A purple dragon with tiny wings") }, minLines = 2, maxLines = 6,
            supportingText = { Text("${request.length}/${GeneratedAvatarForms.MAX_REQUEST} · Describe colors and distinctive features.") })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Purple dragon", "Walking coffee cup", "Tiny spaceship").forEach { ex ->
                FilterChip(request.equals(ex, true), { request = ex }, { Text(ex) }, enabled = !busy)
            }
        }
        Button({ Vending.createAvatar(request, level) }, Modifier.fillMaxWidth(), enabled = request.isNotBlank() && !busy) {
            Text(if (busy) "Creating your form…" else "Create avatar…")
        }
        Text("Or try a ready-made form (works offline)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(GeneratedAvatarExamples::coffeeCup, GeneratedAvatarExamples::spaceship, GeneratedAvatarExamples::dragon).forEach { make ->
                val form = remember { make() }
                AssistChip({ Vending.wearExample(form) }, { Text(form.name) }, enabled = !busy)
            }
        }
        if (worn != null) {
            Text("Refine current form", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(refine, { refine = it.take(GeneratedAvatarForms.MAX_REQUEST) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("How should ${worn!!.name} change?") }, placeholder = { Text("Add a longer curling tail") }, minLines = 2, maxLines = 4)
            OutlinedButton({ Vending.createAvatar(refine, level, refine = true) }, Modifier.fillMaxWidth(), enabled = refine.isNotBlank() && !busy) {
                Text(if (busy) "Refining…" else "Refine & keep wearing")
            }
            TextButton({ Vending.wear(null) }) { Text("Back to Lumen's own look") }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Your current avatar stays while the new form is created.", style = MaterialTheme.typography.bodySmall)
            TextButton(Vending::cancelAvatar) { Text("Cancel creation") }
        }
        Message(message)
        Text("You can also say “turn into…” in chat or by voice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (saved.isNotEmpty()) {
            Text("Your saved forms", style = MaterialTheme.typography.titleMedium)
            saved.forEach { form ->
                val selected = worn?.id == form.id
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ Vending.select(form.id) }, Modifier.weight(1f)) {
                            Text(form.name + if (selected) " · wearing now" else "", Modifier.fillMaxWidth())
                        }
                        IconButton({ Vending.deleteForm(form.id) }) { Icon(Icons.Default.DeleteOutline, "Remove ${form.name}") }
                    }
                }
            }
        }
    }
}

/** Android AvatarWorkshopPanel + AvatarWorkshopPrompt. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Workshop() {
    val worn by Vending.worn.collectAsState()
    val busy by Vending.avatarBusy.collectAsState()
    val message by Vending.avatarMessage.collectAsState()
    val fields = remember { mutableStateMapOf<String, String>() }
    var style by remember { mutableStateOf("cartoon") }
    var level by remember { mutableStateOf(InventReasonLevel.BALANCED) }
    fun f(k: String) = fields[k].orEmpty()
    val composed = AvatarWorkshopPrompt.build(f("subject"), f("look"), style, f("head"), f("body"), f("arms"), f("legs"), f("tail"), f("free"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FormPreview(worn)
        ReasonChips(level, !busy) { level = it }
        listOf("subject" to ("Subject" to "fox barista"), "look" to ("Overall look / colors" to "peach fur, teal apron")).forEach { (k, v) ->
            OutlinedTextField(f(k), { fields[k] = it.take(200) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(v.first) }, placeholder = { Text(v.second) })
        }
        Text("Style", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("cartoon", "chibi", "stylized", "toy-like").forEach { o -> FilterChip(style == o, { style = o }, { Text(o) }, enabled = !busy) }
        }
        Text("Parts", style = MaterialTheme.typography.titleSmall)
        listOf("head" to ("Head" to "round muzzle, soft ears"), "body" to ("Body" to "compact torso, apron"),
            "arms" to ("Arms" to "short arms, mitten paws"), "legs" to ("Legs" to "sturdy legs, soft boots"),
            "tail" to ("Tail / trailing" to "fluffy curling tail")).forEach { (k, v) ->
            OutlinedTextField(f(k), { fields[k] = it.take(120) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(v.first) }, placeholder = { Text(v.second) })
        }
        OutlinedTextField(f("free"), { fields["free"] = it.take(GeneratedAvatarForms.MAX_REQUEST) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Freeform detail") }, minLines = 2, maxLines = 5,
            supportingText = { Text("${composed.length}/${GeneratedAvatarForms.MAX_REQUEST} composed") })
        Button({ Vending.createAvatar(composed, level) }, Modifier.fillMaxWidth(), enabled = composed.isNotBlank() && !busy) {
            Text(if (busy) "Dispensing form…" else "Dispense new form")
        }
        if (worn != null) OutlinedButton({ Vending.createAvatar(composed, level, refine = true) }, Modifier.fillMaxWidth(), enabled = composed.isNotBlank() && !busy) {
            Text("Refine current (${worn!!.name})")
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(Vending::cancelAvatar, Modifier.fillMaxWidth()) { Text("Cancel") } }
        Message(message)
    }
}

object AvatarWorkshopPrompt {
    fun build(subject: String, look: String, style: String, head: String, body: String, arms: String, legs: String, tail: String, freeform: String): String {
        if (listOf(subject, look, head, body, arms, legs, tail, freeform).all { it.isBlank() }) return ""
        return buildString {
            append(subject.trim().ifBlank { "companion form" })
            if (style.isNotBlank()) append(", ").append(style.trim()).append(" style")
            if (look.isNotBlank()) append(", ").append(look.trim())
            fun part(label: String, value: String) { value.trim().takeIf { it.isNotEmpty() }?.let { append(", ").append(label.lowercase()).append(" ").append(it) } }
            part("Head", head); part("Body", body); part("Arms", arms); part("Legs", legs); part("Tail", tail)
            freeform.trim().takeIf { it.isNotEmpty() }?.let { append(", ").append(it) }
        }.take(GeneratedAvatarForms.MAX_REQUEST).trimEnd(',', ' ')
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PropGenerate(onOpenLibrary: () -> Unit) {
    val busy by Vending.propBusy.collectAsState()
    val message by Vending.propMessage.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GeneratedPropCategory.HELD) }
    var level by remember { mutableStateOf(InventReasonLevel.BALANCED) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Describe a prop for Lumen. The machine invents a small vector drawing and saves it to your prop library.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Category", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GeneratedPropCategory.entries.forEach { c -> FilterChip(category == c, { category = c }, { Text(c.name.lowercase()) }, enabled = !busy) }
        }
        ReasonChips(level, !busy) { level = it }
        OutlinedTextField(prompt, { prompt = it.take(GeneratedPropForms.MAX_REQUEST) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("What prop should we dispense?") }, placeholder = { Text("A tiny paper umbrella") }, minLines = 2, maxLines = 4,
            supportingText = { Text("${prompt.length}/${GeneratedPropForms.MAX_REQUEST}") })
        OutlinedTextField(detail, { detail = it.take(240) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Optional detail") }, placeholder = { Text("Peach paper, thin wooden handle") })
        Button({ Vending.inventProp(prompt, category.name, detail, level) }, Modifier.fillMaxWidth(), enabled = prompt.isNotBlank() && !busy) {
            Text(if (busy) "Dispensing prop…" else "Dispense prop")
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(Vending::cancelProp, Modifier.fillMaxWidth()) { Text("Cancel") } }
        Message(message)
        OutlinedButton(onOpenLibrary, Modifier.fillMaxWidth()) { Text("Open prop library") }
    }
}

@Composable
private fun PropLibrary(modifier: Modifier) {
    val props by Vending.savedProps.collectAsState()
    val equipped by Vending.equipped.collectAsState()
    val message by Vending.propMessage.collectAsState()
    val painter = remember { GeneratedAvatarPainter() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Message(message) }
        if (props.isEmpty()) item { Text("No props yet. Dispense one from the prop machine.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(props, key = { it.id }) { prop ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        painter.drawProp(this, prop, LumenFrame(), 0f, center, minOf(size.width, size.height), preview = true)
                    }
                    Text(prop.name, style = MaterialTheme.typography.titleMedium)
                    Text("${prop.category.name.lowercase()} · ${prop.description.take(120)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (prop.useHint.isNotBlank()) Text("Use: ${prop.useHint}", style = MaterialTheme.typography.bodySmall)
                    Text("Attach @ (${fmt2(prop.anchorX)}, ${fmt2(prop.anchorY)}) · scale ${fmt2(prop.attachScale)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (equipped?.id == prop.id) OutlinedButton({ Vending.equip(null) }) { Text("Put away") }
                        else Button({ Vending.equip(prop.id) }) { Text("Use with avatar") }
                        OutlinedButton({ Vending.placing.value = prop }) { Text("Place") }
                        IconButton({ Vending.deleteProp(prop.id) }) { Icon(Icons.Default.DeleteOutline, "Remove ${prop.name}") }
                    }
                }
            }
        }
    }
}

/** Android PropPlacementEditor: drag the prop onto the avatar, scale and rotate, then save. */
@Composable
fun PropPlacementEditor(prop: GeneratedPropForm, avatar: GeneratedAvatarForm?, onSave: (GeneratedPropForm) -> Unit, onDismiss: () -> Unit) {
    var anchorX by remember(prop.id) { mutableFloatStateOf(prop.anchorX) }
    var anchorY by remember(prop.id) { mutableFloatStateOf(prop.anchorY) }
    var scale by remember(prop.id) { mutableFloatStateOf(prop.attachScale) }
    var rotation by remember(prop.id) { mutableFloatStateOf(prop.attachRotation) }
    val avatarPainter = remember { GeneratedAvatarPainter() }
    val propPainter = remember { GeneratedPropPainter() }
    val draft = prop.copy(anchorX = anchorX, anchorY = anchorY, attachScale = scale, attachRotation = rotation)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = .97f)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Place ${prop.name}", style = MaterialTheme.typography.titleLarge)
                    Text("Drag the prop onto your avatar. Scale and rotate if needed, then save.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close placement editor") }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1A2030), RoundedCornerShape(18.dp)).pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val unit = minOf(size.width, size.height) * .42f
                    if (unit > 1f) { anchorX = (anchorX + drag.x / unit).coerceIn(-.5f, .5f); anchorY = (anchorY + drag.y / unit).coerceIn(-.5f, .5f) }
                }
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = generatedAvatarPlacement(size.width, size.height, LumenFrame(), 1.35f)
                    if (avatar != null) avatarPainter.draw(this, avatar, LumenFrame(), 0f, p.center, p.unit)
                    else {
                        drawCircle(Color(0xFFE8D5B9).copy(alpha = .9f), p.unit * .38f, p.center)
                        drawCircle(Color(0xFF182032), p.unit * .06f, p.center + Offset(-p.unit * .12f, -p.unit * .06f))
                        drawCircle(Color(0xFF182032), p.unit * .06f, p.center + Offset(p.unit * .12f, -p.unit * .06f))
                    }
                    propPainter.draw(this, draft, Offset(p.center.x + draft.anchorX * p.unit, p.center.y + draft.anchorY * p.unit), p.unit * .55f)
                }
            }
            Text("Scale ${fmt2(scale)}", style = MaterialTheme.typography.labelMedium)
            Slider(scale, { scale = it }, valueRange = .3f..2.5f)
            Text("Rotation ${rotation.toInt()}°", style = MaterialTheme.typography.labelMedium)
            Slider(rotation, { rotation = it }, valueRange = -180f..180f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val (x, y) = GeneratedPropForms.defaultAttach(prop.category, prop.name, prop.description)
                    anchorX = x; anchorY = y; scale = if (prop.category == GeneratedPropCategory.WEARABLE) .85f else 1f; rotation = 0f
                }, Modifier.weight(1f)) { Text("Reset default") }
                Button({ onSave(GeneratedPropForms.withPlacement(prop, anchorX, anchorY, scale, rotation)) }, Modifier.weight(1f)) { Text("Save placement") }
            }
        }
    }
}
