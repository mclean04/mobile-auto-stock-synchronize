# Android integration against backend source

Source inspected: backend_dnse/auto-stock-synchronize/src/planning_backend/firestore_app.py,
schemas.py, notifications.py, docs/authentication.md on 2026-09-16.
Android communicates with Cloud Run; it never connects directly to Firestore or Sheets.

| API | Android use |
| --- | --- |
| GET /health | Settings: check server |
| GET /v1/sync/status | Verify backend authorization and display Sheet status |
| GET /v1/planning/latest | Planning tab, refresh, canonical notification review |
| POST /v1/planning/import | Explicit confirmed action on overview |
| POST /v1/sync/batches | Persistent encrypted Room upload queue, <=100 records |
| GET /v1/sync/batches | Overview, pagination |
| GET /v1/sync/batches/{id} | Batch detail |
| GET /v1/orders | Orders tab, pagination |
| GET /v1/orders/{account}/{order_id} | Exact order detail |
| POST /v1/sheets/reconcile | Retry pending Sheet projection |
| PUT /v1/devices/{id} | Register and refresh FCM token after authorization |
| DELETE /v1/devices/{id} | Disconnect device before local logout |
| GET /v1/notifications | Inbox, pagination, resume recovery |
| GET /v1/notifications/{id} | Canonical details; stale instructions cannot authorize execution |
| GET /v1/notification-plans/{id} | Fetch current plan state when opening notification |
| POST /v1/notifications/{id}/receipts | RECEIVED/OPENED only; never claims execution |
| POST /v1/notifications | Client method exists for operator tooling; not exposed as a trading/user action |
| POST /v1/notification-preview | Client method exists; optional backend diagnostic only |
| POST /internal/sheet-notifications/poll | Scheduler-only; deliberately excluded from mobile |
| POST /v1/local/notification-preview | Local backend console only; deliberately excluded |

## Authentication integration blocker

The inspected production backend has GOOGLE_USER_AUTH_ENABLED=disabled and accepts the
automation bearer token. That token MUST NOT be packaged in this app. The backend currently
does not verify Firebase identity tokens or Firebase App Check. A Firebase sign-in is NOT
proof that the backend has authorized the user.

The app obtains Firebase Auth ID tokens using Google Credential Manager, refreshes them
through Firebase Auth, and sends Authorization plus X-Firebase-AppCheck. Backend integration
must independently verify Firebase ID token signature, exact project audience/issuer,
expiry, authorized UID and revocation policy; validate App Check app ID/expiry; enforce both
on every mobile business request. Do not replace existing automation/Scheduler authentication.
An explicit /v1/auth/session capability endpoint is recommended but not assumed by this app;
the existing authenticated sync/status is used as the authorization check.

Configure the Android Firebase app for package com.example.finance_planning and register
the real signing certificate fingerprints. Supply public project identifiers through
mobile.properties. Enable Google sign-in, configure Play Integrity and the actual authorized
Firebase UID on the backend. A sideloaded debug APK is NOT automatically eligible for Play
Integrity. Use a separately authorized development setup/internal testing distribution.
No token is printed or embedded, and no debug attestation bypass is shipped.

Cloud setup/backend changes are not silently performed in this Android-only project.

## Data and scheduling

DNSE keys and Room payloads are AES-GCM encrypted with an Android Keystore key; backup is
disabled and sensitive screens block screenshots. Firebase manages its own auth persistence.
Each account namespace is scoped by Firebase UID. Logout removes local keys/cache/outbox
after device revocation succeeds, and requires explicit confirmation because pending data
would be lost.

WorkManager runs approximately every six hours with a network constraint. It is not an exact
alarm and cannot overcome force-stop, offline state, Doze or vendor battery restrictions.
FCM carries only a hint. Opening/reloading fetches canonical notification state and planning;
no broker placement or cancellation exists in the app.

The initial DNSE sync covers the last 30 calendar days plus today's NORMAL/STOP stock orders.
It requires real broker update timestamps and explicit price units; it never substitutes
fetch time for an order's source timestamp. API schema drift stops upload. Sandbox data
remains local. Balance/position responses are encrypted locally, not mapped to backend
records until field/unit mapping is independently verified. Executions have a read API
method but are not yet included in automatic upload. Thus full portfolio/fee reconciliation
is not claimed by this version.

An upload keeps the same batch ID and serialized payload until the server confirms
database=committed. HTTP 409/422 parks the batch for review, instead of inventing new IDs.
Sheet projection status is separate from database commit. No trade is inferred from a
notification receipt, and no plan is matched by symbol alone.

## References

- https://developers.dnse.com.vn/docs/guide/intro/authentication/
- https://firebase.google.com/docs/auth/android/google-signin
- https://firebase.google.com/docs/app-check/android/play-integrity-provider
- https://developer.android.com/topic/libraries/architecture/workmanager
