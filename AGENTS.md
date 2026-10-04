# Android repository workflow

## Current assignment — System Sprint 2 documentation only (2026-10-04)

**System Sprint 2 — Android mới, Stock API và Notifications: PLANNED — NOT STARTED.** Follow the current sections in [roadmap](docs/roadmap.md) and [work tracker](docs/work-tracker.md), sourced from the CTO-approved PO roadmap v2026-10-04.7 through BA's scoped handoff. Do not access/export the private PO folder. This new plan supersedes conflicting prospective local-only, Telegram-only/eligible-Signal-only, no-scheduled-prediction and old-navigation directions; preserve dated evidence and historical Backend sprint numbering. Do not infer System Sprint 1 closure.

- BA accepted the documentation consistency/link/scope review on 2026-10-04. Current work is scoped documentation publication only. No product code, design implementation, tests, builds, devices/runtime, cloud queries/changes, schedule enabling, deployment, sends, trades, framework expansion or paid infrastructure. Every S2 product item remains PLANNED — NOT STARTED until separately authorized.
- S2-01: Backend contract first, Android DTO review. S2-02: parallel Android design only after separate start authorization. S2-03: typed Android integration only after accepted Backend contract/readiness. S2-04: Backend durable Telegram/FCM and Android reception/inbox/navigation only after the device/event contract. S2-05: QA after BOTH compatible developer handoffs and UI review. S2-06: retirement only after accepted replacement, explicit production cutover and verified consumer migration.
- Android owns the planned Compose UI, shared Backend Retrofit/Gson DTO/repository/ViewModel integration and FCM lifecycle/inbox/deep navigation. Keep Backend and DNSE clients/auth separate; no embedded operator credentials or mobile internal-operator API access. Preserve no lifecycle-driven mutation replay and use durable same-Run recovery only under the accepted contract.
- Scheduled success must persist and notify through Telegram AND FCM for every result including HOLD/no Signal, with one summary event per Run/device and independent channel/device outcomes. Manual flows retain Telegram with NO manual FCM. The detailed future contract and UI requirements are in the roadmap; this rule does not authorize any send or schedule change now.
- Baselines: remote.py implementation a2347a2/guidance89d262c and scoped accepted Android UI af11e71. Pin compatible candidate versions/routes at future authorized execution; do not assume current compatibility. Reuse valid QA evidence and bounded affected retests; historical NOT RUN/BLOCKED remain historical.
- Read AGENTS/roadmap/tracker at start, resume and every pull; update both docs before pause/handoff/completion with owner, dependencies, evidence, gates and next action. BA approved the exact three-file documentation package. Next action: publish only its isolated reviewed changes on this branch and verify remote, preserving all unrelated dirty hunks; then await separate product-start authorization. Report safety blocks verbatim immediately; no bypass.

## Mandatory Backend–Client integration policy — 2026-10-03

Read and follow the [authoritative shared integration procedure](../Planning/Quy-trinh-dieu-phoi-Production-Owner.md). Direct user direction applies to current open work and future increments; it supersedes conflicting older workflow instructions below without rewriting historical evidence.

- One common local Backend origin serves implemented Android/Telegram capabilities; local.py manages the complete required service/database lifecycle. Preserve existing API/auth contracts.
- No Android: Retry / Skip / Exit, with Skip starting normally. Configure and verify reverse mappings for ALL authorized connected devices, not a single-device chooser; preserve conflicting mappings and report per-device failures. No automatic prediction, send or mutation replay on startup/reconnect.
- BOTH developer handoffs (or a version-pinned unchanged-side readiness acknowledgment) precede System QA integration dispatch. Real agreed client-to-result journeys, including Android and Telegram together when in scope, must PASS before QA confirmation, BA acceptance and final delivery.
- Build/unit/mock/health or isolated component PASS is insufficient. Required BLOCKED/NOT RUN and blocking defects prohibit final delivery. Preserve historical/component acceptance only within its stated scope; do not bypass publication or live-operation gates.
- Historical checkpoint (2026-10-03): POLICY ADOPTED; combined local runtime and real integration acceptance PENDING, not proven by that edit. Android follows its ownership in the shared procedure. Backend/Android provide readiness handoffs; BA then dispatches QA and tracks defects to verified closure. Earlier selected-device/separate-terminal troubleshooting below is historical guidance, not the target integrated workflow.


## Start, resume and pull

- Read the applicable parent AGENTS.md, this file, [docs/roadmap.md](docs/roadmap.md) and [docs/work-tracker.md](docs/work-tracker.md) before starting or resuming work. Read them again after **every pull**, before editing further.
- Inspect the actual checkout path, branch, HEAD, index and dirty/untracked files. Check the current contract, accepted checkpoint and next authorized action. Preserve existing work; never reset, blanket-stage or restore an older manifest over current files.
- Finance Android must not read, search or edit ProductionOwner/. BA supplies the coordinated roadmap direction; BA's permission to read its exact roadmap does not extend to Android.

## Scope and contracts

- Follow the currently assigned bounded phase. Roadmap milestones are plans, not automatic implementation, spending, deployment, device or LIVE authority. R0–R4 do not replace Backend sprint numbering; Sprint 7 remains GCP.
- For a new or changed shared contract, stop dependent implementation. Route the concrete proposal, affected consumers, impact, recommended owner order and handoff conditions through BA -> PO -> CTO for sequencing approval. Unaffected authorized work may continue; do not reopen settled sequencing without a material change.
- Map each call to its owning server. stock_backend, legacy planning_backend/mobile session and DNSE have different wire/auth contracts. Do not globally wrap responses, repoint production to an undeployed service, use a dev-only command as production authorization, or create prediction-to-order behavior implicitly.
- Reuse shared Retrofit/Gson clients and scoped headers. Preserve numeric/null validation, account/environment/source-generation isolation, local DB/lifecycle behavior, revocation, signing, response caps and no automatic trading/mutation replay. Broad DTO/persisted-login holds are not lifted by documentation work.
- Preserve existing debug/localDebug versus release logging rules. Never include real credentials, raw sensitive logs, OAuth files, APKs or local machine data in reports/commits. Keep Backend and DNSE authentication separate.

## Verification, handoff and publication

- Developers own focused component checks; System QA owns independent acceptance and reusable test infrastructure. QA Python remains outside this Android repository. A developer PASS is not independent QA or runtime/provider acceptance.
- Update **both** docs/roadmap.md and docs/work-tracker.md before every pause, stop or handoff, including partial work. Record actual status, contract pins, owner, blockers, next action, evidence, dirty exclusions and publication status. Preserve historical acceptance and its limits.
- After scoped acceptance, commit the accepted phase and its documentation together, then push the existing agreed branch and verify the remote SHA. Do not accumulate accepted phases or include unrelated files/hunks. Documentation-only acceptance does not authorize committing pending product code.
- Before publication, inspect the exact index/payload, outgoing ancestry, destination and local/checked-in push automation. Stop and report unexpected ancestors, divergence, new side effects or a tool-review denial; no alternate ref/tool workaround, force push, merge, rebase or deployment is implied.
- The 2026-10-03 four-file documentation cycle is historical. The current System Sprint 2 cycle requires BA review of the exact **three-file** delta (AGENTS.md, docs/roadmap.md, docs/work-tracker.md) and baseline/exclusion hashes before staging, committing or pushing. Documentation approval does not start product implementation or tests.
