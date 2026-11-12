// Meow Merch Table — PayPal SANDBOX backend.
//
// The phone app never holds the PayPal secret. It sends the confirmed receipt here;
// this server creates a PayPal Orders v2 order, hands back the approve link plus a QR
// code, reports the order status while the till waits, and captures the payment.
//
// Sandbox only: there is deliberately no live switch in this test fork.
import express from "express";
import QRCode from "qrcode";
import { pathToFileURL } from "node:url";

const MAX_LINES = 20;
const MAX_QTY = 20;

export function createApp(env = process.env, fetchImpl = globalThis.fetch) {
  const {
    PAYPAL_CLIENT_ID,
    PAYPAL_CLIENT_SECRET,
    PAYPAL_API_BASE = "https://api-m.sandbox.paypal.com",
    PUBLIC_BASE_URL = "",
    MERCH_APP_TOKEN = "",
    CURRENCY = "USD",
    BRAND_NAME = "Meow Merch Table",
  } = env;
  const MAX_ORDER_CENTS = parseInt(env.MAX_ORDER_CENTS || "50000", 10);
  if (!PAYPAL_CLIENT_ID || !PAYPAL_CLIENT_SECRET) {
    throw new Error("Missing PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET. Copy .env.example to .env and fill it in.");
  }
  if (/api-m\.paypal\.com/.test(PAYPAL_API_BASE)) {
    throw new Error("This test backend is sandbox only. PAYPAL_API_BASE must not point at live PayPal.");
  }

  let cachedToken = null;
  async function accessToken() {
    if (cachedToken && cachedToken.expires > Date.now() + 60_000) return cachedToken.value;
    const res = await fetchImpl(`${PAYPAL_API_BASE}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: "Basic " + Buffer.from(`${PAYPAL_CLIENT_ID}:${PAYPAL_CLIENT_SECRET}`).toString("base64"),
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: "grant_type=client_credentials",
    });
    if (!res.ok) throw httpError(502, `PayPal sign-in failed (${res.status}). Check the sandbox Client ID and Secret.`);
    const data = await res.json();
    cachedToken = { value: data.access_token, expires: Date.now() + (data.expires_in || 300) * 1000 };
    return cachedToken.value;
  }

  async function paypal(method, path, body, extraHeaders = {}) {
    const res = await fetchImpl(`${PAYPAL_API_BASE}${path}`, {
      method,
      headers: { Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json", ...extraHeaders },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const issue = data?.details?.[0]?.issue || data?.name || `HTTP ${res.status}`;
      throw Object.assign(httpError(res.status === 422 ? 409 : 502, `PayPal: ${issue}`), { data, issue });
    }
    return data;
  }

  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: "32kb" }));

  // Optional shared token so strangers on the Wi-Fi can't create orders.
  function requireApp(req, res, next) {
    if (!MERCH_APP_TOKEN) return next();
    if (req.get("authorization") === `Bearer ${MERCH_APP_TOKEN}`) return next();
    res.status(401).json({ error: "Missing or wrong app token" });
  }

  app.get("/health", (_req, res) => res.json({ ok: true, mode: "sandbox" }));

  app.post("/api/orders", requireApp, async (req, res, next) => {
    try {
      const order = validateOrder(req.body, { MAX_ORDER_CENTS });
      const base = PUBLIC_BASE_URL || `${req.protocol}://${req.get("host")}`;
      const value = (cents) => ({ currency_code: CURRENCY, value: (cents / 100).toFixed(2) });
      const payload = {
        intent: "CAPTURE",
        purchase_units: [{
          reference_id: `merch-${order.orderNumber}`,
          description: `${BRAND_NAME} order #${order.orderNumber}`,
          items: order.lines.map((l) => ({
            name: `${l.name} (${l.size})`.slice(0, 127),
            sku: `${l.sku}-${l.size}`.slice(0, 127),
            quantity: String(l.qty),
            unit_amount: value(l.unitCents),
            category: "PHYSICAL_GOODS",
          })),
          amount: {
            ...value(order.totalCents),
            breakdown: { item_total: value(order.subtotalCents), tax_total: value(order.taxCents) },
          },
        }],
        payment_source: {
          paypal: {
            experience_context: {
              brand_name: BRAND_NAME,
              shipping_preference: "NO_SHIPPING",
              user_action: "PAY_NOW",
              return_url: `${base}/paid`,
              cancel_url: `${base}/cancelled`,
            },
          },
        },
      };
      const created = await paypal("POST", "/v2/checkout/orders", payload, {
        // Same receipt retried → same PayPal order, no duplicates.
        "PayPal-Request-Id": `merch-${order.orderNumber}-${order.createdAtMs}`,
      });
      const approveUrl = created.links?.find((l) => l.rel === "payer-action" || l.rel === "approve")?.href;
      if (!approveUrl) throw httpError(502, "PayPal did not return an approve link");
      res.json({ id: created.id, status: created.status, approveUrl, qr: qrRows(approveUrl), totalCents: order.totalCents });
    } catch (e) { next(e); }
  });

  app.get("/api/orders/:id", requireApp, async (req, res, next) => {
    try {
      const o = await paypal("GET", `/v2/checkout/orders/${encodeURIComponent(req.params.id)}`);
      res.json({ id: o.id, status: o.status, captureId: captureOf(o)?.id || null });
    } catch (e) { next(e); }
  });

  async function captureOnce(id) {
    try {
      const r = await paypal("POST", `/v2/checkout/orders/${encodeURIComponent(id)}/capture`, undefined, {
        "PayPal-Request-Id": `capture-${id}`,
      });
      return r;
    } catch (e) {
      // Already captured (e.g. by the customer's return page): report the existing capture.
      if (e.issue === "ORDER_ALREADY_CAPTURED") return paypal("GET", `/v2/checkout/orders/${encodeURIComponent(id)}`);
      throw e;
    }
  }

  app.post("/api/orders/:id/capture", requireApp, async (req, res, next) => {
    try {
      const r = await captureOnce(req.params.id);
      const cap = captureOf(r);
      if (r.status !== "COMPLETED" || !cap || cap.status === "DECLINED" || cap.status === "FAILED") {
        return res.status(402).json({ error: `Payment not completed (${cap?.status || r.status})` });
      }
      res.json({ id: r.id, status: r.status, captureId: cap.id, captureStatus: cap.status });
    } catch (e) { next(e); }
  });

  // Where PayPal sends the customer's phone after they approve. Capturing here too means the
  // customer sees "paid" right away; the till's capture call is idempotent and just confirms it.
  app.get("/paid", async (req, res) => {
    const id = String(req.query.token || "");
    let ok = false;
    try { ok = !!id && captureOf(await captureOnce(id))?.status === "COMPLETED"; } catch { ok = false; }
    res.type("html").send(page(ok ? "Payment received — thank you!" : "We couldn't confirm that payment.",
      ok ? "Show this screen at the merch table and the helper will hand over your order." : "Please check with the helper at the table."));
  });
  app.get("/cancelled", (_req, res) => res.type("html").send(page("Payment cancelled", "Nothing was charged. You can scan the code again at the table.")));

  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    const status = err.status || 500;
    if (status >= 500) console.error(err.data || err.message);
    res.status(status).json({ error: err.message || "Server error" });
  });
  return app;
}

export function validateOrder(body, { MAX_ORDER_CENTS = 50000 } = {}) {
  const bad = (m) => { throw httpError(400, m); };
  if (!body || typeof body !== "object") bad("Body must be JSON");
  const orderNumber = int(body.orderNumber);
  const createdAtMs = int(body.createdAtMs);
  if (orderNumber == null || orderNumber < 1) bad("orderNumber is required");
  if (!Array.isArray(body.lines) || body.lines.length === 0) bad("Order has no lines");
  if (body.lines.length > MAX_LINES) bad("Too many lines");
  const lines = body.lines.map((l, i) => {
    const qty = int(l?.qty), unitCents = int(l?.unitCents);
    const sku = str(l?.sku, 40), size = str(l?.size, 8), name = str(l?.name, 80);
    if (!sku || !size || !name) bad(`Line ${i + 1} needs sku, size and name`);
    if (qty == null || qty < 1 || qty > MAX_QTY) bad(`Line ${i + 1} has a bad quantity`);
    if (unitCents == null || unitCents < 1 || unitCents > MAX_ORDER_CENTS) bad(`Line ${i + 1} has a bad price`);
    return { sku, size, name, qty, unitCents };
  });
  // Totals are recomputed here; the app's numbers must agree or the order is refused.
  const subtotalCents = lines.reduce((s, l) => s + l.qty * l.unitCents, 0);
  const taxCents = int(body.taxCents) ?? 0;
  if (taxCents < 0 || taxCents > subtotalCents) bad("Bad tax amount");
  const totalCents = subtotalCents + taxCents;
  if (body.subtotalCents != null && int(body.subtotalCents) !== subtotalCents) bad("Subtotal does not match the lines");
  if (body.totalCents != null && int(body.totalCents) !== totalCents) bad("Total does not match the lines");
  if (totalCents > MAX_ORDER_CENTS) bad("Order is over the table's limit");
  return { orderNumber, createdAtMs: createdAtMs ?? 0, lines, subtotalCents, taxCents, totalCents };
}

function qrRows(text) {
  const { size, data } = QRCode.create(text, { errorCorrectionLevel: "M" }).modules;
  const rows = [];
  for (let y = 0; y < size; y++) {
    let row = "";
    for (let x = 0; x < size; x++) row += data[y * size + x] ? "1" : "0";
    rows.push(row);
  }
  return rows;
}

const captureOf = (o) => o?.purchase_units?.[0]?.payments?.captures?.[0];
const int = (v) => (Number.isInteger(v) ? v : typeof v === "string" && /^\d+$/.test(v) ? parseInt(v, 10) : null);
const str = (v, max) => (typeof v === "string" ? v.trim().slice(0, max) : "");
function httpError(status, message) { return Object.assign(new Error(message), { status }); }
function page(title, body) {
  const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><body style="font-family:system-ui,sans-serif;background:#0F1B33;color:#F7F6F2;display:grid;place-items:center;min-height:100vh;margin:0;padding:24px;text-align:center">
<div><h1 style="font-size:28px">${esc(title)}</h1><p style="font-size:18px;color:#C9D3E6">${esc(body)}</p><p style="font-size:14px;color:#8A9BB8">PayPal sandbox · test money only</p></div></body>`;
}

if (import.meta.url === pathToFileURL(process.argv[1] || "").href) {
  const port = parseInt(process.env.PORT || "8787", 10);
  createApp().listen(port, "0.0.0.0", () => console.log(`Merch PayPal sandbox backend on :${port}`));
}
