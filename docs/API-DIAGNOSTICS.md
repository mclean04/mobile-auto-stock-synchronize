# HTTP logs

App diagnostics use DEBUG / PlanningApp. API logs use WARN / OkHttp (debug builds
only), including successful requests. Filter:
`package:com.example.finance_planning tag:OkHttp level:WARN`

Layout matches the requested OkHttp BODY example:

    --> GET https://openapi.dnse.com.vn/accounts
    x-api-key: ██
    x-signature: ██
    accept: application/json
    --> END GET
    <-- 200 https://openapi.dnse.com.vn/accounts (50ms)
    content-type: application/json

    {"accounts":[{"id":"<redacted>"}]}
    <-- END HTTP (...-byte body; redacted)

POST/PUT body JSON appears after request headers and before END METHOD. GET query
parameters remain in the URL, never a fabricated GET body. Backend Transport uses
the same layout. No REQUEST JSON / RESPONSE JSON envelope, correlation suffix or
JSON file is emitted. Long JSON lines are chunked for Android Logcat limits.

DNSE uses Retrofit 2.11.0 / OkHttp 4.12.0. Its interceptor peeks at responses
without consuming the body. Header values use an allowlist; Authorization, keys,
signatures, App Check, cookies and unknown header values are masked. Body values
remain redacted. Unknown fields, non-JSON and previews over 8 KiB are omitted;
arrays are limited to 3 examples. DNSE URLs expose account/order IDs as explicitly
requested, but only safe documented query values are logged. Never share logs
without checking their account/order identifiers.

Redirects/retries disabled, connect 20s/read 40s/call 60s, response cap 4 MiB.
Production financial behavior and DNSE signing are unchanged by logging.
Compose BOM 2026.08.00 contains the repeated frame-rate log fix.
Stock cash maps from stock.availableCash in VND, not totalCash or buying power.
