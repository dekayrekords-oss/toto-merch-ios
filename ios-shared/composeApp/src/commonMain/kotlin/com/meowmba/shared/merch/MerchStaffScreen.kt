package com.meowmba.shared.merch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Staff / helper editor for the merch table: add, edit, or remove menu items and
 * set PayPal keys. This is not shown on the customer walk-up menu.
 */
@Composable
fun MerchStaffScreen(
    snapshot: MerchSnapshot,
    controller: MerchTableController,
    paypal: PayPalSettings,
    onPaypalChange: (PayPalSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<MerchItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Edit menu", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These items appear on the customer screen and in voice shopping. Changes are saved on this device only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var totoSource by remember { mutableStateOf(com.meowmba.shared.voice.TotoVoicePreferences.source) }
        Text("Toto's voice", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = totoSource == com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE,
                    role = Role.Switch,
                    onValueChange = { on ->
                        totoSource = if (on) com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE
                            else com.meowmba.shared.voice.TotoVoiceSource.SYSTEM
                        com.meowmba.shared.voice.TotoVoicePreferences.source = totoSource
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Toto voice: On phone", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = totoSource == com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE,
                onCheckedChange = null,
            )
        }
        Text(
            "Default on. Original Toto voice, generated on this iPhone. The built-in voice is only an emergency fallback. Lion stays an optional advanced setting, off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot.items.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    Money.format(item.priceCents) + "  ·  " + item.sizes.joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = { adding = false; editing = item }) { Text("Edit") }
                    TextButton(onClick = { controller.removeItem(item.sku); if (editing?.sku == item.sku) editing = null }) { Text("Remove") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { editing = null; adding = true }) { Text("Add item") }
            OutlinedButton(onClick = { controller.resetMenuToDefaults(); editing = null; adding = false }) { Text("Restore defaults") }
        }
        if (adding || editing != null) {
            MerchItemEditor(
                initial = editing,
                stock = snapshot.stock,
                onCancel = { adding = false; editing = null },
                onSave = { item, stockBySize ->
                    val sku = item.sku.ifBlank { MerchCatalog.newSku(item.shortName.ifBlank { item.name }) }
                    controller.upsertItem(item.copy(sku = sku), stockBySize)
                    adding = false
                    editing = null
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        MerchPayPalSettings(paypal, onPaypalChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MerchItemEditor(
    initial: MerchItem?,
    stock: Map<String, Int>,
    onCancel: () -> Unit,
    onSave: (MerchItem, Map<String, Int>) -> Unit,
) {
    var name by remember(initial?.sku) { mutableStateOf(initial?.name ?: "") }
    var shortName by remember(initial?.sku) { mutableStateOf(initial?.shortName ?: "") }
    var description by remember(initial?.sku) { mutableStateOf(initial?.description ?: "") }
    var price by remember(initial?.sku) { mutableStateOf(initial?.let { Money.format(it.priceCents).removePrefix("$") } ?: "") }
    var sizes by remember(initial?.sku) { mutableStateOf(initial?.sizes?.toSet() ?: MerchCatalog.ADULT_SIZES.toSet()) }
    var stockText by remember(initial?.sku) {
        mutableStateOf(
            (initial?.sizes ?: MerchCatalog.ADULT_SIZES).associateWith { size ->
                (stock[MerchCatalog.stockKey(initial?.sku ?: "", size)] ?: MerchCatalog.DEFAULT_STOCK_PER_SIZE).toString()
            }.toMutableMap()
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (initial == null) "New item" else "Edit ${initial.shortName}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(shortName, { shortName = it }, label = { Text("Short name (on the menu)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(price, { price = it }, label = { Text("Price (for example 25 or 25.00)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(description, { description = it }, label = { Text("Color / description") }, modifier = Modifier.fillMaxWidth())
        Text("Sizes", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MerchCatalog.COMMON_SIZES.forEach { size ->
                FilterChip(selected = size in sizes, onClick = {
                    sizes = if (size in sizes) sizes - size else sizes + size
                    if (size !in stockText) stockText = (stockText + (size to MerchCatalog.DEFAULT_STOCK_PER_SIZE.toString())).toMutableMap()
                }, label = { Text(size) })
            }
        }
        sizes.sortedBy { MerchCatalog.COMMON_SIZES.indexOf(it).let { i -> if (i < 0) 99 else i } }.forEach { size ->
            OutlinedTextField(
                value = stockText[size] ?: "0",
                onValueChange = { stockText = (stockText + (size to it.filter { ch -> ch.isDigit() })).toMutableMap() },
                label = { Text("Stock in $size") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val cents = Money.parseToCents(price) ?: return@Button
                    val item = MerchItem(
                        sku = initial?.sku.orEmpty(),
                        name = name,
                        shortName = shortName.ifBlank { name },
                        description = description,
                        priceCents = cents,
                        sizes = sizes.toList(),
                        keywords = MerchCatalog.keywordsFor(name, shortName, description),
                        pitch = "the ${shortName.ifBlank { name }}. It's " + Money.spoken(cents),
                    )
                    val stockBySize = sizes.associateWith { size -> stockText[size]?.toIntOrNull() ?: 0 }
                    onSave(item, stockBySize)
                },
                enabled = name.isNotBlank() && Money.parseToCents(price) != null && sizes.isNotEmpty(),
            ) { Text("Save item") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun MerchPayPalSettings(paypal: PayPalSettings, onChange: (PayPalSettings) -> Unit) {
    var showSecret by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PayPal", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Real sandbox payments go through the merch backend (merch-backend/ in the repo). The PayPal client secret lives only in that backend's .env, never on this phone. Live PayPal is not called from this test app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(paypal.simulate, role = Role.Switch) {
                onChange(paypal.copy(simulate = it))
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Simulate payments", style = MaterialTheme.typography.titleMedium)
                Text("On by default. While this is on, the till never calls PayPal, even if keys are filled in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = paypal.simulate, onCheckedChange = { onChange(paypal.copy(simulate = it)) })
        }
        Text("Environment", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = paypal.env.equals("sandbox", true), onClick = { onChange(paypal.copy(env = "sandbox")) }, label = { Text("Sandbox") })
            FilterChip(selected = paypal.env.equals("live", true), onClick = { onChange(paypal.copy(env = "live")) }, label = { Text("Live (not charged here)") })
        }
        OutlinedTextField(paypal.clientId, { onChange(paypal.copy(clientId = it)) }, label = { Text("PayPal client ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(
            paypal.backendUrl,
            { onChange(paypal.copy(backendUrl = it)) },
            label = { Text("Merch backend URL, e.g. http://192.168.1.20:8787") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            paypal.backendToken,
            { onChange(paypal.copy(backendToken = it)) },
            label = { Text("Backend token (MERCH_APP_TOKEN, optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
        )
        Text(
            if (paypal.simulate) "Simulate is on: checkout uses stub order ids (STUB-…) and the Simulate payment button."
            else if (paypal.env.equals("live", true)) "Live environment is recorded, but this test app will not call PayPal's live API."
            else if (paypal.backendUrl.isBlank()) "Simulate is off, but there's no backend URL yet, so checkout stays on the stub."
            else "Sandbox is on: checkout creates real PayPal sandbox orders through the backend and shows a scannable QR code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
