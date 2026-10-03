# Finance Android work tracker

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
