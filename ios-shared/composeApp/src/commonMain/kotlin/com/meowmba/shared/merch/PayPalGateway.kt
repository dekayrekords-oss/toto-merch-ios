package com.meowmba.shared.merch

/**
 * PayPal boundary for the merch table (TEST fork).
 *
 * Keys may be entered in Settings and stored locally for later use. The client
 * secret belongs on a backend; this app stores it only so it can be handed to
 * that backend later, and never logs it.
 *
 * While [PayPalSettings.simulate] is on (the default), the till always uses the
 * offline stub (order ids start with "STUB-") and never calls PayPal, even if
 * keys are filled in. Live PayPal is never called from this test app.
 *
 * The sandbox path ([PayPalBackendGateway]) talks to the merch backend at
 * [PayPalSettings.backendUrl] (Orders v2 create/status/capture, see merch-backend/) only when
 * simulate is off, env is sandbox, and a backend URL is set.
 * Callbacks keep this file stdlib-only so it compiles unchanged on Android and iOS.
 */
interface PayPalGateway {
    /** "stub" (no network), "sandbox" (backend in sandbox). Never "live" in this fork. */
    val mode: String
    fun createOrder(order: MerchOrder, onResult: (PayPalOrderResult) -> Unit)
    fun captureOrder(paypalOrderId: String, onResult: (PayPalCaptureResult) -> Unit)

    /**
     * Where the order stands at PayPal ("PAYER_ACTION_REQUIRED", "APPROVED", "COMPLETED", ...).
     * The till polls this while it waits so a customer who pays on their own phone is noticed.
     * The stub never reports approval by itself: it is paid only by the test button.
     */
    fun orderStatus(paypalOrderId: String, onResult: (PayPalStatusResult) -> Unit) {
        onResult(PayPalStatusResult(ok = true, status = "CREATED"))
    }
}

data class PayPalOrderResult(
    val ok: Boolean,
    val paypalOrderId: String? = null,
    val qrPayload: String? = null,
    /** PayPal page where the customer approves; also what the QR code encodes. */
    val approveUrl: String? = null,
    /** QR code as rows of '1' (dark) / '0' (light), made by the backend so the app needs no QR library. */
    val qrModules: List<String>? = null,
    val error: String? = null,
)

data class PayPalStatusResult(val ok: Boolean, val status: String? = null, val error: String? = null)

data class PayPalCaptureResult(val ok: Boolean, val captureId: String? = null, val error: String? = null)

data class PayPalSettings(
    val env: String = "sandbox",
    val clientId: String = "",
    val clientSecret: String = "",
    val backendUrl: String = "",
    val simulate: Boolean = true,
    /** Optional shared token the merch backend checks (MERCH_APP_TOKEN). Not a PayPal credential. */
    val backendToken: String = "",
) {
    /** The PayPal secret lives on the backend, so the app only needs the backend address. */
    val sandboxReady: Boolean
        get() = env.equals("sandbox", ignoreCase = true) && backendUrl.isNotBlank()

    /** Never print credentials: data-class toString would otherwise put them in logs. */
    override fun toString(): String =
        "PayPalSettings(env=$env, clientId=${if (clientId.isBlank()) "unset" else "set"}, " +
            "clientSecret=${if (clientSecret.isBlank()) "unset" else "set"}, backendUrl=$backendUrl, simulate=$simulate, " +
            "backendToken=${if (backendToken.isBlank()) "unset" else "set"})"

    /** True when the till must stay on the offline stub and must not touch PayPal. */
    val useStub: Boolean
        get() = simulate || !env.equals("sandbox", ignoreCase = true) || !sandboxReady
}

/** Optional platform hook that can talk to the merch sandbox backend. Never used for live. */
fun interface PayPalSandboxFactory {
    fun create(settings: PayPalSettings): PayPalGateway?
}

fun PayPalSettings.gateway(clock: () -> Long, sandboxFactory: PayPalSandboxFactory? = null): PayPalGateway {
    if (useStub) return StubPayPalGateway(clock)
    return sandboxFactory?.create(this) ?: StubPayPalGateway(clock)
}

/**
 * Offline stub used whenever simulate is on, live is selected, or sandbox config is
 * missing. It never touches the network and never moves money.
 */
class StubPayPalGateway(private val clock: () -> Long) : PayPalGateway {
    override val mode: String = "stub"
    private var counter = 0

    override fun createOrder(order: MerchOrder, onResult: (PayPalOrderResult) -> Unit) {
        counter += 1
        val id = "STUB-" + clock().toString(36).uppercase() + "-" + counter
        onResult(PayPalOrderResult(ok = true, paypalOrderId = id,
            qrPayload = "paypal-sandbox-placeholder://order/$id?amount=" + Money.format(order.totalCents).drop(1)))
    }

    override fun captureOrder(paypalOrderId: String, onResult: (PayPalCaptureResult) -> Unit) {
        if (!paypalOrderId.startsWith("STUB-")) { onResult(PayPalCaptureResult(false, error = "Stub can only capture stub orders")); return }
        onResult(PayPalCaptureResult(ok = true, captureId = "CAPTURE-$paypalOrderId"))
    }
}
