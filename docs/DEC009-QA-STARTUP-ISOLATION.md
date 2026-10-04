# DEC009 application startup isolation

Build the reviewed QA pair with `-PqaStartupIsolation=true`. This debug-only compile
selection closes application business networking before Application construction;
release/default debug builds remain ordinary unless a private selection exists.
The flag is verified separately from the shared unit tests, which also run under
default debug. This is the real target Application, not a substitute harness.

`PlanningApp.attachBaseContext` reads `files/qa-startup-admission.json` before any
ContentProvider. Presence selects isolation even on an ordinary fixed build;
unreadable/malformed files and symbolic links remain closed. Absence on a compiled
QA build also remains closed. The file is read-only during startup and cached for
the process lifetime. A trusted operator must stage it while the OLD target is
force-stopped, BEFORE main APK installation, because package-replacement callbacks
may start the new process. An already-running ordinary process is not retroactively
isolated. Do not run old target instrumentation to stage this selection.

## Private admission contract

Use an app-private regular file, max16KiB, owned/readable by the app, with exactly
these top-level fields. Substitute only independently verified current pins:

```json
{
  "schema_version": "finance-qa-startup-admission.v1",
  "mode": "QA_NOTIFICATION",
  "firebase_project_id": "auto-stock-synchronization",
  "target_uid": "<verified Firebase UID>",
  "target_device_id": "<verified existing device UUID>",
  "notification_namespace": "<reviewed QA namespace>",
  "campaign": {
    "campaign_id": "<frozen campaign>",
    "session_id": "<frozen notification session>",
    "manifest_sha256": "<frozen manifest SHA256>",
    "session_kind": "NOTIFICATION"
  }
}
```

No bearer/token/URL belongs in this file. Preserve its original bytes or verified
absence and all prior encrypted QA route and registration-marker values privately.
The bearer stays in the established private QA config installation flow. That
config must match admission UID, device UUID, namespace and campaign exactly and
the existing signed-in UID/device; invalid or legacy unbound configs cannot fall
back to production. Existing event-case binding validation remains mandatory.

FirebaseInitProvider is disabled in all builds. Existing explicit Firebase
initialization now runs only after admission: ordinary initialization keeps its
configured behavior; isolated mode permits only the reviewed project with valid
admission. Firebase/Auth/AppCheck/FCM infrastructure may contact its service.
Their SDK registration/persistence is distinct from app Backend registration.
No IAM or platform security change is part of this patch.

## Network and state behavior

- Default Backend `Transport`, DNSE read transport and DNSE trading's separate
  OkHttp client reject isolated requests before their network clients/sockets.
  Sign-in/out, credential edits, sync, planning refresh, pending batch/report retry,
  device UUID creation and broker-action entry points also reject before mutation.
- Exact loopback `127.0.0.1:18766` notification/control routes retain the existing
  allowlist and campaign lifecycle checks. Every exchange validates private
  admission. Wrong device/event/receipt target is rejected before even status GET.
  Firebase identity headers cannot be repurposed for default business calls.
- `onNewToken` performs no app registration or work enqueue in QA. Registration
  is one explicit operator action after admission/configuration. Production push
  registration and backend-approval markers are preserved; QA has a separate marker.
- Recovered ordinary sync, console and refresh jobs return a local retry before
  repository/network access. They are not canceled/deleted. WorkManager retry and
  backoff bookkeeping may change; the WorkManager database is not byte-immutable.
  QA event receipts retain the existing cache/display/logging path, without a full
  inbox refresh after each receipt and without automatic network-failure retries.
  Failed QA work is failure, not fabricated delivery success; QA inspects it.
- Only the explicit B3 in-memory fake allows broker/repository test execution.
  Its debug non-production client has a terminal interceptor denying fall-through
  before DNS. This is not a general real Sandbox or DNSE exemption.
- Saved user login, credentials, pending order/report journals and ordinary queues
  are preserved. Existing secret-safe logging and FLAG_SECURE stay in place.

## Bounded request accounting

Count every HTTP exchange, including control-plane reads, against the existing
shared session ceiling of 200; orchestration must include its own calls and both
devices. No registration retry worker exists. Per device/operator action:

| Action | App QA HTTP exchanges |
|---|---:|
| Private config install or session verification | 1 status + one per bound event |
| Explicit register | 1 status + 1 device PUT |
| Fetch admitted event | 1 status + 1 event GET |
| Receipt | 1 status + 1 original-case GET + 1 receipt POST |
| Worker fetch + receipt | at most 5, no automatic HTTP retry |
| Explicit inbox refresh | 2 per page; operator must budget pages/actions |
| Metadata-only smoke / token callback / deferred ordinary job | 0 app QA or business HTTP |

Firebase SDK infrastructure is outside this HTTP table; do not claim the entire
process is offline. Multiple pushes/taps/manual retries remain separate actions
and must be budgeted; the app does not replace the server/orchestrator quota gate.

## QA installation and restoration sequence

Only System QA installs/launches after BA reconciles both developer handoffs.
First preserve prior private config/selection/markers, installed app/test artifacts
and their hashes. Keep the old main force-stopped. Stage admission with the
existing shell/run-as private-file route without launching the app. Then install
the reviewed compatible main/test pair as updates, never uninstall/clear data.
Verify hashes/signers and `qa_startup_isolated=true`,
`qa_startup_admission_valid=true`, expected UID/device and approval via bounded
metadata smoke. Preserve any prior metadata file before a helper overwrites it.
The config-install helper removes its staging file; preserve preexisting bytes.
Then explicitly install the matching private route and register once when the
server lifecycle permits. Absent route remains closed, including before this step.

Do not use the historical unsafe smoke pair. Missing/invalid admission can prove
local closed mode but cannot prove signed-in notification readiness. No automatic
queue replay, registration loop, schedule activation or real trade is authorized.

Return to ordinary mode only after terminal QA audit and explicit reviewed private
configuration restoration. Force-stop the target first, restore exact previous
route/markers and selection bytes or absence, then install the explicitly reviewed
ordinary artifact as an update while still stopped. A compiled QA build cannot be
made ordinary just by deleting the marker. An old rollback APK does not understand
the marker at all: never reinstall it before terminal QA and full configuration
restoration. Reopening ordinary mode may resume its preserved original queues;
this is intentional only at that approved restoration step. Record hashes and
restoration evidence without printing secrets. No device restoration is performed
by the local build/handoff itself.
