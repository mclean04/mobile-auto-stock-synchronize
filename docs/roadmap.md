# Finance Android roadmap

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
