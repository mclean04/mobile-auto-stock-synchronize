# Session revocation fence amendment — 2026-10-02

Applies to the accepted combined-session contract SHA256 `ab58545a094a4092658954710e8d10a2ca53d8328631e14040b546e6ba371fbb`. Existing request/response shapes, route versions and HTTP status semantics are unchanged. This is an additive business error and a narrow persistence guarantee; no new endpoint, token, parameter, session framework or outcome ledger.

## Wire implication for Android acknowledgement through BA

- Both POST `/mobile/v2/session` and legacy PUT `/v1/devices/{device_id}` reject registration for a caller-owned revoked device incarnation with HTTP409 business code **`device_registration_revoked`**.
- Combined POST uses its existing Failure envelope: top-level `code:409`, `data:null`, `error.code:"device_registration_revoked"`, `error.operation.database:"NOT_APPLIED"`, `projection:"NOT_APPLICABLE"`, `committed_steps:[]`, device_id as operation_id, environment:null; `meta.http_status:409`; `X-Request-ID == meta.request_id`.
- Legacy PUT preserves its raw shape: HTTP409 `{"detail":"device_registration_revoked"}`. DELETE keeps HTTP200 `{"device_id":"...","registered":false}` and existing foreign-owner404 behavior.
- This code means the device incarnation is permanently retired for that owner. Do not reset/generate an ID or automatically replay to bypass the conflict. Android already removes its persisted device ID only after confirmed logout; a later deliberate sign-in creates a NEW device incarnation. This is the allowed lifecycle, not a conflict-recovery workaround.
- Fresh-token events for an active incarnation remain allowed; refreshing the token cannot revive a revoked incarnation. No Firebase credential invalidation or global logout is inferred from409.
- Confirmed registration COMMITTED stays confirmed for that request even if a later concurrent logout removes it. It is not a promise that the registration still exists when the response reaches the phone. Android must still discard stale lifecycle responses and wait for confirmed remote revoke before clearing account-local data.

## Persistence and transaction guarantee

Store one durable marker at the existing owner-scoped index subcollection:
`notification_device_owners/{digest(uid-or-operator)}/revoked_devices/{device_id}`,
under the existing configured root owner. Marker payload is ONLY `{"revoked":true}`: no token, token hash, UID, credentials, App Check or other authentication data. No TTL or automatic marker cleanup: expiring the marker would permit delayed requests to recreate the slot.

Every registration path reads marker existence IN the same Firestore transaction that reads ownership/index and writes registration/LRU changes. Existing cross-owner checks remain. A marker rejects before token/capacity mutations.

Confirmed explicit DELETE atomically writes the marker together with index removal and device deletion, including an already-missing record retry. Caller UID selects the index for missing records. A document owned by another UID still returns404 before marker or other writes.

If registration commits first, the later DELETE removes it and writes the fence. If DELETE commits first, a registration using an earlier transaction snapshot conflicts/retries through Firestore transaction handling, then sees the marker and rejects409. Requests reaching registration only after DELETE also reject. Same-owner index/device/marker reads and writes provide the transaction conflict points; no in-process-only cancellation claim.

Capacity LRU eviction is NOT logout and writes no marker. Existing FCM UNREGISTERED cleanup passes the token-match guard and also writes no logout marker. Both can allow a later normal authenticated registration. Revoke called without a token is the explicit logout/removal path; the existing privileged automation deletion mode is unchanged, with marker scope derived from the stored owner when present or operator when absent.

Scope remains the current main checkout; no provider calls, marker migration/backfill, repeat registration reset, cloud planning/orders/history deletion, service/runtime changes or deploy. Persistent marker count grows by confirmed logout incarnations; no broad retention framework is added. Component verification must cover both orderings, delayed POST/legacy PUT after DELETE, missing retry, ownership isolation, LRU non-fencing and worker token-guard behavior. Actual Firestore concurrency remains for separately authorized QA.
