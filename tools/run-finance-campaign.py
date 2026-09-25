#!/usr/bin/env python3
"""DEC-008 local evidence index and fixed Android adapters. Never schedules or arms QA."""
import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

from campaign_oracles import KINDS, campaign_result, evaluate, no_secrets
from campaign_artifacts import resolve_artifacts, artifact_arguments
from local_producer_provenance import CASES as PRODUCER_CASES, SCOPE, policy_valid

ROOT = Path(__file__).resolve().parents[1]
PHASES = {
    "P3": ("planning_display",), "P4": ("planning_display",),
    "X1": ("concurrent",), "X2": ("accepted", "resume_accepted", "unknown", "kill_unknown", "resume_unknown"),
    "X3": ("denied",), "X4": ("snapshot", "stale"),
    "X5": ("late", "resume_late"),
}


def manifest_errors(manifest):
    errors = []
    if manifest.get("schema_version") != "finance-e2e-campaign.v1":
        errors.append("manifest_schema")
    no_secrets(manifest)
    if not policy_valid(manifest.get("resources", {}).get("local_producer_policy")):
        errors.append("missing_or_invalid_local_producer_policy")
    for section, keys in {
        "backend": ("commit", "tree", "image_digest", "service", "job", "rollback_evidence"),
        "android": ("commit", "app_sha256", "test_sha256", "signing_sha256", "devices", "rollback_evidence"),
        "resources": ("test_tasks", "qa_sheets", "calendar_id", "source_context", "access_evidence"),
        "release": ("ba_handoffs_accepted", "po_runtime_release", "cost_authority"),
    }.items():
        for key in keys:
            if not manifest.get(section, {}).get(key):
                errors.append(f"missing_{section}.{key}")
    if not manifest.get("campaign_id"):
        errors.append("missing_campaign_id")
    sessions = manifest.get("sessions", [])
    if len(sessions) != 3 or {s.get("kind") for s in sessions} != set(KINDS):
        errors.append("three_session_kinds_required")
    ids = [s.get("id") for s in sessions]
    if None in ids or len(set(ids)) != 3:
        errors.append("distinct_session_ids_required")
    for s in sessions:
        if set(s.get("cases", [])) != set(KINDS.get(s.get("kind"), ())):
            errors.append("case_matrix_mismatch")
        if not re.fullmatch(r"[0-9a-f]{64}", str(s.get("preparation_manifest_hash", ""))):
            errors.append("unfrozen_session_manifest")
    return sorted(set(errors))


def validate_wire_manifest(wire, index, session):
    """Hash the exact immutable Backend input, never the BA result index."""
    no_secrets(wire)
    digest = hashlib.sha256(json.dumps(wire, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False, allow_nan=False).encode()).hexdigest()
    if digest != session["preparation_manifest_hash"]:
        raise ValueError("wire_manifest_digest_mismatch")
    if wire.get("campaign_id") != index["campaign_id"]:
        raise ValueError("wire_campaign_mismatch")
    for key in ("backend", "android", "resources", "release", "fixtures"):
        if wire.get(key) != index.get(key):
            raise ValueError("wire_index_pins_mismatch_" + key)
    if wire.get("case_records"):
        raise ValueError("runtime_ledger_must_be_separate")
    frozen = next(s for s in wire["sessions"] if s["id"] == session["id"])
    if frozen["kind"] != session["kind"] or frozen["cases"] != session["cases"]:
        raise ValueError("wire_session_mismatch")
    policy = frozen["backend_policy"]
    if not 1 <= policy["preparation_seconds"] <= 3600:
        raise ValueError("wire_preparation_deadline")
    for case, bound in policy["cases"].items():
        if any(not isinstance(e, str) for e in bound.get("required_evidence", [])):
            raise ValueError("future_evidence_hash_in_manifest")
        if case == "P4" and (bound.get("accept_before_seconds") != 2100 or
                bound.get("artifact_accept_before_seconds") != 3300 or
                not {"p2_canonical", "p3_display", "p3_unapproved_denial"}.issubset(bound["required_evidence"])):
            raise ValueError("p4_separate_gates_required")
        if case in {"N1", "N2"}:
            accept, process = (1200, 1500) if case == "N1" else (3900, 4200)
            cap = 2400 if case == "N1" else 5400
            if bound.get("accept_before_seconds") != accept or not process <= bound["opens_after_seconds"] < bound["ends_after_seconds"] <= cap:
                raise ValueError("notification_separate_gates_required")
    return digest


def write_private(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if stat.S_IMODE(path.parent.stat().st_mode) & 0o077:
        raise ValueError("use_a_private_evidence_directory")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)


def case_expectations(wire, session, case):
    frozen = next(s for s in wire["sessions"] if s["id"] == session["id"])
    bound = {"policy": frozen["backend_policy"]["cases"][case]}
    if case in PRODUCER_CASES:
        bound.update(local_producer_policy=wire["resources"].get("local_producer_policy"),
                     source_context=wire["resources"]["source_context"],
                     calendar_id=wire["resources"].get("calendar_id"))
    if case in {"P1", "P4"}:
        fixture = next(f for f in wire["fixtures"] if f["id"] == case.lower())
        payload = dict(fixture["payload"])
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"),
                                         ensure_ascii=False, allow_nan=False).encode()).hexdigest()
        if fixture["sha256"] != digest:
            raise ValueError("fixture_digest_mismatch")
        source = wire["resources"]["source_context"]
        if "source_context" in payload and payload["source_context"] not in ("$source_context", source):
            raise ValueError("fixture_source_mismatch")
        if "thesis" in payload:
            payload["thesis"] = payload["thesis"].replace("$campaign_id", wire["campaign_id"]).replace("$session_id", session["id"])
            if "$" in payload["thesis"]:
                raise ValueError("unresolved_thesis_binding")
        bound.update(payload=payload, source_context=source,
                     source_metadata_required="source_context" in payload)
        if case == "P4":
            bound["cash_requirements"] = fixture["cash_requirements"]
    return bound


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("inspect")
    review = sub.add_parser("evaluate")
    review.add_argument("--packet", type=Path, action="append", required=True)
    review.add_argument("--evidence-root", type=Path, required=True)
    review.add_argument("--wire-manifest", type=Path, required=True)
    run = sub.add_parser("device-phase")
    run.add_argument("--case", required=True, choices=PHASES)
    run.add_argument("--phase", required=True)
    run.add_argument("--config", type=Path, required=True)
    run.add_argument("--wire-manifest", type=Path, required=True)
    run.add_argument("--transport", required=True)
    run.add_argument("--second-config", type=Path)
    run.add_argument("--second-transport")
    run.add_argument("--session-start-utc", required=True)
    run.add_argument("--session-end-utc", required=True)
    run.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    errors = manifest_errors(manifest)
    if args.command == "inspect":
        write_private(args.output, {"result": "BLOCKED" if errors else "READY_FOR_REVIEW",
            "errors": errors, "cases": KINDS, "runtime_started": False,
            "acceptance_scope": SCOPE, "cloud_scheduled_proof": "NOT_EVALUATED"})
        return
    if args.command == "evaluate":
        wire = json.loads(args.wire_manifest.read_text())
        records = []
        for path in args.packet:
            packet = json.loads(path.read_text())
            if packet.get("campaign_id") != manifest["campaign_id"]:
                raise ValueError("packet_campaign_mismatch")
            session = next(s for s in manifest["sessions"] if packet["case_id"] in s["cases"])
            if (packet.get("session_id") != session["id"] or
                    packet.get("manifest_hash") != session["preparation_manifest_hash"]):
                raise ValueError("packet_session_or_manifest_mismatch")
            validate_wire_manifest(wire, manifest, session)
            try:
                bound = case_expectations(wire, session, packet["case_id"])
            except (KeyError, ValueError, StopIteration):
                bound = None  # Missing immutable expectations remain UNKNOWN, never inferred from after-state.
            records.append(evaluate(packet, args.evidence_root, bound))
        result = campaign_result(records)
        closure = manifest.get("closure", {})
        cleanup = all(closure.get(key) is True for key in (
            "test_tasks_disabled", "qa_disarmed", "source_restored", "devices_restored", "logs_reconciled"))
        write_private(args.output, {"campaign_id": manifest["campaign_id"], "cases": records,
            "acceptance_scope": SCOPE, "cloud_scheduled_proof": "NOT_EVALUATED",
            "matrix_result": result, "cleanup_verified": cleanup,
            "ready_for_ba_review": result == "PASS" and cleanup and not errors,
            "sprint_closed": False, "manifest_errors": errors})
        return
    if errors:
        raise ValueError("runtime_manifest_not_ready:" + ",".join(errors))
    if args.phase not in PHASES[args.case]:
        raise ValueError("phase_not_in_case")
    session = next(s for s in manifest["sessions"] if args.case in s["cases"])
    validate_wire_manifest(json.loads(args.wire_manifest.read_text()), manifest, session)
    configs = [args.config] + ([args.second_config] if args.second_config else [])
    for path in configs:
        cfg = json.loads(path.read_text())
        for key, expected in (("campaign_id", manifest["campaign_id"]), ("session_id", session["id"]),
                              ("manifest_hash", session["preparation_manifest_hash"]),
                              ("session_kind", session["kind"]), ("case_id", args.case)):
            if cfg.get(key) != expected:
                raise ValueError("config_" + key + "_mismatch")
        devices = {d["device_id"] if isinstance(d, dict) else d for d in manifest["android"]["devices"]}
        if cfg["device_id"] not in devices:
            raise ValueError("device_not_in_manifest")
        if args.case == "X2" and args.phase in {"unknown", "kill_unknown", "resume_unknown"}:
            intents = cfg["intents"]
            if not intents.get("unknown") or not intents.get("accepted") or intents["unknown"] == intents["accepted"]:
                raise ValueError("independent_ambiguity_intent_required")
    artifacts = resolve_artifacts(manifest["android"])
    if args.case == "X1":
        if not args.second_config or not args.second_transport:
            raise ValueError("two_actual_devices_required")
        command = [sys.executable, str(ROOT / "tools/run-b3-two-device.py"),
                   "--device-one-config", str(args.config), "--device-two-config", str(args.second_config),
                   "--transport-one", args.transport, "--transport-two", args.second_transport]
    else:
        command = [sys.executable, str(ROOT / "tools/run-b3-device.py"), "--config", str(args.config),
                   "--transport-id", args.transport, "--phase", args.phase]
    command += ["--output", str(args.output), "--session-start-utc", args.session_start_utc,
                "--session-end-utc", args.session_end_utc, "--adb", args.adb]
    command += artifact_arguments(artifacts)
    # One explicit phase per invocation: never catch up, schedule, retry an economic action or choose T0.
    raise SystemExit(subprocess.run(command, check=False).returncode)


if __name__ == "__main__":
    main()
