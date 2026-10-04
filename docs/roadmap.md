# Finance Android roadmap

## System Sprint 2 — Android mới, Stock API và Notifications

**PLANNED — NOT STARTED.** Authority: CTO-approved PO roadmap v2026-10-04.7, supplied through the BA-scoped handoff on 2026-10-04. This repository records that handoff only; no private PO folder was accessed or exported. BA accepted the documentation consistency/link/scope review on 2026-10-04; current assignment is publication of the isolated three-file package only. Approval of this plan does not start implementation, design execution, tests, builds, runtime changes, cloud work, schedule enabling, deployment, sends, trades, a test framework or paid infrastructure.

Baselines supplied by BA: remote.py implementation `a2347a2`, guidance `89d262c`; current Android UI `af11e7149834be2f7029cec77baf55b89e0e9eb5` has scoped CTO acceptance and verified publication. These are references, not a claim that a compatible Sprint 2 integration already exists. Pin compatible Backend/API, Android source/app/test artifacts, candidate route and environment versions at future authorized execution. Keep the TEST model and its results visibly labeled TEST. System Sprint 2 does not renumber historical Backend sprints, replace their evidence, or declare System Sprint 1 closed.

For prospective Sprint 2 scope, this plan supersedes older local-only, Telegram-only/eligible-Signal-only, no-scheduled-prediction and old-navigation directions. Dated results, prior runtime limits, acceptance exceptions and paused checkpoints below remain history; they are not new execution authority. Existing services and consumers stay unchanged until the relevant start/cutover gates are approved.

### Planned sequence, ownership and acceptance

| Item | Status | Owner and dependency | Required outcome / acceptance gate |
| --- | --- | --- | --- |
| S2-01 — Backend contract first | PLANNED — NOT STARTED | Backend owns concrete Python/Pydantic mobile contracts; Android reviews DTO/nullability/error, pagination, auth and lifecycle implications through BA. Separate start authorization required. | Accepted operation/object mapping and compatible readiness handoff before Android typed integration. Keep remote.py compatibility; no speculative mobile wire contract. |
| S2-02 — Android design | PLANNED — NOT STARTED | Android owns Compose screens and state design; may proceed in parallel with S2-01 only after separate design-start authorization. | Review the required navigation, phone/tablet layouts, VI/EN, light/dark and loading/empty/error/stale/TEST states. Visual design review is not permission to call an unaccepted API. |
| S2-03 — Android typed integration | PLANNED — NOT STARTED | Android owns Retrofit/Gson DTOs, repository mapping and ViewModels; depends on accepted S2-01 contract/readiness and reviewed S2-02 UI. | Implement the explicit parity flows below with authorized auth, save/no-save, durable saved-operation identity and safe recovery; no lifecycle-triggered mutation replay. |
| S2-04 — Scheduled Telegram + FCM | PLANNED — NOT STARTED | Backend owns durable scheduling/delivery; Android owns registration lifecycle, reception, inbox and navigation after accepted device/event contract. | Every successful scheduled result, including HOLD/no Signal, persists and reaches the independent Telegram/FCM delivery paths. Manual flows retain Telegram and have no manual FCM. Candidate routing and bounded delivery-test authority precede execution. |
| S2-05 — Candidate integration QA | PLANNED — NOT STARTED | System QA, coordinated by BA, after BOTH compatible developer handoffs and UI review. | Pin candidate versions/routing and authorized phone/tablet/account prerequisites; cover the bounded acceptance cases below. Reuse QA memory; retain actual PASS/FAIL/BLOCKED/NOT RUN and distinguish provider acceptance, device receipt and user open. |
| S2-06 — Legacy retirement | PLANNED — NOT STARTED | Backend + Android, coordinated by BA/PO, after replacement acceptance, explicit production cutover and verified consumer migration. | Inventory and migrate dependencies first; remove only obsolete Android/Planning paths and exclusively old resources, preserve shared services/data/history/rollback evidence. No deletion is authorized by this plan. |

### Android functional parity and UI

Match the approved remote.py capabilities through the future mobile contract: health and model listing; saved Runs, Predictions and Signals; manual model selection and execution; model-execution/Telegram TEST with save or no-save; supplied planning-JSON TEST; Schedule set/pause/resume/off/on; saved-Run status, resume and explicit resend. Manual model selection must not silently change the scheduled model. Choose save/no-save before sending. The normal flow uses a simple model form; raw JSON, planning TEST and recovery controls belong in Advanced.

Run, Prediction and Signal are distinct records. A failed Run or a supplied planning-item TEST can have no model result; do not fabricate a Prediction/Signal or imply an order. No-save produces no saved history or recovery. A Signal is not a trade. Histories, filters, pagination and detail reads only read existing authorized records; zero/one/many results use a collection with a separate unresolved/loading state. Backend owns the concrete pagination and correlation contract; do not infer wire fields or routes from this plan.

Navigation is Overview (initial), Predictions, History and Account, plus a notification bell. Overview shows latest results/signals, next schedule, notification status and an explicit Run prediction action. Predictions contains Predictions/Signals groups with filters, pagination and details. History distinguishes Runs, results, errors and per-channel delivery states. The bell opens a read/unread inbox and the linked Run's correct result. Account groups Google identity, connection, schedule and advanced controls. Do not add empty Portfolio or DNSE destinations.

Reuse fixed navy Material 3 Compose, system light/dark, VI/EN, phone bottom navigation and tablet rail/list-detail layouts. Clearly present loading, empty, error, stale and TEST states. Keep ViewModels, repositories and typed DTOs separate; rendering or navigation must not dispatch hidden business commands. Preserve existing account/environment boundaries and accepted security protections while designing the replacement.

### Contract, authentication and mutation safety

Backend owns Python/Pydantic concrete `BaseResponse<T>`, real HTTP status with equal numeric top-level code, separate business error, pagination and correlation. Android uses its shared Backend Retrofit/Gson client with typed endpoint request/response data classes and domain mapping; keep DNSE signing, headers and clients separate. No generic Object/Any payloads or dynamic JSON dispatcher as a substitute for agreed DTOs. Advanced supplied JSON TEST still uses an explicitly defined, validated business operation.

Use Firebase Google sign-in plus App Check for the authorized admin and accepted account/device rules. No embedded GCP or Telegram credentials, no mobile access to internal operator APIs. Inventory existing Google session, device registration/revocation, Firebase verification and FCM dependencies before migrating them; do not treat an old module boundary as proof these capabilities are dispensable.

Opening, resuming, changing tabs, paging or reading status must not replay prediction, send or mutation commands. Persist idempotency identity before a saved mutation; recovery resolves the same Run. An unknown outcome is not grounds for a blind retry. Explicit Telegram resend requires a duplicate-risk confirmation and targets the existing Run; it is not an implicit new prediction. Unsaved operations cannot claim durable history/recovery.

### Notifications, scheduling and Android lifecycle

A successful scheduled prediction persists its result and sends BOTH Telegram and FCM for EVERY result, including HOLD or no eligible Signal. Use one summary event per Run/device covering that Run's results; do not restrict notifications to eligible Signals. Preserve authorized-account, device-slot and logout rules in the accepted contract. Track Telegram and each device independently: failure in one must not block another or resend an already completed channel. Model failure records a failed Run, not a fabricated result-ready notification. Manual execution retains Telegram; NO manual FCM.

Use `STOCK_PREDICTION_READY` with stable event, Run and account identity, never a fake Planning ID. Android deduplicates reception, maintains read/unread inbox state and fetches canonical authorized detail before opening the correct result. Handle foreground/background/open and account/device lifecycle against the accepted contract. Distinguish provider acceptance, device receipt and user open; do not promise exactly-once delivery.

Candidate scheduling must target the approved candidate route URL and, if required, a distinct OIDC audience. Never accidentally target production. Preserve remote.py compatibility. Any future schedule changes, activation and delivery tests require separately bounded authorization and pinned routing; no schedule or cloud changes occur in this documentation cycle.

### Replacement and retirement boundaries

Inventory Android, CLI, Scheduler, auth and notification dependencies. Preserve or migrate Google login/session, device registration/revocation, Firebase verification and FCM BEFORE removing the Planning module. Only after replacement acceptance, approved production cutover and verified consumer migration may owners remove old Android endpoints/DTOs/repositories/calls, Backend Drive Planning routes/module/runtime dependencies and EXCLUSIVELY old GCP resources. NEVER delete a shared Cloud Run service hosting the new Backend.

Preserve Drive files, databases, history and rollback evidence. If required, provide a separate read-only legacy-history path; no silent Drive fallback. DNSE trading, single-stock research and Drive-planning model-provider import belong to later separately authorized phases, not System Sprint 2 implementation scope.

### Future QA gates and next action

After both compatible handoffs and UI review, QA covers auth, saved/unsaved flows, valid/invalid supplied JSON, distinct histories/paging, schedule/candidate routing, Telegram plus FCM receipt/open on phone/tablet, HOLD, offline/invalid-token behavior, independent-channel failure and safe same-Run recovery. Read and reuse [QA memory](../../Test/TestProject/qa-memory.md), and rerun only cases with a concrete affected change, environment/evidence issue or explicit retest direction. A new chat or documentation change does not justify repeating successful tests. These are future acceptance criteria; no new test execution is authorized now.

Current evidence is limited to plan consistency, links and scope review. [Existing UI publication receipt](../../Planning/Android-current-ui-publication-20261004.json), [remote CLI QA](../../Planning/System-QA-remote-cli-gcp-test-20261004.md) and [remote history guidance QA](../../Planning/System-QA-remote-history-guide-20261004.md) retain their exact historical limits; they do not accept Sprint 2 implementation.

BA accepted this exact three-file documentation package against the authorized plan and matching Backend draft on 2026-10-04. Next action: publish only the isolated reviewed document hunks on the existing branch and verify the remote, then await separate product-start authorization. [Publication receipt](../../Planning/Android-System-Sprint-2-doc-publication-20261004.json). Product execution still needs separate start authorization. Read AGENTS/roadmap/tracker at start, resume and every pull; update both docs before pause, handoff or completion with owner, dependencies, evidence, gates and next action. Report a safety rejection verbatim; no bypass.

## Historical checkpoints — retained with their original evidence limits

The sections below preserve earlier assignments and outcomes. Their use of “current”, “next” or “paused” is dated history; the System Sprint 2 plan above controls prospective scope and does not retroactively change those results.

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


Updated 2026-10-03 (Asia/Ho_Chi_Minh). Read with the [work tracker](work-tracker.md) and [repository workflow](../AGENTS.md).

## Current scope and evidence

The active assignment is **documentation only**: BA documentation review accepted on 2026-10-03; scoped publication is pending and is the next action. No product phase is authorized. It does not start a product phase, authorize deployment/spending, or accept pending product work.

Accepted Android foundation: `5d78632f631893c93101e4acb6397d2fac65eb0f` on `codex/mobile-api-integration`, previously QA-accepted and pushed. It centralizes Backend and DNSE headers and provides scoped debug diagnostics. Independent QA reported 57 debug, 45 localDebug and 10 release executions passing. Device idle root cause remains unestablished; diagnostic capability, not a runtime fix, was accepted.

The subsequent legacy DTO group 1 is **developer-complete, uncommitted, independent QA/publication pending**: raw planning health, sync status and device PUT/DELETE. Developer results: 40 debug, 23 localDebug and 13 release executions passing. These overlap across variants; they are not unique scenarios or live-provider evidence. There are zero active Android stock API calls. See [group-1 handoff](../../Planning/Android-planning-group1-handoff-20261003.md) and [source/test manifest](../../Planning/Android-planning-group1-handoff-20261003.json).

## Approved shared milestone sequence

R0–R4 are cross-project milestones, **not replacement sprint numbers**. They describe intended outcomes, not implemented features or permission to begin every phase. BA relays CTO-approved sequencing through PO.

| Milestone | Intended outcome and boundary | Android state and next owner |
| --- | --- | --- |
| R0 — Existing foundation | Preserve accepted clients, headers, isolation, persistence and diagnostic checkpoints; close bounded pending work only through its review/QA gate. | Accepted header checkpoint published. DTO group 1 awaits BA/System QA disposition. Android preserves the dirty implementation. |
| R1 — Local Predict | A simple explicit client command; model owns the result list, with no watchlist input. Runs/results belong to the authenticated owner. A deliberate new click creates a new run at page 0; idempotent recovery uses the same owner-scoped key and existing run, without duplicate inference or delivery. Persisted run results and history are paginated: default 20, maximum 100. Reading a page performs no inference, regeneration or resend. | Planned; no Android stock integration. Backend owns the amended run/result/ownership/error contract; BA coordinates contract acceptance and Android handoff before dependent implementation. |
| R2 — First GCP release | Predict plus eligible-Signal Telegram is the **first release**. Production command/auth, delivery and cost/deploy conditions must be approved before resources or rollout. No scheduled/proactive prediction generation. | Planned and gated. Backend/BA prepare the concrete contract and release/cost proposal; PO/CTO approve sequencing and deployment/spending. Existing reminder/outbox semantics are not silently repurposed. |
| R3 — Manual DNSE | Only an eligible Backend Signal opens the manual ticket. Explicitly confirmed account, price and quantity; one durable submitted-order slot per prediction/account/environment among cooperating clients. Ambiguous outcomes require reconciliation, not TTL-only slot release; broker acknowledgement is not a fill. Preserve order/reconciliation history. No automatic trading from a prediction, Signal or notification. | Planned prediction-linked workflow. Existing manual planning/DNSE functionality is historical foundation, not completion of this new slot/reconciliation contract. Backend and Android require coordinated ownership/idempotency contracts and QA. |
| R4 — Separate symbol analysis | After main acceptance and demonstrated model capability: separate symbol analysis, optional proposed price/holding period and independent paginated report history. No implicit prediction, Signal, Telegram message or order. | Deferred product milestone. BA/PO/CTO approve capability and separate contract before owners implement. |

The planned light/dark phone/tablet UI has Predictions, Portfolio, Orders and Account surfaces. This is a design direction, not implemented navigation or design acceptance. Existing adaptive layouts do not prove this new UI is complete.

## Accepted versus pending contracts

- **Existing planning_backend:** keep its endpoint-specific raw response shapes and current service origin. Group 1 uses raw `{status, storage}`, sync status fields, and `{device_id, registered}`; no stock envelope or request-ID requirement is invented. Group-1 Android changes remain unaccepted/unpublished product work pending QA.
- **Existing localDebug session:** `/mobile/v2/session` keeps its accepted `mobile-envelope.v2`, Firebase/App Check verification, registration/revocation and outcome semantics. Health is not authorization proof.
- **DNSE:** preserve its own production/sandbox keys, signing inputs, trading token, acknowledgement/uncertainty and no-replay rules. No DNSE wire migration follows from the stock roadmap.
- **Accepted historical stock Sprint 6:** Backend implementation `e2e66217f591d89859cd9bb96f91d7e3bc341bac`, app 0.2.0 and `stock-api.v1`, had independent 152-test acceptance including 22 local Postman HTTP requests. That acceptance excludes Android, live Telegram, cloud/deployment, real broker actions and production auth. Its dev-only single-result create contract is **not the final mobile Predict contract**.
- **Pending stock amendment:** even one result uses `data.items=[result]`; models may emit multiple results. All list-returning APIs need pagination. Run-scoped persisted results/history, recovery keys, ownership/auth, errors and page behavior require Backend handoff and acceptance before Android adoption. Pagination must not regenerate results or trigger delivery. Do not globally wrap legacy lists or break installed consumers; inventory each legacy gap and obtain explicit migration approval.

Reference Backend [API documentation](../../auto-stock-synchronize/docs/api.md), [Android contract notes](../../auto-stock-synchronize/docs/stock-api-android.md), [roadmap](../../auto-stock-synchronize/docs/roadmap.md) and [tracker](../../auto-stock-synchronize/docs/work-tracker.md). Older dated statements about unresolved list semantics are superseded by the coordinated direction above, not by an assumption that the amendment has shipped. Backend's local Telegram workflow is a future mobile reference; source option 7 runs that workflow, while option 8 clears local data. Neither is executed or reimplemented in this documentation cycle.

## Gates and deferred work

At any new/changed shared-contract dependency, stop dependent implementation and send the proposal, impact, recommended owner order and handoff conditions through BA -> PO -> CTO. Unaffected work already authorized may continue. A coordinated roadmap does not itself approve a wire change, paid resource or production release.

Remaining legacy Android DTO groups are deferred/prioritized by this roadmap and BA assignments; they do not start automatically after group 1 and do not imply Drive expansion. Backend **Sprint 7 remains GCP**. VPS/workstation hosting and Drive model/import are deferred, unscheduled and unimplemented in this scope.

Immediate action: publish the four documentation files under BA review accepted on 2026-10-03; the scoped commit/push and remote verification are pending. Documentation acceptance/publication is separate from product QA. System QA remains pending for DTO group 1; no additional product checks or deployment occur here. Keep both this roadmap and the tracker current before stopping, including incomplete work.

## Group-1 component acceptance — 2026-10-03

BA accepted [independent System QA](../../Planning/System-QA-Android-planning-group1-20261003.md): 40 debug / 23 localDebug / 13 release executions PASS, 19/19 source hashes matched; runtime/device/provider NOT RUN. This supersedes earlier group-1 QA-pending statements. This local checkpoint contains the 15 reviewed group-owned files, converter-gson-only Gradle addition and these acceptance notes. Existing Moshi lines, dirty QA test hunk, inactive draft adapters and presentation work remain excluded. No next DTO group is authorized.

Publication remains **BLOCKED**: parent documentation commit `a7349c24ece307098457ceecbdba9f7820d9134b` was rejected for push by tool review. This checkpoint is local only; no retry, alternate ref or hidden ancestor export is authorized. See the [publication receipt](../../Planning/Android-roadmap-doc-review-20261003.json).
