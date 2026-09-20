# Android Sprint 1 — Collapsible planning cards and local chronological sorting

Date: 2026-09-20. Branch: `codex/mobile-api-integration`. Based on B3 commit `fb2a91e`.
Scope: `Planning/Sprint-1-order-cards-and-priority.md` and the latest shared context.

## Delivered

- Planning All, Upcoming and History cards start collapsed. Summary retains symbol, side/cancel badge, scheduled time (or existing date-only/unknown presentation), and existing authoring/execution or legacy status. Tapping the card opens/closes existing details. VN/EN expansion labels and accessibility state/click labels are localized.
- Expansion lives only in Compose `remember`, hoisted outside lazy items. Scope is current Firebase UID + snapshot source ID/generation + selected tab; per-card identity is source ID/generation + intent ID. Stable grid keys prevent a row from inheriting another row's UI state during reorder/filtering.
- Clock ticks, recomposition, scroll disposal and same-source snapshot refresh do not reset the map. A newly encountered identity is collapsed. Tab change, owner/source/generation change, leaving/reopening Orders, Activity recreation or process restart resets to collapsed. No expanded state is stored in Room, preferences, or saved-instance state. A version update to the same identity keeps its expanded state while showing current details.
- Nested order button consumes its own touch; expansion never invokes the order action. All/History remain read-only. Upcoming uses the unchanged canonical/EXACT/current-time guard, fresh-cache/environment/eligibility/cash checks and existing repository/preflight/report guards. No cancel flow was added. Admin's existing noncollapsible card presentation is preserved.
- Upcoming still filters EXACT `scheduled_at >= device_now`. It sorts by scheduled local date in `ZoneId.systemDefault()`, then absolute scheduled instant, then stable intent ID. All/History order and filters are unchanged. DATE_ONLY/INVALID/MISSING are not assigned inferred times.
- Filtering, sorting and expansion use the local snapshot only. No new request, polling, automatic list refresh, persistence schema or backend change. P1 raw DEBUG unchanged.
- B3 harness opens the collapsed target card before locating its action. No B3 cloud/QA/business test was rerun for this UI change; prior evidence remains historical evidence of its recorded binaries.

## Priority: BLOCKED_BACKEND_CONTRACT

No typed priority field or ordering semantics exist in current Android PlanningIntent/PlanningAll, backend `src/planning_backend/planning_all.py` and `planning_v2.py`, `docs/planning-contract-v2.md`, or the shared Sprint-1 contract/source contract. Raw notes/legacy extra fields have no contractual ranking semantics. Android does not infer rank from Sheet row order, thesis, time window, or guessed field names.

Only same-day planning priority remains blocked. Date/time/identity sorting is delivered. Backend was not modified or assigned work, and no Cloud/Drive writes were made. BA must treat priority as incomplete until an explicitly approved future contract provides the field and comparator/null rules.

## Verification

- Targeted JVM tests: 18 passed (PlanningAllTest 13, PlanningActionPolicyTest 3, LocalizationTest 2). Covers exact equality/nanoseconds/offsets, device-zone chronological sorting, stable ID ties, unchanged All/History, date-only exclusion, no inferred priority, snapshot atomicity and existing action policy.
- Samsung SM_X730 (ADB transport 7): PlanningOrderCardTest 3/3 passed. Real Compose card/state helper tests cover all three tabs, collapsed summaries, open/close, clock/recreated JSON retention, identity/source-generation/source-ID/owner/screen reset, read-only tabs, cached action suppression and physical child-button tap without parent toggle. This is local component UI evidence; no HTTP/repository/DNSE is instantiated by these tests. FLAG_SECURE remains enabled.
- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: PASS; lint 0 errors, 86 warnings. Initial compile found Admin's shared card call needed the original noncollapsible default; fixed before successful build. No failed device UI tests.
- APKs installed with `adb -t 7 install -r`; no uninstall or data clear. Two Wi-Fi entries refer to the same physical Samsung tablet.
- `git diff --check`: PASS. Existing full unit/Room/B3 suites were not rerun without a changed dependency requiring it.

App APK SHA-256: `1d20905538622cfa308c9eb7de47568cb051cf7225347b9a61b36b797de51c67`

Instrumentation APK SHA-256: `18b5dedf9355ee89c353e3b3e9d25404a050eff06493653823dc1fece598a5b5`

Evidence: `/Users/tuanh/finance root/Test/android-order-cards-20260920/` (JUnit XML, lint XML, UI instrumentation output, artifacts.json).

Commands, from the Android repository:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' bash gradlew :app:testDebugUnitTest --tests 'com.example.finance_planning.PlanningAllTest' --tests 'com.example.finance_planning.PlanningActionPolicyTest' --tests 'com.example.finance_planning.LocalizationTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
'/Users/tuanh/Library/Android/sdk/platform-tools/adb' -t 7 install -r app/build/outputs/apk/debug/app-debug.apk
'/Users/tuanh/Library/Android/sdk/platform-tools/adb' -t 7 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
'/Users/tuanh/Library/Android/sdk/platform-tools/adb' -t 7 shell am instrument -w -r -e class com.example.finance_planning.PlanningOrderCardTest com.example.finance_planning.test/androidx.test.runner.AndroidJUnitRunner
```

This delivery does not close native B3 cloud/Google integration prerequisites and does not claim full Sprint acceptance. Tester coordination remains with BA.
