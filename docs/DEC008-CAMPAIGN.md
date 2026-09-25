# DEC-008 Android local package

This package supplies Android adapters and offline evidence checks for the three-session
campaign. Compilation and local tests are development evidence. They are not device,
FCM, Scheduled-origin, Google projection, or System QA acceptance. BA coordinates all
runtime admission after both developer handoffs and the Scheduled package are accepted.

## Identity, clocks and evidence

Keep the BA campaign index separate from the immutable Backend preparation manifest
and from append-only runtime evidence. Resolve canonical IDs, versions and event IDs
from actual accepted responses. Never invent them for preparation or change the frozen
manifest hash to accommodate later results. A missing fixture or observed binding blocks
the dependent phase. The Backend contract is `finance-qa-campaign.v1`.
Case policy fixes `required_evidence` IDs, not future hashes. Case opening appends actual
evidence hashes and server-verified `bindings`. `GET /qa/cases/{case_id}` is the trusted
runtime readback. Reopening with changed evidence is denied. P4 opens before minute 35,
accepts producer ingest before minute 55, and permits canonical/mobile review before
minute 90. Opening at minute 34 and ingesting at minute 41 is valid. For P5, compare the
current canonical before/after replay; the duplicate response retains its original
ingested version and must not be mistaken for a canonical rollback.

Private B3 configuration adds `campaign_id`, `session_id`, `session_kind`, `manifest_hash`
and `case_id` to the existing verified UID/device/run/account/loopback configuration.
The test transport sends the four `X-QA-*` identity headers. Before mutations and each
fake broker call it reads fresh `/qa/status`; identity, state, UTC deadlines and the
operator-supplied start/end must match. New business stops strictly before minute 90;
original-report completion stops before minute 120. Backend remains the authority for
case admission, source binding, narrower case windows, and effects already admitted.
The harness never prepares, activates, resets, extends, opens, or closes a session.

Preparation is at most 60 minutes. READY starts no execution clock. ACTIVE is exactly
7,200 seconds. Restoration is an operator operation before minute 105. CLOSED/EXPIRED
permit only reads. No local clock offset, reused session, reset claim or alternate
namespace is a recovery mechanism. Historical connector rejections remain blockers
for their exact actions; these tools provide no substitute producer write.

`run-finance-campaign.py --manifest INDEX --output NEW_FILE inspect` validates the
minimum BA index without runtime work. `evaluate --wire-manifest FROZEN.json --packet CASE.json --evidence-root DIR`
can be repeated with multiple `--packet` options in one invocation. Each packet has
`case_id`, `campaign_id`, `session_id`, `manifest_hash`, `server_timestamp` (UTC), `facts`,
`truncated:false`, and `evidence:[{path,sha256}]`. Paths must resolve inside the private
evidence root. Missing facts/evidence yield UNKNOWN; mismatched digests yield FAIL.
Owner-declared facts are not independently authenticated by this offline checker.
QA must verify their correspondence to the hashed evidence and Backend audit.
BLOCKED/NOT_RUN/UNKNOWN remain explicit, never converted to PASS.
`device-phase` additionally requires `--wire-manifest` with the exact immutable Backend
prepare input. Its canonical JSON SHA256 (sorted keys, compact separators, UTF-8,
non-ASCII preserved, no NaN) must match the index/config/server hash. It also verifies
fixed pins and distinct P4/N1/N2 admission/processing gates. The mutable index is never
submitted as an updated preparation manifest.

## Fixed case adapters

| Case | Android adapter and required companion evidence |
|---|---|
| P1/P2 | Scheduled and Backend evidence only; no mobile producer or import replacement |
| P3 | `planning_display`: production Orders card, canonical fields/source/version and unapproved action denial, zero broker calls |
| P4 | `planning_display` on the observed updated intent/version; Backend/Scheduled update evidence plus both P2 and P3 verification before minute 35 |
| P5/P6 | Backend dedupe/CAS evidence; no fabricated Scheduled run |
| N1/N2 | Actual main-app FCM pipeline; read-only `collect-campaign-notification.py` captures received/displayed/opened and explicit OPENED receipt acceptance, plus private log export |
| N3/N4 | Backend/provider/readback plus Android inbox evidence; distinguish logical event rows, provider sends, deliveries and receipt attempts |
| X1 | `concurrent`: verified two-device IDs, durable distinct requests, deterministic barrier; exactly one claim, fake broker invocation/ACK and accepted report, one explicit conflict |
| X2 | `accepted`/`resume_accepted` for lost report ACK, plus independent `unknown` or `kill_unknown`/`resume_unknown` for durable ambiguity and no repeated action/report; both required |
| X3 | `denied`, separately for `wrong_owner`, `unapproved`, `out_of_window`; independent observed fixture IDs |
| X4 | `snapshot` on both devices, then `stale` with original canonical fixture; QA operator switches source while mobile waits; stale snapshot denied before broker |
| X5 | `late` retains a report offline before the source switch; `resume_late` sends the unchanged original-source report and verifies quarantine/readback with zero repeated broker calls |
| X6 | Operator-only restoration/readback/closure evidence with strictly increasing generations; no source-switch button/API in mobile |

For native planning checks, `expected_canonical` contains observed plan/intent/version,
symbol, side, quantity, limit_price_vnd, scheduled_at, authoring_state, execution_state,
and source_context. Keep all business fields from the captured Backend response.
The test asserts actual rendered symbol, quantity, price, version, source ID and
generation; canonical equality also checks the other listed fields in the local data.
It does not claim screenshot coverage of every textual field.

`operator_source_switch` contains `original_source` and `switched_source`, each an
observed/reviewed `{source_id,source_generation}` tuple. Mobile writes a trace marker
`awaiting_qa_operator_source_switch` and waits at most 45 seconds for exact source
readback. The operator must independently perform an admitted source change; mobile
never POSTs `/qa/source`. An unexpected tuple or timeout fails closed. Start X5's
original-source pending report before the shared X4 transition; do not start a new
old-source economic action after the switch. Resume reports under their original case.

The X1 barrier cannot reset after an abort. Recovery decisions belong to BA; no runner
automatically retries a case or an uncertain economic action. The aggregate host result
must match Backend claim/preflight/order/report evidence before X1 is accepted.

## BA review corrections and required facts

Evaluation now requires the exact immutable wire manifest as well as the mutable index.
P1/P4 require all A:U exactly once; V:Z, unknown columns, duplicates and a partial AA:AB
pair fail. AA:AB is required when the pinned proposal contains source_context; otherwise
the pair is optional. Any supplied source metadata must match the pinned source tuple
in facts.source_metadata. The oracle does not accept a caller-selected column allowlist.

P4 resolves expected price/thesis from the hashed p4 fixture, including exact campaign
and session markers. It requires exactly before.version+1, unchanged identity/source/
environment/account/recipient/symbol/side/quantity/scheduled/window/conditions/states,
the intended price/thesis, pinned cash_requirements, consistent principal/fee/required
cash, and Android agreement on those fields. Missing immutable cash/delta input gives
UNKNOWN. Expected values are never copied from observed after-state.

N1 scan and device_reviewed_minute must be before40, N2 before90, and both honor any
narrower frozen case deadline. Review must follow scan. Completion-only receipts may
be accepted by Backend until120, but do not make late notification review PASS.

X2 facts retain the lost-ACK fields and additionally identify fixture_id and intent_id.
The required ambiguity object contains a distinct fixture_id/intent_id (both fixtures
must be in X2's immutable policy), first_pid/restart_pid, journal_before/journal_after
both UNKNOWN, total_broker_calls=1, restart_broker_calls=0, server_report_count=0,
replay_denied=true and evidence_sha256 linked to an actual hashed evidence file.
The final Backend example pins x2 and x2-unknown independently. Missing the ambiguity
subcase prevents full X2 PASS. Each phase uses its own captured canonical ID; no claim
or fixture is reset. Runner configs must name distinct intents.accepted and intents.unknown.
Evidence remains subject to independent QA review, including the replay-denial proof.

## Device and logging boundaries

B3 hosts product UI/repository/Room code with an injected identity and fake broker in
test-only storage. It does not verify Firebase login or MainActivity navigation. FCM
evidence uses the actual signed-in tablet and its existing isolated QA route. The phone
is not registered for notifications merely to run X1. No tool clears app data or login.

Notification capture requires exact installed app/test hashes, verified device UUID,
UID, observed event ID and session start. It does not send FCM, register a token, open
a notification, create a receipt or assert that missing log records never occurred.
Rotated/pruned logs can make evidence incomplete; correlate the exported records with
Backend and UI evidence. A real user/QA opening must precede capture.

For campaign notification routing, the private operator config additionally contains
`campaign:{campaign_id,session_id,session_kind:"NOTIFICATION",manifest_sha256}` and
`event_cases:{OBSERVED_EVENT_UUID:"N1"}` (or N2). After actual artifact acceptance,
copy the event from `GET /qa/cases/N1`/N2; installation independently reads that endpoint
and verifies identity, hash and event binding. Subsequent installs in that campaign may
only append mappings. Install the mapping before the admitted worker sends FCM. Missing
bindings reject the push; FCM data cannot provide or override a case. The cached route
keeps its original campaign/case. Receipt transport checks current lifecycle plus trusted
case binding and sends those original headers. No token or URL is taken from push data.
Legacy QA config remains readable without a campaign, but it is not DEC-008-compatible
and the campaign Backend rejects mutations without the new headers.

Private evidence directories/files use 0700/0600. Production observation logs remain
bounded and allowlisted. B3 traces strip credential/header/free-text fields, cap nesting,
array/string sizes and total file bytes, and mark truncation. Truncated traces cannot
produce an accepted runner result. The real observation sink is enabled for B3.
FLAG_SECURE remains enabled; do not disable it for screenshots.

Runners verify installed hashes, snapshot and restore temporary adb reverse routes,
stop their test flow, and remove only temporary private test configuration. A failed
cleanup is a failure requiring review. Route/config mutation booleans describe code
paths; QA still verifies actual final device state. No automatic cloud upload occurs.

## Compatibility and rollback

No Room schema migration or account/DNSE credential migration is added. New main-app
behavior includes source ID display, campaign-aware debug notification routing and
distinct RECEIVED/OPENED receipt action log labels;
export readers must accept these enum values. Product broker and Backend origins remain
unchanged. Use `adb install -r` only after runtime/install approval; never uninstall the
app or clear its data. Exact app/test/signing pins belong in the dated BA handoff.

Rollback must use the manifest's captured compatible app/test artifacts with matching
signatures after execution is terminal. Preserve login, databases, claims, original
reports and audit. Do not roll back the Backend to bypass an expired or denied case.
