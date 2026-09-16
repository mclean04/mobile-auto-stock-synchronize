# API diagnostics (debug builds)

Filter Android Studio Logcat with `package:com.example.finance_planning tag:PlanningApi`.
DNSE uses Retrofit 2.11.0 with OkHttp 4.12.0; the planning backend retains Transport.
Both emit DEBUG-level START/END entries
with a process-local ID, service/environment, HTTP method, redacted route, HTTP status,
allowlisted error code, duration and failure category. Firebase/Google SDK internal
HTTP traffic is not intercepted by this logger.

No raw request/response bodies, query strings, headers, credentials, signatures, tokens,
account IDs, order IDs, source UIDs or raw exception messages are logged. Unknown
routes are entirely redacted. Logs are disabled in release builds.

`device_minus_server_seconds` is device time minus the DNSE response Date header,
measured when response headers arrive. It is approximate (network delay and Date
header precision), not a clock correction. Unknown means no parseable server Date.
DNSE requires request Date within one minute of its clock.

An invalid_api_key/OA-401 response means the key is not accepted for the logged
environment; it does not distinguish a revoked key from a wrong environment.
An invalid_signature/invalid_authorization response requires checking the matching
secret and clock. FORBIDDEN/OA-403 concerns access permission.
Unknown server messages are deliberately not displayed or logged.

References checked 2026-09-16:
- https://developers.dnse.com.vn/docs/guide/intro/authentication/
- https://developers.dnse.com.vn/docs/guide/error_codes/

The existing signing algorithm matches DNSE's current documented HMAC-SHA256,
UTF-8 secret, path without query, date, fresh UUID hex nonce, Base64 plus URL encoding.
No signing change was made without evidence of a defect.
## Compose frame-rate noise

Compose BOM 2026.08.00 selects UI 1.12.0, which includes the upstream fix that
only calls setRequestedFrameRate when the value changes (including NaN-aware
comparison). The previous UI 1.10.4 called it on every draw, flooding Samsung
Logcat. Frame-rate category values and NaN are framework hints, not DNSE errors.
A few entries on actual frame-rate changes may still be emitted by Android.

For a quieter Android Studio view while keeping API activity and warnings:
`package:com.example.finance_planning & (tag:PlanningApi | level:WARN)`

HTTP 401 must remain visible: it means an API rejected authentication.
The old `HTTP 401 code=unclassified` entry was produced by the earlier build;
current requests include the sanitized service, route and error classification.

Upstream fix:
https://android.googlesource.com/platform/frameworks/support/+/59e21467962fe232cad57ca257cd1715b86ba9b4

## Stock balance response

DNSE /accounts/{accountNo}/balances returns separate stock/derivative/bond/egg
objects. Cash maps from stock.availableCash (VND), not stock.totalCash (which also
includes interest and unsettled amounts). The configurable security price unit
must not multiply this nested VND cash amount. Missing purchasing power is null;
availableCash is not substituted for purchasing power. Missing/malformed stock
cash stops the batch instead of inventing a zero balance.

Reference: https://developers.dnse.com.vn/docs/dnse/get-account-balances/
Verified using the official response schema and value-free device field types.


## Retrofit + OkHttp BODY interceptor

Filter `package:com.example.finance_planning tag:PlanningApi level:DEBUG`.
DNSE GET requests log request_body=<empty>. SafeBodyLoggingInterceptor logs a
redacted JSON body preview: known field names and object/array structure remain;
scalar values and unknown field names are redacted. Non-JSON, deeply nested or
bodies larger than 8 KiB are omitted, with at most 3 array entries and 6 levels.
This is a custom BODY interceptor, not unredacted HttpLoggingInterceptor.Level.BODY.
It never consumes the response delivered to Retrofit. Release builds do not add
this logging interceptor. Backend metadata logging also uses DEBUG, without bodies.

HTTPS host validation, disabled redirects and automatic connection retries,
20s connect / 40s read timeout, and a 4 MiB response cap remain in place.
A 60s call timeout also bounds DNSE calls. Retrofit error-body buffering is capped
by a separate response-size interceptor in debug and release builds.
The existing DNSE signing algorithm and endpoint/query behavior are unchanged.
