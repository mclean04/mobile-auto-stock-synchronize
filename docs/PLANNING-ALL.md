# Planning All on Android

Delivery date: 2026-09-20. Replaces separate upcoming/history downloads.

## Data and actions

- Only the **Refresh plans / Làm mới kế hoạch** button requests
  `GET /v2/planning/intents?view=all&limit=100`, following each opaque
  `next_cursor` until null. No legacy endpoint fallback or startup fetch.
- Every page must have contract 2.0, the same source tuple, snapshot ID and
  total count. Duplicate item IDs, missing/looping cursors, wrong row source,
  partial results and fetch errors reject the refresh.
- Stage pages in memory, then replace the encrypted, owner-scoped Room
  `planning_all` row in one transaction. Existing data and its saved timestamp
  survive a failed refresh. Old per-tab cache keys are not used.
- All three tabs read that single snapshot. Planning (All), the initial tab,
  and History are read-only. Upcoming uses `scheduled_at >= device_now`;
  History uses `scheduled_at < device_now`. The lifecycle-aware local clock
  updates every second without making list requests.
- Only timezone-qualified EXACT timestamps participate in time filtering.
  DATE_ONLY, INVALID and MISSING rows remain in All with an unknown-time
  explanation. A calendar date never becomes midnight.
- Legacy fields/status are retained for display; their synthetic IDs never
  open detail/preflight. Being in History does not imply execution or a fill.
- The action requires canonical EXACT data in Upcoming, fresh explicit load,
  environment/eligibility/funds checks and final user confirmation. Repository
  checks the tab/time gate at dialog opening, placement start and immediately
  before DNSE. Existing source/detail/preflight, expiry, cash-only/no-margin,
  durable UNKNOWN and immutable reporting protections remain.
- Regular accounts may refresh their own authorized canonical rows. Backend
  controls recipient scope and the admin-only shared legacy rows.
- No Production cancellation or new broker mutation path was added. Raw DEBUG
  logging remains unchanged. Vietnamese and English resources cover new copy.

## Verification

```text
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain
```

- BUILD SUCCESSFUL: 88 unit tests, 17 suites, zero failures/errors/skips.
- Lint: zero errors, 86 warnings (including dependency/resource warnings).
- New unit coverage: multi-page collection, atomic commit seam, failed/partial
  refresh, source/snapshot changes, duplicate IDs, invalid cursors, exact time
  boundary/offsets/nanoseconds, device clock changes, all-state/legacy retention
  and read-only/action gating. Obsolete auto-fetch-on-cache-miss tests removed.
- Samsung SM_X730: `adb install -r` updated the app and the isolated test APK.
  `PlanningAllPersistenceTest` passed both instrumented tests against real Room:
  close/reopen persistence + owner isolation, and failed-page/transaction rollback
  retaining the previous snapshot and timestamp. Fake pages only; no broker calls.
- Controlled force-stop/cold-start: activity started successfully, process 11421
  stayed alive; its debug logs contained 5 GET requests, 0 planning GET requests
  and no fatal exception. This is a startup observation, not a full lifecycle E2E.
- Screenshot-based visual validation was unavailable because the activity sets
  FLAG_SECURE. Two ADB Wi-Fi endpoints identify the same physical tablet; this
  is one-device evidence.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `22422cedcea1444383637a83c24b7335e5233ea8a9ae29c2c1ed49f718cd020c`.

Backend integration and independent acceptance belong to System BA after both
deliveries. No Tester task was called or polled for this increment; no real DNSE
placement/cancellation was performed.
