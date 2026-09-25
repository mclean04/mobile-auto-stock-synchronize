# DEC009 offline Android adaptation

Acceptance scope is `CODEX_LOCAL_SCHEDULED_REAL_QA_CHAIN`: actual local scheduled
producer → real QA Sheet/Calendar → Cloud QA Backend → Android → fake broker.
`cloud_scheduled_proof` is always `NOT_EVALUATED`. These offline checks neither
execute that chain nor attest to a scheduler platform. QA must inspect the original
records and any normalization. All 16 business oracles and cleanup gates remain.

## Frozen policy and append-only runtime evidence

Copy `DEC009-local-producer-policy.template.json` into immutable
`resources.local_producer_policy` before sealing the Backend wire manifest. Its
source is Scheduled's `DEC009-local-scheduled-package/local-package-manifest.json`.
It pins exact automation/project identity, schema/package hashes, per-case template
hashes, allowed `<TOKEN>` substitutions, and numeric relative timing. It contains
no actual firing time, rendered prompt/config hash, future artifact, or guard hash.
The existing wire/index equality check also covers this resource policy. The
template is not a runtime release or proof that a producer has run.

Use the agreed `finance-local-producer-evidence.v1` envelope in
`packet.facts.provenance`. Every structured evidence reference must also occur in
the packet's `evidence` collection, with a path inside its private evidence root.
Paths are resolved before reading; unavailable references remain UNKNOWN, and an
outer packet digest mismatch is FAIL. Normalization is RAW_BYTES or explicitly
CANONICAL_JSON_UTF8 (sorted keys, compact separators, UTF-8, no NaN). Hash integrity
does not authenticate authorship. UI links/booleans cannot replace file contents.

Required kinds are SAVED_CONFIG, BOUND_PROMPT, RELEASE_GUARD,
SCHEDULER_RUN_RECORD and ARTIFACT_READBACK; exactly one of each. INDIRECT_ACCEPTED
also requires BA_LINKAGE_REVIEW. The verifier parses each record and cross-checks
IDs, saved prompt, one-shot RRULE, times and artifact markers. Native or declared
normalized run evidence must identify a scheduled invocation. MANUAL is rejected
even when the surrounding envelope claims DIRECT or INDIRECT_ACCEPTED.

`packet.facts.provenance_runtime_binding` is a `{path,sha256}` reference to a
separate append-only runtime-ledger snapshot. Do not put that ledger into the
release guard: it includes later run/review evidence and would create a circular
or future-evidence dependency. All referenced sidecar documents below must also
be hash-checked packet evidence. Missing fields or unsupported parsers yield
UNKNOWN, never inferred values.

## Consumer field contract

This specifies the content consumed beneath Scheduled's shared reference schema.
Records normalized from platform/Backend evidence must retain their originals for
independent QA review. Do not manufacture unavailable fields to satisfy this schema.

- SAVED_CONFIG: raw TOML with `id`, `project_id`, `cwd` (or `project_path`), `kind`,
  `execution_environment`, `model`, `reasoning_effort`, `status`, `prompt`, `rrule`.
  Python 3.11+ `tomllib` or installed `tomli` is required. The consumer never installs
  dependencies. Missing parser/fields is UNKNOWN. An ACTIVE readback, exact local
  project/automation/model settings and a single `COUNT=1` are required.
- BOUND_PROMPT: exact rendered UTF-8 bytes. Include the frozen template and common
  schema/package source bytes in packet evidence as well. All template tokens must
  match the sealed allowlist and the BA-reviewed substitution map exactly. No
  unresolved token or edits outside substitutions are accepted.
- RELEASE_GUARD: released JSON with campaign/session/backend-manifest identity,
  `readiness_evidence` (list of referenced evidence hashes), `ba_release_evidence`
  (hash), and the monthly/daily role block: automation ID, case ID, bound prompt
  hash, `scheduled_at`, `accept_by`. An existing NOT_RELEASED scaffold is not proof.
  Planning still requires `safety_block_resolution=RESOLVED_SUPPORTED` and a
  hash-resolved `safety_resolution_evidence_sha256`; neither exists currently.
  A different actor/ID is not resolution. Independent notification N1/N2 may
  instead use the case-specific PO exception below while preserving the unresolved
  historical status. N3/N4 retain their downstream notification gates.
- Runtime binding: `schema_version=finance-local-run-binding.v1`, campaign/session/
  case/backend_manifest_sha256, automation_id, config_file_sha256, guard_sha256,
  server_status_sha256, scheduled_at, accept_by, rrule, one_shot=true,
  observed_next_run_at, substitutions, rendering_binding_sha256. P4 additionally
  needs p2_p3_gate_sha256; INDIRECT additionally needs indirect_review_sha256.
  Scheduling instants are derived from actual server T0 and the frozen offsets;
  saved next-run evidence must agree. The consumer checks COUNT=1 but independent
  QA still verifies the scheduler's interpretation and original next-run record.
- Server status sidecar: state=ACTIVE, campaign_id, session_id, manifest_hash,
  started_at from captured Backend readback. No client clock substitutes for T0.
- Rendering binding: reviewer_role=SYSTEM_BA, decision=ACCEPTED, exact campaign/
  session/case/backend_manifest_sha256, template_sha256, substitutions, and
  case_readback_sha256. The latter resolves trusted case readback with the same
  identity. QA reviews other canonical/time substitutions against that readback
  and the sealed fixtures. The consumer independently checks campaign/session,
  source generation and P4 intent/version tokens; business oracles still verify
  payload/cash/canonical invariants.
- P4 gate: campaign_id, session_id, p2_verified, p3_verified,
  p3_unapproved_denial_verified, verified_at, intent_id, expected_version. All
  booleans must be true, time before minute35, and saved config readback must be
  after this gate and before minute35. This document requires independent QA
  support from the P2/P3 records; owner assertion alone is not runtime acceptance.
- Run record: automation_id (may be null only for reviewed INDIRECT), project_id,
  task_record_id, run_id, invocation_kind=SCHEDULED, manual_invocation=false,
  campaign_id, session_id, case_id, backend_manifest_sha256, config_file_sha256,
  scheduled_at, started_at, completed_at, observed_status=COMPLETED,
  artifact_readback_sha256. Envelope and record must agree. Started/completed and
  artifact readback times must remain within the released case acceptance bound.
- Artifact readback: artifact_kind, resource_id, range_or_event_id,
  campaign_marker, session_marker, case_marker, automation_id, task_record_id,
  run_id, source_context, producer_values, backend_values. Planning additionally
  records producer_payload_sha256, equal to the existing Sheet payload fact.
  Notification additionally records calendar_id and event_id, matching frozen
  Calendar and the notification chain. Preserve actual row/Calendar values in
  the evidence; no local-only simulation may stand for connector readback.
- Indirect review: decision=INDIRECT_ACCEPTED, reviewer_role=SYSTEM_BA, rationale,
  automation_id, task_record_id, run_record_sha256, config_file_sha256,
  artifact_readback_sha256, last_run_before, last_run_after (changed and equal to
  observed run ID), manual_invocation=false, downstream_chain_evidence_sha256.
  This is additional reviewed linkage, not permission to relabel manual execution.

Absolute times/rendered config are recorded only after actual T0, and P4 rendering
after P2/P3. Later run/task/artifact/indirect-review records extend the separate ledger.
Do not change the frozen manifest hash or rewrite the guard to add future outputs.

## Exact APK candidate paths

`android.app_artifact_path` and `android.test_artifact_path` select reviewed local
APK files; the existing app_sha256/test_sha256 pins remain authoritative. Defaults
retain legacy output paths when explicit paths are absent. Explicit paths must
resolve inside this repository or sibling Planning/evidence; symlinks escaping
those roots fail. The reviewed main candidate may use
`Planning/evidence/DEC008-installed-272397a2/app-debug.apk` via its absolute path.
No path selection silently changes an accepted hash or supplies provenance.

The campaign runner passes paths and pins through both single/two-device B3
runners. Each campaign child requires a complete pair and verifies local hashes
before device work; existing installed-hash checks remain. The accepted test pin
is still 1fd1a4f9… and actual main candidate272397a2…. No APK is built or installed
by offline evaluation. The existing runtime/install gates remain mandatory.

## Independent notification disposition

The exact PO document SHA is
`0709cd1f986b3dceef53611c9432f0b5a0bce89184f345e8456b7d80961c2944`.
It narrows a product dependency, not any platform safety restriction. Under this
exception top-level `safety_block_resolution` stays
`NOT_ESTABLISHED_FOR_HISTORICAL_REJECTED_OPERATION`. Never mark it resolved merely
because a notification case is independent.

Guard `independent_notification_release` must contain scope
`CASE_SCOPED_NOTIFICATION`, the exact current N1 or N2 case/campaign/session/
backend_manifest_sha256, `po_disposition_path` exactly
`Planning/PO-DEC009-independent-runtime-disposition-20260925.md`, the pinned
`po_disposition_sha256`, nonempty hash-resolved structured `independence_evidence`,
`release_decision=RELEASED_FOR_INDEPENDENT_NOTIFICATION`, unchanged unresolved
`historical_planning_block_status`, and `new_rejection_status=NONE_OBSERVED`.
Proof binding repeats `release_scope=CASE_SCOPED_NOTIFICATION` and the PO hash.
The exact PO bytes and case-independence evidence must be packet evidence; a path
or approval boolean alone is insufficient. QA reviews the substantive independence.

The append-only runtime binding also requires
`new_rejection_status=NONE_OBSERVED`; a new generic rejection denies acceptance
even if the pre-run guard was released. Preserve the guard and append the outcome.
P1/P4 cannot use this exception; P1-P6 and fixture-dependent cases stay held under
their own disposition. All existing provenance, owner, clock, resource, and
readback checks still apply. These changes neither arm a schedule nor retry,
retarget, reproduce or reidentify the historical rejected fixture operation.
