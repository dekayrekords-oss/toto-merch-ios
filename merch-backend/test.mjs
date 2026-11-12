// Offline test: runs the backend against a fake PayPal so no keys or network are needed.
import assert from "node:assert/strict";
import { createApp, validateOrder } from "./server.js";

const calls = [];
let orderStatus = "PAYER_ACTION_REQUIRED";
let captured = false;
async function fakePayPal(url, init = {}) {
  const path = new URL(url).pathname;
  calls.push(`${init.method || "GET"} ${path}`);
  const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  if (path === "/v1/oauth2/token") return json(200, { access_token: "T", expires_in: 3600 });
  if (path === "/v2/checkout/orders" && init.method === "POST") {
    const body = JSON.parse(init.body);
    assert.equal(body.purchase_units[0].amount.value, "56.15");
    assert.equal(body.purchase_units[0].amount.breakdown.item_total.value, "53.00");
    assert.equal(body.purchase_units[0].amount.breakdown.tax_total.value, "3.15");
    assert.ok(init.headers["PayPal-Request-Id"]);
    return json(201, { id: "ORDER1", status: "PAYER_ACTION_REQUIRED", links: [{ rel: "payer-action", href: "https://www.sandbox.paypal.com/checkoutnow?token=ORDER1" }] });
  }
  const cap = { purchase_units: [{ payments: { captures: [{ id: "CAP1", status: "COMPLETED" }] } }] };
  if (path === "/v2/checkout/orders/ORDER1/capture") {
    if (captured) return json(422, { name: "UNPROCESSABLE_ENTITY", details: [{ issue: "ORDER_ALREADY_CAPTURED" }] });
    if (orderStatus !== "APPROVED") return json(422, { name: "UNPROCESSABLE_ENTITY", details: [{ issue: "ORDER_NOT_APPROVED" }] });
    captured = true; orderStatus = "COMPLETED";
    return json(201, { id: "ORDER1", status: "COMPLETED", ...cap });
  }
  if (path === "/v2/checkout/orders/ORDER1") return json(200, { id: "ORDER1", status: orderStatus, ...(captured ? cap : {}) });
  return json(404, { name: "NOT_FOUND" });
}

const app = createApp({ PAYPAL_CLIENT_ID: "id", PAYPAL_CLIENT_SECRET: "secret", PAYPAL_API_BASE: "https://fake.paypal.test", MERCH_APP_TOKEN: "tok" }, fakePayPal);
const server = app.listen(0);
await new Promise((resolve) => server.once("listening", resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const h = { "Content-Type": "application/json", Authorization: "Bearer tok" };
const order = {
  orderNumber: 3, createdAtMs: 1700000000000, taxCents: 315, subtotalCents: 5300, totalCents: 5615,
  lines: [{ sku: "TOTO-TEE-GRY", name: "Toto Crew", size: "M", qty: 1, unitCents: 2500 }, { sku: "LUMEN-TEE-WHT", name: "Lumen Glow", size: "XL", qty: 1, unitCents: 2800 }],
};
try {
  let r = await fetch(`${base}/api/orders`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(order) });
  assert.equal(r.status, 401, "token required");

  r = await fetch(`${base}/api/orders`, { method: "POST", headers: h, body: JSON.stringify({ ...order, totalCents: 1 }) });
  assert.equal(r.status, 400, "mismatched total refused");

  r = await fetch(`${base}/api/orders`, { method: "POST", headers: h, body: JSON.stringify(order) });
  const created = await r.json();
  assert.equal(r.status, 200, JSON.stringify(created));
  assert.equal(created.id, "ORDER1");
  assert.match(created.approveUrl, /sandbox\.paypal\.com/);
  assert.ok(created.qr.length >= 21 && created.qr.every((row) => row.length === created.qr.length && /^[01]+$/.test(row)), "QR matrix");

  r = await fetch(`${base}/api/orders/ORDER1`, { headers: h });
  assert.equal((await r.json()).status, "PAYER_ACTION_REQUIRED");

  r = await fetch(`${base}/api/orders/ORDER1/capture`, { method: "POST", headers: h });
  assert.equal(r.status, 409, "capture before approval refused");

  orderStatus = "APPROVED";
  r = await fetch(`${base}/paid?token=ORDER1`);
  assert.match(await r.text(), /Payment received/);

  r = await fetch(`${base}/api/orders/ORDER1/capture`, { method: "POST", headers: h });
  const cap = await r.json();
  assert.equal(r.status, 200, JSON.stringify(cap));
  assert.equal(cap.captureId, "CAP1", "second capture is idempotent");

  assert.throws(() => validateOrder({ orderNumber: 1, lines: [{ sku: "A", name: "A", size: "M", qty: 1, unitCents: 60000 }] }), /bad price|limit/);
  assert.throws(() => createApp({ PAYPAL_CLIENT_ID: "a", PAYPAL_CLIENT_SECRET: "b", PAYPAL_API_BASE: "https://api-m.paypal.com" }), /sandbox only/);
  console.log("ALL PASSED", calls.length, "fake PayPal calls");
} finally {
  server.close();
}
