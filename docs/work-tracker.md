# Finance Android work tracker

## System Sprint 2 — Android mới, Stock API và Notifications

**PLANNED — NOT STARTED.** Current owner action: Finance Android publishes only the documentation package accepted by BA on 2026-10-04, from the CTO-approved PO roadmap v2026-10-04.7 as relayed in the BA-scoped handoff. No private PO folder access/export. The [current roadmap](roadmap.md) records the full prospective requirements and supersedes earlier local-only, Telegram-only/eligible-Signal-only, no-scheduled-prediction and old-navigation directions. Historical Backend sprint numbering and System Sprint 1 status are unchanged; this is not a System Sprint 1 closure.

Baseline: current Android branch `codex/mobile-api-integration`, published UI `af11e7149834be2f7029cec77baf55b89e0e9eb5`; remote.py implementation `a2347a2` and guidance `89d262c` supplied by BA. Preserve their bounded evidence, not an assumed compatible Sprint 2 runtime. Pin future Backend/API, Android source/main/test APK, candidate route/audience and environment versions when execution is separately authorized. The TEST model must be visibly TEST.

| Item | Status | Owner / dependency | Acceptance gate and next action after separate start authorization |
| --- | --- | --- | --- |
| S2-01 — Mobile Stock contract | PLANNED — NOT STARTED | Backend first; Android reviews DTO, auth, paging/correlation, errors and lifecycle through BA. | Backend publishes concrete typed contract and readiness; accept the operation/object mapping before dependent Android integration. |
| S2-02 — Android design | PLANNED — NOT STARTED | Android; parallel design only after its separate start authorization. | Review Overview/Predictions/History/Account + bell, phone/tablet layouts and all light/dark VI/EN states. No speculative wire integration. |
| S2-03 — Typed integration | PLANNED — NOT STARTED | Android; accepted S2-01 contract/readiness and S2-02 UI review first. | Shared Backend Retrofit/Gson DTO/repository/ViewModel flows provide parity, saved/unsaved semantics and safe same-Run recovery; DNSE remains separate. |
| S2-04 — Scheduled delivery and inbox | PLANNED — NOT STARTED | Backend durable Telegram/FCM; Android reception/inbox/navigation after device/event contract. | Every successful scheduled result persists and sends both channels, including HOLD/no Signal; per-device independence and canonical-detail navigation. Manual Telegram only, no manual FCM. |
| S2-05 — Candidate QA | PLANNED — NOT STARTED | System QA via BA; BOTH compatible developer handoffs and UI review required. | Verify candidate route/audience, authorized phone/tablet and bounded cases; reuse valid QA memory. Provider acceptance is distinct from receipt/open. |
| S2-06 — Legacy retirement | PLANNED — NOT STARTED | Android + Backend via BA/PO; replacement acceptance, explicit production cutover and verified consumer migration first. | Inventory/migrate shared auth/device/FCM dependencies before deleting old Planning/Android paths or exclusively old resources. Preserve shared Cloud Run, data/history and rollback evidence. |

### Android requirements carried into future handoffs

- Functional parity: health/models; saved Runs/Predictions/Signals; manual model selection/execution; model execution/Telegram TEST with save/no-save; supplied planning JSON TEST; Schedule set/pause/resume/off/on; saved-Run status/resume/resend. Save choice is explicit before send. Manual model selection cannot silently change the scheduled model. Runs, Predictions and Signals stay distinct; failed/planning-item Runs may lack a result; no-save means no history/recovery; Signal does not mean trade.
- UI: Overview initially shows latest results/signals, next schedule, notification status and Run prediction. Predictions/Signals groups have filters/pagination/detail; History shows Runs/results/errors/per-channel state. Bell opens read/unread inbox and linked Run result. Account groups Google/connection/schedule/advanced. Fixed navy Material 3 Compose, system light/dark, VI/EN, phone bottom navigation, tablet rail/list-detail, loading/empty/error/stale/TEST; no empty Portfolio/DNSE tabs. Simple model form is normal; JSON/planning TEST/recovery are advanced. Separate ViewModels, repositories and DTOs.
- Typed/auth boundary: Backend Python/Pydantic owns concrete `BaseResponse<T>`, actual HTTP/equal numeric code, separate business error, pagination/correlation. Android shared Backend Retrofit/Gson stays separate from DNSE. Firebase Google + App Check and authorized-admin/account/device rules apply. No embedded GCP/Telegram credentials, internal operator API access or dynamic JSON dispatcher from mobile.
- Mutation/lifecycle: no prediction/send/mutation replay on open/resume/tabs/paging/status. Persist idempotency before a saved mutation; recover the same Run; unknown outcome gets no blind retry. Explicit Telegram resend confirms duplicate risk. No-save cannot recover from saved history.
- Delivery: successful scheduled results persist and send BOTH Telegram and FCM for EVERY result including HOLD/no Signal, summarized once per Run/device. Track Telegram and each authorized device separately under accepted slot/logout rules; failure does not block another channel or resend completed deliveries. No manual FCM. Model failure records failed Run, never fake result-ready notification. `STOCK_PREDICTION_READY` carries stable event/Run/account identity, no fake Planning ID. Android deduplicates, fetches canonical authorized detail and opens the correct result. Provider acceptance, device receipt and user open are separate; no exactly-once promise.
- Candidate operations: schedule targets the candidate route URL and distinct OIDC audience if needed, never accidentally production; retain remote.py compatibility. Future schedule changes and delivery tests need bounded authority. This cycle makes no runtime change.
- Retirement: inventory Android/CLI/Scheduler/auth/notification consumers; preserve/migrate Google login/session, registration/revocation, Firebase verification and FCM BEFORE Planning removal. After accepted replacement/cutover/migration, retire old Android endpoints/DTOs/repositories/calls, Backend Drive Planning routes/module/runtime dependencies and EXCLUSIVELY old GCP resources. NEVER delete shared Cloud Run hosting the new Backend. Preserve Drive files/databases/history/rollback, separate read-only legacy history if needed, no silent Drive fallback. DNSE trading, single-stock research and Drive-planning model-provider import remain later separate phases.

### Evidence, limits and next action

Future QA criteria: auth; saved/unsaved; valid/invalid JSON; distinct histories/paging; schedule/candidate routing; Telegram+FCM receive/open on phone/tablet; HOLD; offline/invalid token; independent channel failures and safe recovery. QA must use [qa-memory](../../Test/TestProject/qa-memory.md) and bounded retests for affected cases; do not repeat valid PASS solely for a new handoff/build/chat. No such tests run for this documentation assignment.

Existing evidence: [UI publication](../../Planning/Android-current-ui-publication-20261004.json), [remote CLI QA](../../Planning/System-QA-remote-cli-gcp-test-20261004.md), [remote guidance QA](../../Planning/System-QA-remote-history-guide-20261004.md). Preserve their scoped acceptance and historical NOT RUN/BLOCKED; no new runtime or Sprint 2 product acceptance.

BA accepted ONLY documentation consistency/link/scope on 2026-10-04, including the exact three-file delta (AGENTS.md, roadmap.md, work-tracker.md), baseline/exclusion hashes and consistency with the Backend draft. Publish only these reviewed isolated doc changes on the existing branch and verify remote; do not blanket-stage old dirty hunks. [Publication receipt](../../Planning/Android-System-Sprint-2-doc-publication-20261004.json). Preserve Moshi additions, QA transport hunk, inactive adapters, Predictions draft/resources/tests and accumulated older document edits. No product code, runtime, tests, builds, cloud queries/changes, schedule enabling, deployment, sends, trades, general framework or paid infrastructure.

Next action is scoped documentation publication and remote verification, not S2-01/S2-02 execution. Android awaits separate start authorization and the accepted Backend contract before integration. At start/resume/every pull read AGENTS and both documents; before pause/handoff/completion update owner, dependencies, evidence, gates and next action. Report tool-safety blockers verbatim immediately; no bypass.

## Historical checkpoints — retained with their original evidence limits

Earlier “current assignment”, publication and paused statements below describe their dated checkpoints; they do not authorize product work in this docs-only Sprint 2 cycle.

## Current-app UI accepted by CTO — 2026-10-04

CTO explicitly accepted the handed-off current-app UI candidate and authorized Android publication, superseding the earlier pause and pending acceptance gate for this increment. Acceptance is based on the CTO's own phone functional checks the previous evening and tablet functional checks this morning, together with the existing independent QA evidence. CTO explicitly accepts the absence of protected screenshot evidence and requests no further tests, builds, device operations or screenshots. This is human product acceptance, not a claim that QA executed every case.

Independent QA retains its actual 26 JVM and three exact-device guard PASS results, the observed authenticated tablet read-only journeys and six configuration/semantic checks. Its historical phone journeys, populated notification detail/read-state/pagination, protected pixels and logout/sign-in NOT RUN/BLOCKED outcomes remain unchanged. FLAG_SECURE remains enabled. No further execution is required for this accepted increment by the CTO's decision; the historical evidence limits are not relabeled PASS or authority to bypass a rejected destructive action.

Scope is the exact21-path current-app UI candidate over c96de97: fixed navy light/dark theme, affected screen extraction, readable order/detail cards, grouped settings, notifications/admin presentation, width-adaptive navigation, VN/EN strings and the bounded existing-tooling instrumentation support. Product patch SHA-256: ca7222a46d04fba07de51d38cc6364fa153cb6a3047e4b5632416e3318cdb461. The handed-off app/test APK hashes remain 2f7b9f2ddb28af1800456e82695708b790204486aeedc720f6e0bb810eee1602 / 934d9f690c1b525c07eebc32544e2b4903f56b0459d8d893bfd697114349d051. Future authorized device runs must verify both installed APK identities; QA's earlier stale test package was environment drift, resolved by installing the exact test artifact, not a product defect.

Publish only these21paths and this acceptance note in each of roadmap/work-tracker on codex/mobile-api-integration. Preserve unrelated Predictions, inactive adapters, three Moshi additions, the separate QA hunk and accumulated documentation edits. API/auth/FCM/pooling/trading behavior and existing runtime are unchanged; no cloud deployment, real trades or next phase is authorized. Owner: Finance Android for scoped publication; BA for the accepted handoff. [Handoff](../../Planning/Android-current-ui-handoff-20261004.md), [original QA report](../../Planning/System-QA-Android-current-ui-20261004.md), [publication receipt](../../Planning/Android-current-ui-publication-20261004.json).

## Production deployment deferred — 2026-10-04

CTO decision via PO/BA: defer production replacement/deployment because managed PostgreSQL cost is not justified by current volume; testing remains local only. [Source proposal](../../Planning/Backend-production-replacement-proposal-20261004.md) is retained as a deferred proposal, not execution approval. Preserve existing production/local state and dirty work. No cloud preparation, build/upload, IAM change, migration, deployment or provider action; no new database, R1 or UI phase.

The accepted local-only pooling increment remains published at `ff09b6c765433fb8f25a1d7279987dc0954979e5`; its QA scope and limits remain valid. No redundant QA or autonomous Telegram send is needed. Android has no cloud action in flight and no next already-authorized local implementation item. This decision note records a deferral, not completion of cloud work or another product phase.


## Accepted local-only idle prevention — 2026-10-04

CTO resolved local-only prevention with production pooling unchanged. BackendSlot installs BackendHttp1ConnectionPolicy only inside LocalBackend.active after the valid-local-origin check. LocalDebug uses8001 and closes every local HTTP/1.1 exchange without retries; remote debug/release retains ordinary pooling. HTTP/2, DNSE, authentication, API contracts, timeouts, source/account fences and one-exchange behavior remain unchanged. The increment also includes the reviewed local endpoint panel, Planning health diagnostics and matching VN/EN notes.

System QA independently verified all11intended source hashes and the built/installed APK SHA256 `ac04400754af007adb08196848b2e089303eee34dac8e07e09b0fe9bd468769e`. Forced exact-candidate execution passed76tests:39debug,21localDebug,16release;0failed/errors/skipped. Real Samsung refreshes after10/30/60seconds idle passed. User-operated Telegram TEST in the same existing Backend session produced one correlated SENT delivery with no older queue drain; subsequent Android refresh also passed. BA granted final scoped acceptance. [Independent QA report](../../Planning/System-QA-Android-local-only-port8001-20261004.md).

Evidence limits: remote pooling, HTTP/2, DNSE, credentials and cancellation/source/no-replay guards were verified at component level; no production Backend call or real trade. Telegram provider acceptance/persistence was verified, not recipient rendering/read status. Backend unchanged readiness identifies529200cec7ebfa9c7b1cbfdf43ac9861013ee692; live process metadata is consistent with that session, but exact loaded-byte identity is unavailable. Earlier broad-policy QA remains historical and is not substituted for the narrowed candidate evidence. This bounded acceptance supersedes earlier pending integration/policy statements only for this increment, not other roadmap milestones.

Publication of the exact reviewed13paths (11product plus these2scoped documentation notes) is authorized on codex/mobile-api-integration. Exclude3Moshi additions, unrelated UI/drafts/QA edits and accumulated unrelated documentation. No merge, deployment, live send or device/runtime change follows from publication. Source manifest: `Planning/Android-local-only-port8001-candidate-20261004.json`; publication outcome: `Planning/Android-local-only-publication-20261004.json`.


## Mandatory Backend–Client integration policy — 2026-10-03

Read and follow the [authoritative shared integration procedure](../../Planning/Quy-trinh-dieu-phoi-Production-Owner.md). Direct user direction applies to current open work and future increments; it supersedes conflicting older workflow instructions below without rewriting historical evidence.

- One common local Backend origin serves implemented Android/Telegram capabilities; local.py manages the complete required service/database lifecycle. Preserve existing API/auth contracts.
- No Android: Retry / Skip / Exit, with Skip starting normally. Configure and verify reverse mappings for ALL authorized connected devices, not a single-device chooser; preserve conflicting mappings and report per-device failures. No automatic prediction, send or mutation replay on startup/reconnect.
- BOTH developer handoffs (or a version-pinned unchanged-side readiness acknowledgment) precede System QA integration dispatch. Real agreed client-to-result journeys, including Android and Telegram together when in scope, must PASS before QA confirmation, BA acceptance and final delivery.
- Build/unit/mock/health or isolated component PASS is insufficient. Required BLOCKED/NOT RUN and blocking defects prohibit final delivery. Preserve historical/component acceptance only within its stated scope; do not bypass publication or live-operation gates.
- Current status: POLICY ADOPTED; combined local runtime and real integration acceptance PENDING, not proven by this edit. Android follows its ownership in the shared procedure. Backend/Android provide readiness handoffs; BA then dispatches QA and tracks defects to verified closure. Earlier selected-device/separate-terminal troubleshooting below is historical guidance, not the target integrated workflow.


Updated 2026-10-03 (Asia/Ho_Chi_Minh). Companion: [roadmap](roadmap.md), [repository instructions](../AGENTS.md), [README](../README.md).

## Current handoff

**BA documentation review accepted on 2026-10-03; scoped publication pending/next. No product phase authorized.** Intended tracked scope is exactly `AGENTS.md`, `README.md`, `docs/roadmap.md` and `docs/work-tracker.md`. No product edits, product tests/builds, Cloud/runtime queries, live sends or next-phase work are part of this cycle. Product QA is still pending.

Canonical checkout: `/Users/tuanh/finance root/mobile-auto-stock-synchronize` (the old AndroidStudioProjects path is stale). Branch: `codex/mobile-api-integration`. Starting HEAD: `5d78632f631893c93101e4acb6397d2fac65eb0f`; initial index empty. Before this documentation cycle there were 19 dirty/untracked product files; preserve every file and hunk.

Live GitHub branch readback on 2026-10-03 matched `5d78632f631893c93101e4acb6397d2fac65eb0f` at `refs/heads/codex/mobile-api-integration`, destination `https://github.com/mclean04/mobile-auto-stock-synchronize.git`. No additional unpublished product commit was found. The local `origin/codex/mobile-api-integration` tracking ref still showed `9a1fe9b`; that cached ref is stale and is not proof of an unpublished header commit. No fetch, pull, merge or rebase was performed for this documentation preparation.

Read-only publication inspection found no checked-in `.github/workflows`, no active non-sample local hooks, no custom hooksPath, and no configured remote.origin.push or push.followTags override. External GitHub integrations/hooks were not inspected, so absence of remote side effects is not claimed. Recheck exact outgoing ancestry and remote compatibility at publication; stop on divergence or new tool-review denial.

## Work, owners and gates

| Item | Actual state / evidence | Owner and next action |
| --- | --- | --- |
| R0 header/diagnostic checkpoint | Accepted and published at 5d78632; independent 57 debug / 45 localDebug / 10 release executions passed. Device idle root cause not established. Current source review confirms DNSE-specific per-call headers already implemented; no new product changes or tests for the repeated header request. | Preserve checkpoint; no new runtime-fix claim. |
| Legacy DTO group 1 | Developer-complete in working tree; raw health/status/device PUT/DELETE typed through Retrofit/Gson with strict fields and uncertainty safeguards. 40 debug / 23 localDebug / 13 release developer executions passed; no independent QA/publication yet. | BA coordinates System QA. Android preserves source/compatibility evidence and waits for bounded acceptance; docs-only acceptance cannot publish this code. |
| Current documentation | Four-file documentation review accepted by BA on 2026-10-03, including R1 ownership/idempotency and R3 eligibility/slot/reconciliation qualifiers; final status-only wording approved. | Scoped publication pending/next: Android selectively commits only these docs and verifies ordinary push to existing branch. No product phase authorized. |
| R1 local Predict | Planned; zero active Android stock calls. Single-result dev stock response is historical; amended owner-scoped run/list and idempotent recovery contract pending acceptance. | Backend publishes the contract and evidence; BA coordinates sequencing through PO/CTO before Android implementation. |
| R2 GCP first release | Predict + eligible-Signal Telegram; implementation/cost/deploy gates remain. | Backend/BA proposal, PO/CTO approval. No provisioning or sending in this cycle. |
| R3/R4 and remaining legacy migration | Planned/deferred as described in roadmap. R3 requires an eligible Backend Signal, one submitted-order slot per prediction/account/environment among cooperating clients, reconciliation for ambiguity rather than TTL-only release, and acknowledgement distinct from fill. No automatic execution or Drive expansion. | BA prioritizes approved work and contract owner order; do not start from a documentation handoff. |

## Product changes excluded from documentation publication

The complete 19-file before/after hash inventory belongs to the documentation review manifest in the shared Planning directory. The main preserved groups are:

- Uncommitted group-1 tracked code: PlanningRepository, SessionStatus, BackendApi, BackendEndpoint, BackendSlot, PlanningBackend, QaNotificationTransport, Transport, SessionGson, PlanningViewModel and BackendCredentialsTest.
- New group-1 files: PlanningAccountModels, PlanningAccountRemote, PlanningScalars and PlanningAccountContractTest.
- Mixed `app/build.gradle.kts`: one required Gson converter addition belongs to pending group 1; three pre-existing Moshi dependency lines remain excluded from that product increment. **None** belongs to this docs commit.
- Pre-existing `QaCampaignTransportTest.kt` changes remain excluded. Its historical regression execution is not acceptance of its unrelated dirty hunk.
- Untracked `MobilePlanningBackend.kt` and `MobileDomainAdapterTest.kt` remain unfinished/inactive. Their four-signature compatibility edits were recorded in the group-1 handoff without activating their wider feature. Do not stage them with documentation or erase their prior work.

No blanket add/reset, generated artifacts, credentials or sensitive raw logs. The original exclusions and product manifest must not be replaced with stale versions merely to make the checkout clean.

## Contract and evidence references

- [Group-1 developer handoff](../../Planning/Android-planning-group1-handoff-20261003.md), [manifest](../../Planning/Android-planning-group1-handoff-20261003.json), [dependency-only patch](../../Planning/Android-planning-group1-handoff-20261003-dependency.patch), [draft compatibility patch](../../Planning/Android-planning-group1-handoff-20261003-draft-compatibility.patch).
- [Accepted header/DNSE handoff and publication receipt](../../Planning/Android-backend-headers-logcat-idle-handoff-20261002.md), [manifest](../../Planning/Android-backend-headers-logcat-idle-handoff-20261002.json).
- [Backend Sprint 6 independent QA](../../Planning/System-QA-Backend-Sprint-6-contract-postman-20261003.md), accepted implementation e2e66217: 152 tests / 22 actual local Postman requests. No Android, Cloud, production auth or live-provider acceptance. Documentation-only successors are not newly tested implementation.
- [Backend API](../../auto-stock-synchronize/docs/api.md), [Android consumer contract](../../auto-stock-synchronize/docs/stock-api-android.md), [session contract](../../auto-stock-synchronize/docs/contracts/session-registration-v2.md). Apply each only to its owning service/version. The new stock list/run amendment is pending acceptance; existing planning/mobile and DNSE contracts are unchanged.

These Planning/Backend links resolve in the shared finance-root workspace; they are external handoff evidence, not files bundled into this Android documentation commit. Earlier integration Sprint acceptance and stock_backend roadmap sprints remain distinct. Historical deferred/blocked native/device/cloud evidence is not converted into PASS by documentation or component tests.

## Safe resumption and commands

At start/resume and after **every pull**, read applicable AGENTS.md plus both roadmap/tracker files before editing. Inspect actual branch/index/dirty state and contract evidence. Update both documents before pause/stop/handoff, including partial results, blockers, owner and next action.

Read-only checks for the current docs review:

```bash
cd '/Users/tuanh/finance root/mobile-auto-stock-synchronize'
git status --short
git branch --show-current
git rev-parse HEAD
git diff --cached --name-status
git diff --check
git diff -- README.md
```

Untracked documentation needs inclusion in the saved review diff; plain `git diff` alone omits new files. No product test suite is required for this documentation-only review. For a **later explicitly assigned** product validation, the known variant prerequisite is `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false`, using Studio's JBR and each task's own `--tests` filters; the exact prior commands are in the group-1 handoff. Do not run them, launch local.py, clear data, install an APK or contact a provider merely to validate these docs.

BA documentation review was accepted on 2026-10-03. Scoped publication is pending/next: stage exact docs paths/hunks, inspect staged payload and outgoing ancestry, commit the accepted docs, use a non-force push to the existing branch and verify remote SHA. Report acceptance scope and actual result; stop on divergence/denial. No product phase is triggered by publishing these docs.

## Group-1 component acceptance — 2026-10-03

BA accepted [independent System QA](../../Planning/System-QA-Android-planning-group1-20261003.md): 40 debug / 23 localDebug / 13 release executions PASS, 19/19 source hashes matched; runtime/device/provider NOT RUN. This supersedes earlier group-1 QA-pending statements. This local checkpoint contains the 15 reviewed group-owned files, converter-gson-only Gradle addition and these acceptance notes. Existing Moshi lines, dirty QA test hunk, inactive draft adapters and presentation work remain excluded. No next DTO group is authorized.

Publication remains **BLOCKED**: parent documentation commit `a7349c24ece307098457ceecbdba9f7820d9134b` was rejected for push by tool review. This checkpoint is local only; no retry, alternate ref or hidden ancestor export is authorized. See the [publication receipt](../../Planning/Android-roadmap-doc-review-20261003.json).
