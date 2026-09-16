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

## Authentication integration

Production keeps automation-token and Scheduler identities separate from mobile identity.
Mobile requests carry a Firebase Auth ID token in Authorization and a Firebase App Check
token in X-Firebase-AppCheck. Cloud Run verifies both with Firebase Admin, restricts the
email to the configured owner and binds that Firebase UID to the owner namespace. The App
Check subject must exactly match the registered Android Firebase App ID.

The Android app for com.example.finance_planning has Firebase configuration plus debug
SHA-1/SHA-256 certificates. Release builds use Play Integrity; debug builds use Firebase's
debug provider and require the device-generated debug token to be allow-listed before phone
testing. Google Sign-In must be enabled once in Firebase Console because OAuth clients cannot
be created or modified programmatically. No automation token is packaged in the APK.

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

The DNSE sync covers the last 30 calendar days plus today's NORMAL/STOP stock orders,
executions for filled NORMAL orders, current positions and current balance snapshots. Orders
and executions retain broker timestamps; position/balance updated_at is the explicit device
observation time because those endpoints return snapshots. Decimal values are converted with
the configured unit multiplier. Fee aliases are mapped when present and remain null when DNSE
does not provide a fee; the app never estimates one. API schema drift stops upload and sandbox
data remains local.

An upload keeps the same batch ID and serialized payload until the server confirms
database=committed. HTTP 409/422 parks the batch for review, instead of inventing new IDs.
Sheet projection status is separate from database commit. No trade is inferred from a
notification receipt, and no plan is matched by symbol alone.

## References

- https://developers.dnse.com.vn/docs/guide/intro/authentication/
- https://firebase.google.com/docs/auth/android/google-signin
- https://firebase.google.com/docs/app-check/android/play-integrity-provider
- https://developer.android.com/topic/libraries/architecture/workmanager
