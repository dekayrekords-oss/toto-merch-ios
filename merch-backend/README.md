# Toto Merch PayPal Sandbox Backend (candidate)

This is a reviewable, credential-free candidate export of the PayPal sandbox backend. It validates a confirmed merch receipt, creates a PayPal Orders v2 sandbox order, returns an approval URL and QR matrix, polls status, and captures once. It never targets live PayPal.

## Run the offline verification

Requires Node 20.6+:

```sh
npm install
npm test
```

The test uses a fake PayPal transport and must finish with `ALL PASSED`. No network or credentials are needed.

## Run the sandbox server

```sh
cp .env.example .env
# Fill in sandbox credentials locally; never commit .env.
npm start
```

The app-facing API is documented in `server.js`. For a real sandbox run, use a PayPal developer sandbox personal account and keep `PAYPAL_API_BASE` on `https://api-m.sandbox.paypal.com`.

This folder is only a backend candidate export. The mobile app, public repository metadata, license, hosted demo, and Devpost submission still require separate review.
