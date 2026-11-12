package com.meowmba.shared.merch

enum class MerchPhase { IDLE, SHOPPING, REVIEW, AWAITING_PAYMENT, PAID }

data class MerchReceiptLine(val sku: String, val name: String, val size: String, val qty: Int, val unitCents: Int) {
    val lineCents: Int get() = unitCents * qty
}

data class MerchOrder(
    val orderNumber: Int,
    val lines: List<MerchReceiptLine>,
    val subtotalCents: Int,
    val taxCents: Int,
    val taxRateBasisPoints: Int,
    val totalCents: Int,
    val createdAtMs: Long,
)

enum class TicketStatus { GRAB_NOW, PAID_HAND_OVER, DONE }

/** What the human helper sees: grab these shirts. The robot never hands merch over itself. */
data class HelperTicket(val orderNumber: Int, val lines: List<MerchReceiptLine>, val status: TicketStatus)

data class MerchSnapshot(
    val enabled: Boolean,
    val phase: MerchPhase,
    val cart: List<MerchReceiptLine>,
    val subtotalCents: Int,
    val taxCents: Int,
    val totalCents: Int,
    val taxRateBasisPoints: Int,
    /** Non-null only while the receipt for exactly the current cart is on screen. */
    val receipt: MerchOrder?,
    val awaitingSizeFor: String?,
    val paypalMode: String,
    val paypalOrderId: String?,
    val qrPayload: String?,
    /** PayPal approve page for this order (sandbox), null on the stub. */
    val approveUrl: String?,
    /** QR code rows ('1' = dark) for [approveUrl], null on the stub. */
    val qrModules: List<String>?,
    /** True while a capture call is in flight, so the pay button can't fire twice. */
    val capturing: Boolean,
    /** Card tap / QR may only be offered once the receipt + total are on screen and confirmed. */
    val tapReady: Boolean,
    val paymentError: String?,
    val ticket: HelperTicket?,
    val stock: Map<String, Int>,
    val ordersCompleted: Int,
    val lastReply: String?,
    /** Full-screen merch menu over the face. Hidden on idle / just-looking / after a session. */
    val menuVisible: Boolean,
    val justLooking: Boolean,
    val items: List<MerchItem>,
)

/**
 * The merch-table till. Owns inventory, cart, receipt, payment state and the helper ticket.
 * Pure Kotlin stdlib: the same file builds for Android and the shared iOS module.
 *
 * Invariant: [MerchSnapshot.tapReady] and the QR are only set after a receipt for the *current*
 * cart has been shown and the customer confirmed it. Any cart change clears the receipt and
 * payment state.
 */
class MerchTableController(
    private val clock: () -> Long,
    private val storage: MerchStorage? = null,
    var gateway: PayPalGateway = StubPayPalGateway(clock),
    /** Venue sales tax in basis points (e.g. 860 = 8.60%). 0 until the operator sets it. */
    var taxRateBasisPoints: Int = 0,
) {
    var onChange: ((MerchSnapshot) -> Unit)? = null
    /**
     * Called with the exact V14 preset name already used by Toto
     * (`high_reach` or `live_neutral`) via `sendV14Preset`. No-ops if no robot is attached.
     */
    var onArmPreset: ((String) -> Unit)? = null
    /** Optional LOOI expression cue (greet/select/pay). Does not replace [onArmPreset]. */
    var onLooiGesture: ((String) -> Unit)? = null
    var sandboxFactory: PayPalSandboxFactory? = null

    private var enabled = true
    private var phase = MerchPhase.IDLE
    private var menuVisible = false
    private var justLooking = false
    private var lastInteractionMs = 0L
    private var lastWalkUpMs = 0L
    private var paypalSettings = PayPalSettings()
    private val cart = ArrayList<MerchCartLine>()
    private val stock: MutableMap<String, Int> = LinkedHashMap()
    private var receipt: MerchOrder? = null
    private var awaitingSizeFor: String? = null
    private var pendingQty = 1
    private var paypalOrderId: String? = null
    private var qrPayload: String? = null
    private var approveUrl: String? = null
    private var qrModules: List<String>? = null
    private var capturing = false
    private var paymentError: String? = null
    private var ticket: HelperTicket? = null
    private var nextOrderNumber = 1
    private var ordersCompleted = 0
    private var lastReply: String? = null
    private var lastRecommended = 0
    private var lastArmPreset: String? = null

    init {
        val raw = runCatching { storage?.read() }.getOrNull()
        val loaded = MerchMenuCodec.decode(raw)
        if (loaded.items != null) MerchCatalog.setItems(loaded.items)
        else if (raw.isNullOrBlank()) MerchCatalog.resetToDefaults()
        stock.putAll(loaded.stock)
    }

    val snapshot: MerchSnapshot get() = buildSnapshot()

    // ── Customer turns ───────────────────────────────────────────────────────────────

    /** Handle one customer utterance. Returns Meow's reply, or null if this is not a merch turn. */
    fun handle(text: String): String? {
        if (!enabled) return null
        val t = MerchIntentParser.normalize(text)
        if (t in setOf("open merch table", "start merch table", "merch table on")) { enabled = true; return reply(greeting()) }
        if (t in setOf("close merch table", "stop merch table", "merch table off")) { enabled = false; changed(); return "Merch table closed." }
        val intent = MerchIntentParser.parse(text, awaitingConfirm = phase == MerchPhase.REVIEW, awaitingSizeFor = awaitingSizeFor)
            ?: return null
        if (justLooking && intent !is MerchIntent.JustLooking && !reopensMenu(intent)) return null
        return reply(apply(intent))
    }

    /** "New customer" button / person walked up. Starts a fresh order, shows the menu, and raises the arm. */
    fun greet(): String {
        justLooking = false
        menuVisible = true
        if (phase == MerchPhase.PAID || phase == MerchPhase.IDLE) resetOrder(keepTicket = phase == MerchPhase.PAID)
        menuVisible = true
        highReach()
        looiCue("greet")
        return reply(greeting())
    }

    /**
     * Walk-up trigger: a face / person appeared, or the face home became active.
     * Does nothing while the customer said they are just looking, or mid-order.
     */
    fun onPersonArrived(): String? {
        if (!enabled || justLooking) return null
        if (menuVisible && phase != MerchPhase.IDLE && phase != MerchPhase.PAID) return null
        val now = clock()
        if (now - lastWalkUpMs < 12_000L) return null
        lastWalkUpMs = now
        return greet()
    }

    fun justLooking(): String = dismissMenu()

    /**
     * Close the walk-up menu by hand (X) or "just looking". Returns to the idle face,
     * parks the arm at live_neutral, and keeps an in-progress cart so reopening resumes.
     * Mid-payment reservations are released (stock and helper ticket) but the cart stays.
     * A finished paid thank-you screen is ended like [endSession].
     */
    fun dismissMenu(): String {
        if (phase == MerchPhase.PAID) {
            endSession()
            return reply("No problem — I'll be right here if you want to look at a price or pick something out.")
        }
        if (phase == MerchPhase.AWAITING_PAYMENT) {
            receipt?.lines?.forEach { line -> adjustStock(line.sku, line.size, line.qty) }
            persist()
            ticket = null
            receipt = null
            clearPayment()
            phase = MerchPhase.SHOPPING
        }
        if (phase == MerchPhase.IDLE && cart.isEmpty()) {
            // stay idle
        }
        menuVisible = false
        justLooking = true
        armNeutral()
        return reply("No problem — I'll be right here if you want to look at a price or pick something out.")
    }

    /** Hide the menu and return to the idle face after paid / cancelled / timeout. */
    fun endSession() {
        if (phase == MerchPhase.AWAITING_PAYMENT) {
            receipt?.lines?.forEach { line -> adjustStock(line.sku, line.size, line.qty) }
            persist()
        }
        val keep = phase == MerchPhase.PAID
        resetOrder(keepTicket = keep)
        phase = MerchPhase.IDLE
        menuVisible = false
        justLooking = false
        armNeutral()
        changed()
    }

    /** Periodic idle/paid timeout. Returns a spoken line only when something changes that should be said. */
    fun tick(): String? {
        if (!menuVisible) return null
        val now = clock()
        if (phase == MerchPhase.PAID && now - lastInteractionMs > 8_000L) { endSession(); return null }
        if (phase == MerchPhase.SHOPPING && cart.isEmpty() && now - lastInteractionMs > 45_000L) { endSession(); return null }
        if (phase == MerchPhase.IDLE && now - lastInteractionMs > 20_000L) { endSession(); return null }
        return null
    }

    fun setEnabled(on: Boolean) { enabled = on; if (!on) lastArmPreset = null; changed() }

    fun applyPayPalSettings(settings: PayPalSettings) {
        paypalSettings = settings
        gateway = settings.gateway(clock, sandboxFactory)
        changed()
    }

    fun addFromPanel(sku: String, size: String): String = reply(add(sku, size, 1))

    private fun apply(intent: MerchIntent): String = when (intent) {
        MerchIntent.JustLooking -> justLooking()
        MerchIntent.Greet -> openForShopping(greeting())
        MerchIntent.ListStock -> listStock()
        is MerchIntent.Recommend -> recommend(intent.hint)
        is MerchIntent.Price -> price(intent.sku)
        is MerchIntent.SizeHelp -> sizeHelp(intent.sku)
        is MerchIntent.Add -> add(intent.sku ?: awaitingSizeFor ?: singleItemInCartSku(), intent.size, intent.qty)
        is MerchIntent.SizeOnly -> {
            val sku = awaitingSizeFor
            if (sku == null) "Which shirt is that for? We have " + itemNamesSentence() + "."
            else add(sku, intent.size, intent.qty ?: pendingQty)
        }
        is MerchIntent.Remove -> remove(intent.sku, intent.size)
        MerchIntent.ShowCart -> cartSentence()
        MerchIntent.Checkout -> review()
        MerchIntent.Confirm -> confirm()
        MerchIntent.Decline -> { if (phase == MerchPhase.REVIEW) { clearReceipt(); phase = MerchPhase.SHOPPING }; "No problem. What would you like to change?" }
        MerchIntent.CancelOrder -> { cancelOrder(); "Okay, I've cleared the order. Let me know if you want to start again." }
    }

    private fun greeting(): String {
        if (phase == MerchPhase.IDLE) phase = MerchPhase.SHOPPING
        return "Ah! Hello there — I'm Toto! I've got t-shirts today: " + itemNamesSentence() +
            ". Have a look at the menu, and make a selection whenever you're quite ready!"
    }

    private fun reopensMenu(intent: MerchIntent): Boolean = when (intent) {
        is MerchIntent.Greet, is MerchIntent.ListStock, is MerchIntent.Recommend,
        is MerchIntent.Price, is MerchIntent.Add, is MerchIntent.SizeHelp,
        is MerchIntent.Checkout, is MerchIntent.ShowCart -> true
        else -> false
    }

    private fun openForShopping(spoken: String): String {
        justLooking = false
        menuVisible = true
        if (phase == MerchPhase.IDLE || phase == MerchPhase.PAID) {
            if (phase == MerchPhase.PAID) resetOrder(keepTicket = true)
            phase = MerchPhase.SHOPPING
            menuVisible = true
        }
        highReach()
        return spoken
    }

    private fun listStock(): String {
        if (phase == MerchPhase.IDLE) phase = MerchPhase.SHOPPING
        menuVisible = true
        justLooking = false
        highReach()
        val parts = MerchCatalog.items.mapNotNull { item ->
            val sizes = item.sizes.filter { stockOf(item.sku, it) > 0 }
            if (sizes.isEmpty()) null else item.shortName + " " + Money.spoken(item.priceCents) + " in " + sizes.joinToString(", ")
        }
        if (parts.isEmpty()) return "Oh! I'm afraid we're completely sold out right now."
        return "Here's what's in stock: " + parts.joinToString("; ") + ". It's all on the screen too!"
    }

    private fun recommend(hint: String): String {
        if (phase == MerchPhase.IDLE) phase = MerchPhase.SHOPPING
        menuVisible = true
        justLooking = false
        highReach()
        val inStock = MerchCatalog.items.filter { item -> item.sizes.any { stockOf(item.sku, it) > 0 } }
        if (inStock.isEmpty()) return "Oh dear — we're sold out!"
        val direct = MerchIntentParser.findItem(hint)?.let { MerchCatalog.bySku(it) }?.takeIf { it in inStock }
        val pick = direct
            ?: inStock.firstOrNull { item -> (hint.contains("kid") || hint.contains("child") || hint.contains("youth")) && item.sku == "MEOW-TEE-KID" }
            ?: inStock.firstOrNull { item -> (hint.contains("funny") || hint.contains("joke")) && item.sku == "TOTO-TEE-GRY" }
            ?: inStock.firstOrNull { item -> (hint.contains("light") || hint.contains("white") || hint.contains("bright")) && item.sku == "LUMEN-TEE-WHT" }
            ?: inStock[(lastRecommended++) % inStock.size]
        awaitingSizeFor = pick.sku
        val sizes = pick.sizes.filter { stockOf(pick.sku, it) > 0 }
        return "I'd go with " + pick.pitch + ". It's " + Money.spoken(pick.priceCents) + ". What size? I have " + sizes.joinToString(", ") + "!"
    }

    private fun price(sku: String?): String {
        menuVisible = true
        justLooking = false
        highReach()
        val item = sku?.let { MerchCatalog.bySku(it) }
        if (item == null) return "Prices: " + MerchCatalog.items.joinToString("; ") { it.shortName + " " + Money.spoken(it.priceCents) } + "."
        awaitingSizeFor = item.sku
        return "The " + item.shortName + " is " + Money.spoken(item.priceCents) + ". Want one? What size?"
    }

    private fun sizeHelp(sku: String?): String {
        val item = sku?.let { MerchCatalog.bySku(it) } ?: awaitingSizeFor?.let { MerchCatalog.bySku(it) }
        val sizes = item?.sizes ?: MerchCatalog.ADULT_SIZES
        return "They're a regular unisex fit. If you're between sizes, go up one. Sizes are " + sizes.joinToString(", ") +
            ". My helper can hold one up for you if you like."
    }

    private fun add(sku: String?, size: String?, qty: Int): String {
        if (phase == MerchPhase.AWAITING_PAYMENT) return "Your payment is already on screen. Say cancel order if you want to change it."
        if (phase == MerchPhase.PAID || phase == MerchPhase.IDLE) resetOrder(keepTicket = phase == MerchPhase.PAID)
        phase = MerchPhase.SHOPPING
        menuVisible = true
        justLooking = false
        val item = sku?.let { MerchCatalog.bySku(it) }
            ?: return "Which one would you like? We have " + itemNamesSentence() + "."
        if (size == null) {
            awaitingSizeFor = item.sku; pendingQty = qty
            val sizes = item.sizes.filter { stockOf(item.sku, it) > 0 }
            if (sizes.isEmpty()) { awaitingSizeFor = null; return "Sorry — the " + item.shortName + " is sold out!" }
            return "Splendid choice! What size " + item.shortName + "? I have " + sizes.joinToString(", ") + "!"
        }
        if (size !in item.sizes) {
            awaitingSizeFor = item.sku
            return "The " + item.shortName + " comes in " + item.sizes.joinToString(", ") + ". Which one?"
        }
        val inCart = cart.filter { it.sku == item.sku && it.size == size }.sumOf { it.qty }
        val available = stockOf(item.sku, size) - inCart
        if (available <= 0) {
            val others = item.sizes.filter { stockOf(item.sku, it) - cartQty(item.sku, it) > 0 }
            awaitingSizeFor = item.sku
            return "Sorry, I'm out of " + size + " in the " + item.shortName + "." +
                (if (others.isNotEmpty()) " I still have " + others.joinToString(", ") + "." else "")
        }
        val n = qty.coerceIn(1, available)
        val idx = cart.indexOfFirst { it.sku == item.sku && it.size == size }
        if (idx >= 0) cart[idx] = cart[idx].copy(qty = cart[idx].qty + n) else cart.add(MerchCartLine(item.sku, size, n))
        awaitingSizeFor = null; pendingQty = 1
        clearReceipt()
        armNeutral()
        looiCue("select")
        val limited = if (n < qty) " I only had $n left in that size." else ""
        return "Added " + n + " " + item.shortName + " in " + size + "." + limited + " Your total so far is " +
            Money.spoken(totals().third) + ". Anything else, or is that it?"
    }

    /** Panel "−" button. */
    fun removeOne(sku: String, size: String): String = reply(remove(sku, size))

    /** Panel buttons route through the same steps as voice so replies stay consistent. */
    fun reviewFromPanel(): String = reply(review())
    fun confirmFromPanel(): String = reply(confirm())
    fun declineFromPanel(): String = reply(apply(MerchIntent.Decline))

    private fun remove(sku: String?, size: String?): String {
        if (phase == MerchPhase.AWAITING_PAYMENT) return "Your payment is already on screen. Say cancel order to start over."
        if (cart.isEmpty()) return "Your cart is empty."
        val target = cart.lastOrNull { (sku == null || it.sku == sku) && (size == null || it.size == size) }
            ?: return "I don't see that in your cart. " + cartSentence()
        val idx = cart.indexOf(target)
        if (target.qty > 1) cart[idx] = target.copy(qty = target.qty - 1) else cart.removeAt(idx)
        clearReceipt()
        if (phase == MerchPhase.REVIEW) phase = MerchPhase.SHOPPING
        val name = MerchCatalog.bySku(target.sku)?.shortName ?: target.sku
        return "Removed one " + name + " in " + target.size + ". " + cartSentence()
    }

    private fun cartSentence(): String {
        if (cart.isEmpty()) return "Your cart is empty. " + "We have " + itemNamesSentence() + "."
        val lines = receiptLines().joinToString(", ") { "${it.qty} ${MerchCatalog.bySku(it.sku)?.shortName} ${it.size}" }
        return "You have " + lines + ". Total " + Money.spoken(totals().third) + "."
    }

    /** Step 1 of paying: put the itemized receipt and total on screen and ask the customer to confirm. */
    fun review(): String {
        if (cart.isEmpty()) return "Your cart is empty. What would you like?"
        if (phase == MerchPhase.AWAITING_PAYMENT) return "Your total is on screen. Pay with PayPal, or scan the PayPal QR code, right here!"
        val lines = receiptLines()
        val (sub, tax, total) = totals(lines)
        receipt = MerchOrder(nextOrderNumber, lines, sub, tax, taxRateBasisPoints, total, clock())
        phase = MerchPhase.REVIEW
        clearPayment()
        menuVisible = true
        highReach()
        looiCue("pay")
        return "Here's your receipt on the screen. " + cartSentence() + " Does that look right? Say yes to pay, or tell me what to change."
    }

    /** Step 2: customer confirmed the on-screen receipt. Create the PayPal order and the helper ticket. */
    fun confirm(): String {
        val shown = receipt
        if (phase != MerchPhase.REVIEW || shown == null) {
            return if (cart.isEmpty()) "Nothing to pay for yet." else review()
        }
        if (shown.lines != receiptLines()) { clearReceipt(); return review() }
        // Reserve stock now so two customers can't buy the last shirt; released on cancel.
        shown.lines.forEach { line -> adjustStock(line.sku, line.size, -line.qty) }
        persist()
        ticket = HelperTicket(shown.orderNumber, shown.lines, TicketStatus.GRAB_NOW)
        phase = MerchPhase.AWAITING_PAYMENT
        paymentError = null
        menuVisible = true
        highReach()
        looiCue("pay")
        gateway.createOrder(shown) { result ->
            if (receipt?.orderNumber != shown.orderNumber || phase != MerchPhase.AWAITING_PAYMENT) return@createOrder
            if (result.ok) {
                paypalOrderId = result.paypalOrderId; qrPayload = result.qrPayload
                approveUrl = result.approveUrl; qrModules = result.qrModules; paymentError = null
            }
            else paymentError = result.error ?: "Could not start the payment"
            changed()
        }
        return "Thanks! Your total of " + Money.spoken(shown.totalCents) + " is on screen. Pay with PayPal, or scan the PayPal QR code, right here! " +
            "My helper is grabbing your shirt now."
    }

    /** Called by the payment layer (or the test-only "Simulate sandbox payment" button). */
    fun capturePayment(onDone: ((String) -> Unit)? = null) {
        val id = paypalOrderId
        if (phase != MerchPhase.AWAITING_PAYMENT || id == null || receipt == null) { onDone?.invoke("No payment is waiting."); return }
        if (capturing) return // a capture for this order is already in flight; never charge twice
        capturing = true
        changed()
        gateway.captureOrder(id) { result ->
            capturing = false
            if (phase != MerchPhase.AWAITING_PAYMENT || paypalOrderId != id) { changed(); return@captureOrder }
            val msg = if (result.ok) {
                phase = MerchPhase.PAID
                ordersCompleted += 1
                nextOrderNumber += 1
                paymentError = null
                ticket = ticket?.copy(status = TicketStatus.PAID_HAND_OVER)
                "Payment received — thank you! Marvellous. My helper will hand you your shirt. Enjoy!"
            } else {
                paymentError = result.error ?: "Payment failed"
                "That payment didn't go through. Want to try again?"
            }
            reply(msg)
            onDone?.invoke(msg)
        }
    }

    /**
     * Poll PayPal (through the gateway) while waiting. When the customer has approved on their
     * own phone, capture and finish the order. Safe to call repeatedly; it does nothing on the stub.
     */
    fun checkPayment(onDone: ((String) -> Unit)? = null) {
        val id = paypalOrderId
        if (phase != MerchPhase.AWAITING_PAYMENT || id == null || capturing || gateway.mode == "stub") return
        gateway.orderStatus(id) { result ->
            if (phase != MerchPhase.AWAITING_PAYMENT || paypalOrderId != id) return@orderStatus
            when (result.status) {
                "APPROVED", "COMPLETED" -> capturePayment(onDone)
                "VOIDED" -> { paymentError = "PayPal closed this payment. Cancel and try again."; changed() }
            }
        }
    }

    /** Helper tapped "Handed over". */
    fun markTicketDone() { ticket = ticket?.copy(status = TicketStatus.DONE); changed() }

    fun cancelOrder() {
        if (phase == MerchPhase.AWAITING_PAYMENT) {
            receipt?.lines?.forEach { line -> adjustStock(line.sku, line.size, line.qty) }
            persist()
            ticket = null
        }
        resetOrder(keepTicket = phase == MerchPhase.PAID)
        phase = MerchPhase.IDLE
        menuVisible = false
        justLooking = false
        armNeutral()
        changed()
    }

    // ── Operator ─────────────────────────────────────────────────────────────────────

    fun restock(sku: String, size: String, qty: Int) {
        val key = MerchCatalog.stockKey(sku, size)
        if (MerchCatalog.bySku(sku) == null || size.isBlank()) return
        stock[key] = qty.coerceAtLeast(0); persist(); changed()
    }

    fun upsertItem(item: MerchItem, stockBySize: Map<String, Int> = emptyMap()) {
        val next = item.normalized()
        val previous = MerchCatalog.bySku(next.sku)
        MerchCatalog.upsert(next)
        next.sizes.forEach { size ->
            val key = MerchCatalog.stockKey(next.sku, size)
            stock[key] = (stockBySize[size] ?: stock[key] ?: MerchCatalog.DEFAULT_STOCK_PER_SIZE).coerceAtLeast(0)
        }
        if (previous != null) {
            previous.sizes.filter { it !in next.sizes }.forEach { stock.remove(MerchCatalog.stockKey(next.sku, it)) }
        }
        persist(); changed()
    }

    fun removeItem(sku: String) {
        MerchCatalog.remove(sku)
        stock.keys.filter { it.startsWith("$sku|") }.toList().forEach { stock.remove(it) }
        persist(); changed()
    }

    fun resetMenuToDefaults() {
        MerchCatalog.resetToDefaults()
        val next = MerchCatalog.defaultStock()
        next.keys.forEach { key -> if (key in stock) next[key] = stock[key]!! }
        stock.clear(); stock.putAll(next)
        persist(); changed()
    }

    // ── Internals ────────────────────────────────────────────────────────────────────

    private fun resetOrder(keepTicket: Boolean) {
        cart.clear(); receipt = null; awaitingSizeFor = null; pendingQty = 1
        clearPayment()
        if (!keepTicket) ticket = null
        phase = MerchPhase.IDLE
    }

    private fun clearPayment() {
        paypalOrderId = null; qrPayload = null; approveUrl = null; qrModules = null; paymentError = null; capturing = false
    }

    private fun clearReceipt() {
        receipt = null; clearPayment()
        if (phase == MerchPhase.REVIEW) phase = MerchPhase.SHOPPING
    }

    private fun singleItemInCartSku(): String? = cart.map { it.sku }.distinct().singleOrNull()
    private fun cartQty(sku: String, size: String) = cart.filter { it.sku == sku && it.size == size }.sumOf { it.qty }
    private fun stockOf(sku: String, size: String) = stock[MerchCatalog.stockKey(sku, size)] ?: 0
    private fun adjustStock(sku: String, size: String, delta: Int) {
        val key = MerchCatalog.stockKey(sku, size)
        stock[key] = ((stock[key] ?: 0) + delta).coerceAtLeast(0)
    }
    private fun persist() { runCatching { storage?.write(MerchMenuCodec.encode(MerchCatalog.items, stock)) } }

    private fun itemNamesSentence(): String {
        val names = MerchCatalog.items.map { it.shortName }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private fun receiptLines(): List<MerchReceiptLine> = cart.mapNotNull { line ->
        val item = MerchCatalog.bySku(line.sku) ?: return@mapNotNull null
        MerchReceiptLine(item.sku, item.name, line.size, line.qty, item.priceCents)
    }

    private fun totals(lines: List<MerchReceiptLine> = receiptLines()): Triple<Int, Int, Int> {
        val sub = lines.sumOf { it.lineCents }
        // Round half up to the cent (truncating under-collected tax by up to a cent per order).
        val tax = ((sub.toLong() * taxRateBasisPoints + 5_000L) / 10_000L).toInt()
        return Triple(sub, tax, sub + tax)
    }

    private fun setArmPreset(preset: String) {
        if (lastArmPreset == preset) return
        lastArmPreset = preset
        onArmPreset?.invoke(preset)
    }
    private fun highReach() = setArmPreset("high_reach")
    private fun armNeutral() = setArmPreset("live_neutral")
    private fun looiCue(kind: String) { onLooiGesture?.invoke(kind) }

    private fun reply(text: String): String {
        lastReply = text
        lastInteractionMs = clock()
        changed()
        return text
    }
    private fun changed() { onChange?.invoke(buildSnapshot()) }

    private fun buildSnapshot(): MerchSnapshot {
        // Build the priced cart once per snapshot (it was rebuilt three times per change before).
        val lines = receiptLines()
        val (sub, tax, total) = totals(lines)
        val r = receipt
        val receiptMatches = r != null && r.lines == lines
        return MerchSnapshot(
            enabled = enabled, phase = phase, cart = lines, subtotalCents = sub, taxCents = tax, totalCents = total,
            taxRateBasisPoints = taxRateBasisPoints, receipt = if (receiptMatches) r else null, awaitingSizeFor = awaitingSizeFor,
            paypalMode = gateway.mode, paypalOrderId = paypalOrderId, qrPayload = qrPayload,
            approveUrl = approveUrl, qrModules = qrModules, capturing = capturing,
            tapReady = receiptMatches && phase == MerchPhase.AWAITING_PAYMENT && paypalOrderId != null,
            paymentError = paymentError, ticket = ticket, stock = LinkedHashMap(stock), ordersCompleted = ordersCompleted,
            lastReply = lastReply, menuVisible = menuVisible, justLooking = justLooking,
            items = MerchCatalog.items,
        )
    }
}
