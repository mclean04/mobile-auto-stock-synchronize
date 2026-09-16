# API diagnostics (debug builds)

Filter Android Studio Logcat with `package:com.example.finance_planning tag:PlanningApi`.
Every request made through Transport (DNSE and planning backend) has START/END entries
with a process-local ID, service/environment, HTTP method, redacted route, HTTP status,
allowlisted error code, duration and failure category. Firebase/Google SDK internal
HTTP traffic is not intercepted by this logger.

No request/response bodies, query strings, headers, credentials, signatures, tokens,
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