# DNSE shared transport

The local integration was directly authorized by the user after the initial tool
rejection. Tool review then permitted the change. This covers the DNSE transport
slice of the accepted Android–Backend ADR, not completion of the entire Backend
architecture or activation of `/mobile/v1`. No persisted-login rewrite is included.

## Ownership and request flow

`NetworkClients.application` owns the active immutable `DnseSlot`. Compatibility
constructors for `DnseApi` and `DnseTradingApi` obtain that same slot; both services
are created from its one Retrofit/OkHttp pair. A slot contains no credentials,
trading token, account ID, UID or mutable planning source. Explicit slot injection
is also supported. Injected OkHttp clients remain a separate component-test seam.

Repository sessions bind their existing UID/approval/credential checks through
`withRequestContext` or `checkContext`. Signing stays in the DNSE adapter with the
original lower-case method, exact path, date and fresh nonce. The typed endpoint
adds an immutable request tag; the transport rechecks it at asynchronous dispatch.
Session exceptions are converted to IOException at that boundary so OkHttp can
return a failure instead of throwing an uncaught worker-thread exception.

The fixed `DnseReadEndpoints` and `DnseTradeEndpoints` interfaces cover seven read
and ten trading-context operations. DNSE's existing object/list variants remain
bounded response bodies decoded by the existing adapters; this change does not
invent a Backend-style response envelope or rewrite domain normalization. The
compatibility read-URL adapter accepts only known DNSE origins and dispatches
validated paths to those fixed methods. There is no product `@Url` DNSE service.

## Replacement and completion

A different requested environment retires the old slot, cancels its calls and
creates a new immutable slot. Saving/deleting credentials or selecting an
environment explicitly invalidates the active slot. An old wrapper fails closed;
it cannot silently reroute to the new environment. Opening the dedicated Sandbox
session also selects a Sandbox slot and retires an active Production slot.

Read results must pass the owning session and active-slot checks before returning.
Account/credential changes therefore prevent dispatch or publication under the
old context. This slice does not add a new Firebase account/logout listener; the
existing session checks provide that rejection. Login persistence remains outside
scope. Existing planning source checks and broker/preflight journal logic remain
their current owners' responsibility.

A successful mutation acknowledgement is returned to its original journal even
if its session changed after dispatch. Cancellation or a missing acknowledgement
does not establish broker rollback. Existing UNKNOWN handling remains in place;
neither path automatically resends the economic action.

## Transport protections

The Retrofit method's declaring interface selects policy, including for the same
`GET /accounts` path used by both adapters:

| Policy | Read | Trading context |
|---|---|---|
| Connect/read/call timeout | 20/40/60 seconds | 20/40/60 seconds |
| Response bound | 4 MiB; 4096-byte error interpretation | 64 KiB |
| Logging | DEBUG method, sanitized route, status/timing and allowlisted error code only; no raw headers/bodies/URLs | No read logger; optional redacted Sandbox diagnostics |
| Error buffering | Bounded before Retrofit buffers | Omitted without diagnostics; bounded with diagnostics |
| Redirect/auth followup/retry | Disabled | Disabled, including mutation replay |

Production cannot enable Sandbox diagnostics. QA startup denial remains enforced.
The explicit debug-Sandbox fake-only seam has a terminal denial before DNS if a
fake interceptor falls through. Fake clients never authorize a real broker call.

## Maintenance and local checks

Add an endpoint to the appropriate fixed service and its bounded adapter mapping;
do not select the logging policy from user input or a mutable global flag. Keep
the signed path identical to the final encoded request path. Bind the owning
session check, retain result ownership, and add a focused fake-broker case for
changed wire or safety behavior.

Run from the repository root:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash gradlew \
  :app:testDebugUnitTest \
  --tests 'com.example.finance_planning.DnseSharedClientTest' \
  --tests 'com.example.finance_planning.DnseTradingTest' \
  --tests 'com.example.finance_planning.DnseHttpTest' \
  --tests 'com.example.finance_planning.QaStartupIsolationTest' \
  --tests 'com.example.finance_planning.RetrofitHttpTest' \
  --offline --console=plain
```

2026-10-01 result: **32 tests passed, zero failures/errors/skips**. Nine new shared
transport tests cover all 17 endpoint bindings and signatures, instance reuse,
environment replacement, account/credential context, dispatch recheck, late-read
rejection, acknowledged mutation retention, logging separation, byte limits and
in-flight cancellation without replay. Existing cases cover redaction, no replay,
QA startup/fake isolation and local HTTP cancellation/redirect behavior.

All broker requests terminated in memory; HTTP fixtures used local MockWebServer.
No real trades, Cloud deployment, device installation or login rewrite occurred.
This is component evidence, not device/Cloud E2E or whole-ADR acceptance.
