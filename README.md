# Toto Merch

Toto Merch is a voice and touch merch till for a phone at a live event. It shows the catalog, helps shoppers select an item and size, displays a receipt for confirmation, and opens a PayPal Sandbox checkout. Staff can use the paid pickup ticket to fulfill the order. The hardware robot is optional for testing the till.

This release covers the iOS till and its PayPal backend. It does not include Android, deployment credentials, private robot services, or runtime model files.

## Set up the checkout server

Use Node.js 20.6 or newer and a PayPal Sandbox REST app. No live payments are needed.

```sh
cd merch-backend
npm ci
npm test
cp .env.example .env
```

Set `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` in `.env` to the Sandbox REST app values. Set a local `MERCH_APP_TOKEN` and enter the same token in the till settings. Leave `PAYPAL_API_BASE` on the Sandbox URL. Then run `npm start`. The server defaults to port 8787. From the same Mac, `curl http://localhost:8787/health` checks that the server is listening.

`npm test` uses an offline PayPal fixture to verify the order creation, status, and capture paths. It does not perform a live or Sandbox transaction.

## Build the iOS till

Use an Apple silicon Mac with Xcode and an iOS SDK, JDK 21, Python 3, and CMake 3.28 or newer. iOS 17 is the minimum deployment target. The pinned setup scripts fetch and generate the native dependencies that are intentionally absent from source control.

```sh
cd ios-shared
./scripts/setup-ios-llama.sh
./scripts/setup-ios-needle.sh
./scripts/setup-ios-sherpa.sh
xcodebuild -project iosApp/Meowmba.xcodeproj -scheme Meowmba \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO build
```

To run on a physical device, open `iosApp/Meowmba.xcodeproj`, choose your development team and a bundle identifier you control, and run the `Meowmba` scheme with your device selected. In the app, enable Merch Table mode. In Merch Table settings, turn off Simulate Payments, select Sandbox, and enter the backend URL and app token. For a phone on the same network, use the Mac’s LAN address in the backend URL.

Add an item, review the receipt, and open the PayPal approval link or scan the QR code on another device that can reach the backend. Sign in with a **Sandbox buyer account**; after approval, verify that the till displays the paid pickup ticket. The tap on the PayPal button opens an approval flow and is not NFC card acceptance.

## PayPal and AI

The backend uses PayPal OAuth and Orders v2. It recomputes receipt totals from the submitted lines, creates a Sandbox order, and provides an approval URL and QR matrix. The till waits for payment confirmation. The PayPal secret remains on the server.

Toto’s general conversation on iOS can use the open Qwen3-0.6B GGUF model with llama.cpp. Select the on-device provider in the app and download the model before trying a general conversation. The merch intent parser handles supported catalog and cart actions locally; the model does not authorize or confirm a charge.

See [third-party notices](THIRD_PARTY_NOTICES.md) and the [MIT license](LICENSE).
