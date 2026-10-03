# Finance Android work tracker

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
