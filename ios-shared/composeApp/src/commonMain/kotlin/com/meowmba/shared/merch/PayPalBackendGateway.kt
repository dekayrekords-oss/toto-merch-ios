package com.meowmba.shared.merch

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Real PayPal SANDBOX payments through the merch backend (`merch-backend/` in this repo).
 *
 * The app never sees the PayPal secret: it sends the confirmed receipt to the backend, which
 * creates the PayPal order and returns the approve link plus a ready-made QR matrix. The till
 * then polls [orderStatus] and calls [captureOrder] once the customer has approved.
 *
 * Every callback is delivered on the main thread, like the stub's, so [MerchTableController]
 * stays single-threaded.
 */
class PayPalBackendGateway(
    settings: PayPalSettings,
    private val client: HttpClient = defaultClient(),
) : PayPalGateway {
    override val mode: String = "sandbox"

    private val base = settings.backendUrl.trim().trimEnd('/')
    private val token = settings.backendToken.trim()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun createOrder(order: MerchOrder, onResult: (PayPalOrderResult) -> Unit) {
        val body = buildJsonObject {
            put("orderNumber", order.orderNumber)
            put("createdAtMs", order.createdAtMs)
            put("subtotalCents", order.subtotalCents)
            put("taxCents", order.taxCents)
            put("totalCents", order.totalCents)
            put("lines", buildJsonArray {
                order.lines.forEach { line ->
                    add(buildJsonObject {
                        put("sku", line.sku); put("name", line.name); put("size", line.size)
                        put("qty", line.qty); put("unitCents", line.unitCents)
                    })
                }
            })
        }
        call(onResult, { PayPalOrderResult(false, error = it) }) {
            val res = client.post("$base/api/orders") {
                auth(); contentType(ContentType.Application.Json); setBody(body.toString())
            }
            val json = res.json()
            if (!res.status.isSuccess()) return@call PayPalOrderResult(false, error = json.error(res))
            val approve = json.string("approveUrl")
            val qr = (json["qr"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { rows -> rows.isNotEmpty() && rows.all { it.length == rows.size } }
            PayPalOrderResult(ok = true, paypalOrderId = json.string("id"), qrPayload = approve, approveUrl = approve, qrModules = qr)
        }
    }

    override fun captureOrder(paypalOrderId: String, onResult: (PayPalCaptureResult) -> Unit) {
        call(onResult, { PayPalCaptureResult(false, error = it) }) {
            val res = client.post("$base/api/orders/${paypalOrderId.encodeURLPathPart()}/capture") { auth() }
            val json = res.json()
            if (!res.status.isSuccess()) return@call PayPalCaptureResult(false, error = json.error(res))
            PayPalCaptureResult(ok = true, captureId = json.string("captureId"))
        }
    }

    override fun orderStatus(paypalOrderId: String, onResult: (PayPalStatusResult) -> Unit) {
        call(onResult, { PayPalStatusResult(false, error = it) }) {
            val res = client.get("$base/api/orders/${paypalOrderId.encodeURLPathPart()}") { auth() }
            val json = res.json()
            if (!res.status.isSuccess()) return@call PayPalStatusResult(false, error = json.error(res))
            PayPalStatusResult(ok = true, status = json.string("status"))
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }

    private fun <T> call(onResult: (T) -> Unit, failure: (String) -> T, block: suspend () -> T) {
        if (base.isEmpty()) { onResult(failure("Set the merch backend URL in Merch table settings")); return }
        scope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure("Can't reach the payment server. Check the backend URL and Wi-Fi.")
            }
            onResult(result)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject =
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.error(res: HttpResponse): String =
        string("error") ?: "Payment server error (${res.status.value})"

    private companion object {
        fun defaultClient() = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 8_000
            }
            expectSuccess = false
        }
    }
}
