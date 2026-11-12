package com.meowmba.shared.merch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Ink = Color(0xFF12100E)
private val Panel = Color(0xF21C1916)
private val CardBg = Color(0xFF2A241F)
private val Cream = Color(0xFFF6EFE4)
private val Gold = Color(0xFFE8C572)
private val Muted = Color(0xFFC4B6A4)
private val PayPalNavy = Color(0xFF003087)
private val PayPalBlue = Color(0xFF009CDE)
private val PayPalGold = Color(0xFFFFC439)
private val TicketGrab = Color(0xFFFFE08A)
private val TicketPaid = Color(0xFFBDEDC6)
private val TicketDone = Color(0xFFD7D0C6)
private val shape = RoundedCornerShape(24.dp)

/**
 * Full-screen merch overlay for the face-only home. The avatar stays underneath;
 * this sheet is a high-contrast kiosk menu meant to be read and tapped at arm's
 * length on a robot-mounted phone. Hidden while idle or after "just looking".
 */
@Composable
fun MerchTablePanel(
    snapshot: MerchSnapshot,
    controller: MerchTableController,
    onSay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!snapshot.enabled) return
    LaunchedEffect(snapshot.phase, snapshot.menuVisible) {
        while (snapshot.menuVisible) {
            delay(1000)
            controller.tick()
        }
    }
    // Sandbox: watch PayPal while the customer pays on their own phone (QR) or on this screen.
    LaunchedEffect(snapshot.phase, snapshot.paypalOrderId, snapshot.paypalMode) {
        while (snapshot.phase == MerchPhase.AWAITING_PAYMENT && snapshot.paypalMode == "sandbox" && snapshot.paypalOrderId != null) {
            delay(3000)
            controller.checkPayment { onSay(it) }
        }
    }
    val uriHandler = LocalUriHandler.current
    if (!snapshot.menuVisible) {
        snapshot.ticket?.let { ticket ->
            Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                HelperTicketCard(ticket, onDone = { controller.markTicketDone() })
            }
        }
        return
    }
    Box(
        modifier
            .fillMaxSize()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Panel)
            .border(1.dp, Gold.copy(alpha = 0.28f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CloseMenuButton(onClick = { onSay(controller.dismissMenu()) })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("MERCH", color = Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp)
                    Text(
                        when (snapshot.phase) {
                            MerchPhase.REVIEW -> "Check your receipt"
                            MerchPhase.AWAITING_PAYMENT -> "Pay with PayPal"
                            MerchPhase.PAID -> "You're all set"
                            else -> "Make a selection"
                        },
                        color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp,
                    )
                }
                if (snapshot.cart.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Total", color = Muted, fontSize = 14.sp)
                        Text(Money.format(snapshot.totalCents), color = Gold, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    }
                }
            }
            if (snapshot.paypalMode != "live") {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (snapshot.paypalMode == "sandbox") "PayPal sandbox · test money only · no real charges"
                    else "Test mode · payments are simulated · no real charges",
                    color = Muted, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (snapshot.phase) {
                    MerchPhase.IDLE, MerchPhase.SHOPPING -> {
                        if (snapshot.cart.isNotEmpty()) {
                            CartBlock(snapshot, removable = true, onRemove = { sku, size -> onSay(controller.removeOne(sku, size)) })
                            KioskButton("That's everything · review order", onClick = { onSay(controller.reviewFromPanel()) })
                            Spacer(Modifier.height(4.dp))
                            Text("Add another shirt", color = Muted, fontSize = 16.sp)
                        } else {
                            Text("Tap a shirt, then a size. You can also just tell me what you'd like.", color = Muted, fontSize = 18.sp, lineHeight = 24.sp)
                        }
                        Catalog(snapshot, onPick = { sku, size -> onSay(controller.addFromPanel(sku, size)) })
                    }
                    MerchPhase.REVIEW -> {
                        ReceiptCard(snapshot, title = "Please check this receipt")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            KioskButton("Looks right", onClick = { onSay(controller.confirmFromPanel()) }, modifier = Modifier.weight(1f))
                            KioskGhost("Change", onClick = { onSay(controller.declineFromPanel()) }, modifier = Modifier.weight(1f))
                        }
                    }
                    MerchPhase.AWAITING_PAYMENT -> {
                        ReceiptCard(snapshot, title = "Total due  ·  pay with PayPal")
                        PaymentBox(snapshot)
                        PayPalPayButton(
                            enabled = snapshot.tapReady && !snapshot.capturing,
                            onClick = {
                                if (!snapshot.tapReady || snapshot.capturing) return@PayPalPayButton
                                val approve = snapshot.approveUrl
                                // Sandbox: open PayPal's approve page here; the status poll captures once approved.
                                if (snapshot.paypalMode == "sandbox" && approve != null) runCatching { uriHandler.openUri(approve) }
                                else controller.capturePayment { onSay(it) }
                            },
                        )
                        Text(
                            when {
                                snapshot.capturing -> "Confirming payment with PayPal…"
                                snapshot.paypalMode == "sandbox" -> "PayPal sandbox · scan the code with your phone, or tap the button to pay here."
                                else -> "Test mode · this PayPal payment is simulated and will not charge anyone."
                            },
                            color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = { controller.cancelOrder(); onSay("Okay, I've cancelled that order.") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) { Text("Cancel order", color = Cream, fontSize = 18.sp) }
                    }
                    MerchPhase.PAID -> {
                        ReceiptCard(snapshot, title = "Paid with PayPal  ·  thank you")
                        KioskButton("Next customer", onClick = { onSay(controller.greet()) })
                    }
                }
                snapshot.ticket?.let { ticket ->
                    HelperTicketCard(ticket, onDone = { controller.markTicketDone() })
                }
                Spacer(Modifier.height(8.dp))
            }
            if (snapshot.phase != MerchPhase.PAID && snapshot.phase != MerchPhase.AWAITING_PAYMENT) {
                TextButton(
                    onClick = { onSay(controller.justLooking()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 4.dp),
                ) { Text("Just looking", color = Cream, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun CloseMenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(2.dp, Cream, CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = "Close menu" },
        contentAlignment = Alignment.Center,
    ) {
        Text("×", color = Cream, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
    }
}

@Composable
private fun KioskButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Ink, disabledContainerColor = CardBg, disabledContentColor = Muted),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

@Composable
private fun KioskGhost(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(2.dp, if (enabled) Cream else Muted.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Cream else Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Catalog(snapshot: MerchSnapshot, onPick: (String, String) -> Unit) {
    val items = snapshot.items.ifEmpty { MerchCatalog.items }
    var openSku by remember { mutableStateOf(snapshot.awaitingSizeFor) }
    LaunchedEffect(snapshot.awaitingSizeFor) { if (snapshot.awaitingSizeFor != null) openSku = snapshot.awaitingSizeFor }
    items.forEach { item ->
        val sizes = item.sizes.filter { (snapshot.stock[MerchCatalog.stockKey(item.sku, it)] ?: 0) > 0 }
        val soldOut = sizes.isEmpty()
        val expanded = openSku == item.sku && !soldOut
        Column(
            Modifier.fillMaxWidth().clip(shape).background(CardBg).border(1.dp, if (expanded) Gold else Color.Transparent, shape).padding(18.dp)
                .clickable(enabled = !soldOut) {
                    when {
                        sizes.size == 1 -> onPick(item.sku, sizes.first())
                        else -> openSku = if (openSku == item.sku) null else item.sku
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.shortName, color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp)
                    if (item.description.isNotBlank()) Text(item.description, color = Muted, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Text(Money.format(item.priceCents), color = Gold, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (soldOut) Text("Sold out", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            else {
                Text(if (expanded) "Tap a size" else ("Sizes  " + sizes.joinToString("   ")), color = Muted, fontSize = 16.sp)
                AnimatedVisibility(visible = expanded) {
                    FlowRow(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        sizes.forEach { size ->
                            Box(
                                Modifier.heightIn(min = 56.dp).widthIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(Gold)
                                    .clickable { onPick(item.sku, size); openSku = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("$size", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartBlock(snapshot: MerchSnapshot, removable: Boolean, onRemove: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(shape).background(CardBg).padding(18.dp)) {
        Text("Your cart", color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        CartLines(snapshot, removable, onRemove)
        Spacer(Modifier.height(8.dp))
        Totals(snapshot)
    }
}

@Composable
private fun CartLines(snapshot: MerchSnapshot, removable: Boolean, onRemove: (String, String) -> Unit = { _, _ -> }) {
    snapshot.cart.forEach { line ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${line.qty} × ${line.name}  ·  ${line.size}", color = Cream, fontSize = 18.sp, modifier = Modifier.weight(1f), lineHeight = 24.sp)
            Text(Money.format(line.lineCents), color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (removable) TextButton(onClick = { onRemove(line.sku, line.size) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Remove", color = Gold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Totals(snapshot: MerchSnapshot) {
    val rate = snapshot.taxRateBasisPoints
    val rateText = (rate / 100).toString() + "." + (rate % 100).toString().padStart(2, '0') + "%"
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TotalRow("Subtotal", Money.format(snapshot.subtotalCents))
        TotalRow(if (rate == 0) "Tax (not set)" else "Tax $rateText", Money.format(snapshot.taxCents))
        TotalRow("Total", Money.format(snapshot.totalCents), bold = true)
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = if (bold) Cream else Muted, modifier = Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = if (bold) 20.sp else 16.sp)
        Text(value, color = if (bold) Gold else Cream, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = if (bold) 22.sp else 16.sp)
    }
}

@Composable
private fun ReceiptCard(snapshot: MerchSnapshot, title: String) {
    val receipt = snapshot.receipt
    Column(Modifier.fillMaxWidth().clip(shape).background(CardBg).border(1.dp, Gold.copy(alpha = 0.45f), shape).padding(18.dp)) {
        Text(title, color = Cream, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        if (receipt != null) Text("Order #${receipt.orderNumber}", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        CartLines(snapshot, removable = false)
        Totals(snapshot)
    }
}

@Composable
private fun PayPalWordmark(fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Pay", color = PayPalNavy, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp)
        Text("Pal", color = PayPalBlue, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = (-0.5).sp)
    }
}

@Composable
private fun PayPalPayButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) PayPalGold else CardBg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PayPalWordmark(22.sp)
            Text("Pay with PayPal", color = if (enabled) PayPalNavy else Muted, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
    }
}

@Composable
private fun PaymentBox(snapshot: MerchSnapshot) {
    Column(Modifier.fillMaxWidth().clip(shape).background(CardBg).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PayPalWordmark(34.sp)
        Text("PayPal checkout", color = Cream, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).border(2.dp, PayPalNavy, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val modules = snapshot.qrModules
                if (modules != null) {
                    QrMatrix(modules, Modifier.size(164.dp).semantics { contentDescription = "PayPal QR code. Scan to pay." })
                } else if (snapshot.qrPayload != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PayPal QR", color = PayPalNavy, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("checkout code", color = PayPalBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text("Preparing\nPayPal code…", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
            Text("Scan this PayPal QR code to check out", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        snapshot.paypalOrderId?.let { Text("PayPal order $it", color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace) }
        snapshot.paymentError?.let { Text(it, color = Color(0xFFFFB4A8), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Draws a QR matrix (rows of '1' dark / '0' light) with a quiet zone. No QR library needed. */
@Composable
private fun QrMatrix(rows: List<String>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val n = rows.size + 8 // 4-module quiet zone each side
        val cell = minOf(size.width, size.height) / n
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '1') drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun HelperTicketCard(ticket: HelperTicket, onDone: () -> Unit) {
    val (bg, label) = when (ticket.status) {
        TicketStatus.GRAB_NOW -> TicketGrab to "HELPER  ·  GRAB THIS"
        TicketStatus.PAID_HAND_OVER -> TicketPaid to "HELPER  ·  PAID, HAND IT OVER"
        TicketStatus.DONE -> TicketDone to "Handed over"
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(bg).padding(16.dp)) {
        Text("$label  ·  order #${ticket.orderNumber}", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        ticket.lines.forEach { line ->
            Text("${line.qty}  ×  ${line.name}  —  size ${line.size}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        }
        if (ticket.status != TicketStatus.DONE) TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Handed over", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
