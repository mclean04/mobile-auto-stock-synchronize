# B3 on-device HTTP/business-flow harness

## Scope and isolation

`B3DeviceFlowTest` hosts the production `Orders` and `ManualTradeDialog`
composables on a ComponentActivity with FLAG_SECURE. The dialog calls the real
`PlanningRepository.ManualTradeSession`, including cash/package checks,
confirmation, mutex, UNKNOWN persistence and placed-report queue. All parsing,
All pagination, time filtering, preflight/source guard, report building, ACK
validation and retry execute the production implementation.

Small dependency seams retain the existing production defaults:

- MobileIdentity.uid and Transport.request can be overridden in the test APK.
- PlanningRepository accepts a manual broker factory whose default is unchanged.
- Orders accepts its existing repository and refresh callback separately.
- Report retry and Orders are internal for instrumentation access; cards carry
  a test tag so tests can identify the intended card without changing its text.

Everything else below lives only in androidTest:

- Fake identity, dummy DNSE credentials/OTP and a broker OkHttp interceptor that
  **never calls chain.proceed** and rejects Production hosts.
- HTTP mapping restricted to the configured backend host and v2 planning/report
  paths, then sent to `http://127.0.0.1:PORT` through adb reverse. Raw loopback
  sockets avoid changing the product TLS/cleartext manifest or base URL.
- A real Room database `b3-RUN.db`, preferences `b3-RUN-*` and trace files
  `files/b3-RUN/`. Vault uses real Android Keystore encryption. Product
  planning.db, saved login and DNSE keys are not changed.

This is not Firebase sign-in/FCM coverage, a MainActivity navigation test, or a
real DNSE trade. The root refresh callback and initial ScreenState are test wiring;
the production Orders lifecycle and actual trade dialog/repository execute.
Separate instrumentation phases prove recovery after process restart.
kill_unknown intentionally kills the target process inside the fake broker
after the production UNKNOWN write returns, before any broker response.
That checkpoint plus resume_unknown covers process death at this specific
boundary; it does not simulate an arbitrary physical power failure.

## Backend service configuration

Supply a local JSON file; do not commit the token. Example structure:

```json
{
  "run_id": "unique-b3-run",
  "evidence_kind": "native_qa_http",
  "uid": "qa-user",
  "account": "qa-account",
  "base_url": "http://127.0.0.1:8899",
  "token": "test-service-only-bearer",
  "auth_header": "X-Planning-Authorization",
  "intents": {"accepted": "UUID", "late": "UUID", "stale": "UUID", "unknown": "UUID"},
  "switch": {"path": "/test/source/switch", "body": {}},
  "readback_path": "/test/readback"
}
```

The service must call actual Backend handlers and use an allowlisted Google QA
source. Intents must be canonical sandbox APPROVED/NOT_STARTED for this UID and
account, scheduled in the future but within an already-open execution window.
The existing production source/expiry/cash checks are not bypassed.

The service uses JSON responses (Content-Length or chunked) and a local test bearer. Switch
and readback paths above are examples: use the concrete Backend handoff.
The readback endpoint must identify native Google QA rows, not a fake writer.

## Build and run

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
python3 tools/run-b3-device.py --config /private/tmp/b3-config.json --transport-id 7 --phase accepted --output /private/tmp/b3-evidence --install
python3 tools/run-b3-device.py --config /private/tmp/b3-config.json --transport-id 7 --phase resume_accepted --output /private/tmp/b3-evidence
```

The runner installs with -r only, checks the installed app/test APK SHA-256
against local artifacts, forwards the test-service port and writes configuration
through stdin without exposing its bearer in argv. Each phase runs in a fresh
instrumentation process. It exports test output, JSONL trace and a hash/PID
summary. Both JUnit success and a final trace PASS are required, except for
kill_unknown: it requires the explicit durable-marker crash checkpoint and
must be followed by a passing resume_unknown in a new process.

## Cases

| Phase | Checks |
|---|---|
| accepted | Explicit UI refresh; All/History read-only; tab switches do not request planning; closing after review/OTP makes no marker/broker call; double tap confirm reaches fake broker once after UNKNOWN; actual HTTP200 ACK deliberately dropped after response, leaving a durable PENDING report |
| resume_accepted | Fresh process restores local data; tabs and Activity recreation make no list requests; actual production retry posts byte-identical payload, validates HTTP ACK, marks REPORTED without broker retry; native QA readback captured |
| unknown / resume_unknown | Fake broker timeout leaves UNKNOWN; after fresh process the real session sees that marker, without another broker call |
| kill_unknown / resume_unknown | Intentional process kill inside fake broker after durable UNKNOWN, before response; after restart the real session retains UNKNOWN and does not retry broker |
| stale | Test service switches source after valid preflight/UNKNOWN and before final active-source read; production guard blocks broker |
| late / resume_late | Report transport offline before send, durable original-source payload; switch A→B; fresh process sends the original payload and validates quarantine ACK, with native QA readback and zero broker retries |

Use separately seeded service runs for source-switch cases when necessary; never
silently retarget an old payload or reset an operational source. Previous unit and
Room/bridge evidence is reused; it does not substitute for these device runs.

Execution results and exact final artifacts are recorded in the delivery handoff
after the Backend test service is connected. A compiled harness alone is not PASS.

Current local smoke and outstanding native dependency are recorded in
`/Users/tuanh/finance root/Planning/Sprint-1-android-b3-delivery.md`;
traces are under `Test/android-b3-device-20260920/`. Native B3 is not yet complete.

On Samsung multiwindow, ActivityScenario.moveToState(CREATED) was not reliable
(the Activity remained RESUMED). The harness checks recreation in the resume
phases instead. It does not claim a successful forced stop/resume UI test from
that failing attempt. The initial HTTP/UI smoke uses a local fake HTTP service
and is explicitly not native Cloud Run/Google QA acceptance.
