"""Synthetic offline records only: never scheduler, cloud or device evidence."""
import copy
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import local_producer_provenance as local
from campaign_oracles import evaluate
from test_campaign_review_regressions import notification_fixture


def fixture(level="DIRECT", native_trigger="SCHEDULED", count=1, guard_status="RELEASED", safety="RESOLVED_SUPPORTED", case="N1"):
    blobs = {}
    def save(name, value):
        raw = value.encode() if isinstance(value, str) else json.dumps(value, sort_keys=True).encode()
        blobs[name] = raw
        return local.digest(raw)
    def ref(kind, name, media="application/json"):
        return {"kind": kind, "path": name, "sha256": local.digest(blobs[name]),
                "media_type": media, "normalization": "RAW_BYTES"}
    ids = {"campaign_id": "synthetic-campaign", "session_id": "synthetic-session", "case_id": case,
           "backend_manifest_sha256": "a" * 64}
    role = local.CASES[case]
    automation = local.AUTOMATIONS[role]
    minute = 5 if case == "N1" else 50
    scheduled = f"2026-09-25T12:{minute:02d}:00Z"
    started = f"2026-09-25T12:{minute:02d}:02Z"
    completed = f"2026-09-25T12:{minute+1:02d}:00Z"
    cutoff = "2026-09-25T12:19:59Z" if case == "N1" else "2026-09-25T13:04:59Z"
    template = "Campaign <CAMPAIGN_ID> Session <NOTIFICATION_SESSION_ID> Source <FRESH_SOURCE_GENERATION>"
    substitutions = {"<CAMPAIGN_ID>": ids["campaign_id"], "<NOTIFICATION_SESSION_ID>": ids["session_id"],
                     "<FRESH_SOURCE_GENERATION>": 3}
    prompt = template
    for key, value in substitutions.items():
        prompt = prompt.replace(key, str(value))
    template_hash, prompt_hash = save("template.txt", template), save("prompt.txt", prompt)
    rules = {case: {"template_sha256": template_hash, "allowed_substitutions": list(substitutions),
                   "trigger_offset_seconds": trigger, "artifact_accept_before_seconds": cutoff}
             for case, (trigger, cutoff) in {"P1": (300,1200), "P4": (2400,3300), "N1": (300,1200), "N2": (3000,3900)}.items()}
    package_hash = save("package.json", {"cases": {case: {"prompt_sha256": rule["template_sha256"],
        "allowed_substitutions": rule["allowed_substitutions"], "relative_timing": rule} for case,rule in rules.items()}})
    schema_hash = save("schema.json", {"synthetic_schema_fixture": True})
    policy = {"schema_version": "finance-local-producer-policy.v1", "evidence_schema_sha256": schema_hash,
              "package_manifest_sha256": package_hash, "project_id": local.PROJECT_ID,
              "project_path": local.PROJECT_PATH, "automations": local.AUTOMATIONS, "cases": rules}
    rrule = f"FREQ=DAILY;COUNT={count};BYHOUR=19;BYMINUTE={minute};BYSECOND=0"
    config = {"id": automation, "project_id": local.PROJECT_ID, "cwd": local.PROJECT_PATH,
              "kind": "cron", "execution_environment": "local", "model": "gpt-6-sol",
              "reasoning_effort": "medium", "status": "ACTIVE", "prompt": prompt, "rrule": rrule}
    config_hash = save("config.toml", "\n".join(key + " = " + json.dumps(value) for key,value in config.items()))
    artifact = {"artifact_kind": "NOTIFICATION_ROW_AND_EVENT", "resource_id": "qa-resource",
                "range_or_event_id": "qa-row/event", "campaign_marker": ids["campaign_id"],
                "session_marker": ids["session_id"], "case_marker": case}
    artifact_hash = save("artifact.json", artifact | {"task_record_id": "task-1", "run_id": "run-1",
                         "automation_id": automation, "producer_values": {"mode": "TEST"}, "backend_values": {},
                         "source_context": {"source_id": "qa-resource", "source_generation": 3},
                         "calendar_id": "qa-calendar", "event_id": "event"})
    native = ids | {"automation_id": automation if level == "DIRECT" else None,
                   "project_id": local.PROJECT_ID, "task_record_id": "task-1", "run_id": "run-1",
                   "invocation_kind": native_trigger, "manual_invocation": native_trigger == "MANUAL",
                   "started_at": started, "completed_at": completed,
                   "observed_status": "COMPLETED", "config_file_sha256": config_hash,
                   "scheduled_at": scheduled,
                   "artifact_readback_sha256": artifact_hash}
    native_hash = save("run.json", native)
    server_hash = save("server.json", {"state": "ACTIVE", "campaign_id": ids["campaign_id"],
        "session_id": ids["session_id"], "manifest_hash": ids["backend_manifest_sha256"], "started_at": "2026-09-25T12:00:00Z"})
    case_hash = save("case.json", ids | {"bindings": {"artifact": "synthetic"}})
    rendering_hash = save("rendering.json", ids | {"reviewer_role": "SYSTEM_BA", "decision": "ACCEPTED",
        "template_sha256": template_hash, "substitutions": substitutions, "case_readback_sha256": case_hash})
    ledger = ids | {"schema_version": "finance-local-run-binding.v1", "automation_id": automation,
        "config_file_sha256": config_hash, "server_status_sha256": server_hash,
        "scheduled_at": scheduled, "accept_by": cutoff,
        "rrule": rrule, "one_shot": True, "observed_next_run_at": scheduled,
        "substitutions": substitutions, "rendering_binding_sha256": rendering_hash}
    if level == "INDIRECT_ACCEPTED":
        ledger["indirect_review_sha256"] = save("review.json", {"decision": level, "reviewer_role": "SYSTEM_BA",
            "rationale": "Synthetic consistent offline fixture", "automation_id": automation,
            "task_record_id": "task-1", "run_record_sha256": native_hash, "config_file_sha256": config_hash,
            "artifact_readback_sha256": artifact_hash, "last_run_before": "run-0", "last_run_after": "run-1",
            "manual_invocation": False, "downstream_chain_evidence_sha256": artifact_hash})
    approval_hash = save("approval.json", {"decision": "PREPARATION_ACCEPTED", "synthetic": True})
    guard_hash = save("guard.json", ids | {"status": guard_status, "readiness_evidence": [approval_hash],
        "safety_block_resolution": safety, "safety_resolution_evidence_sha256": approval_hash,
        "ba_release_evidence": approval_hash, role: {"automation_id": automation,
        "case_id": case, "bound_prompt_sha256": prompt_hash, "scheduled_at": ledger["scheduled_at"], "accept_by": ledger["accept_by"]}})
    ledger_hash = save("ledger.json", ledger | {"guard_sha256": guard_hash})
    references = [ref("SAVED_CONFIG", "config.toml", "application/toml"), ref("BOUND_PROMPT", "prompt.txt", "text/plain"),
                  ref("RELEASE_GUARD", "guard.json"), ref("SCHEDULER_RUN_RECORD", "run.json"), ref("ARTIFACT_READBACK", "artifact.json")]
    if level == "INDIRECT_ACCEPTED": references.append(ref("BA_LINKAGE_REVIEW", "review.json"))
    proof = {"schema_version": "finance-local-producer-evidence.v1", "decision": "PO-DEC-009", "evidence_status": "RECORDED",
        "automation": {"automation_id": automation, "project_id": local.PROJECT_ID, "project_path": local.PROJECT_PATH,
            "kind": "cron", "execution_environment": "local", "model": "gpt-6-sol", "reasoning_effort": "medium",
            "saved_status": "ACTIVE", "saved_prompt_sha256": prompt_hash, "saved_rrule": rrule,
            "config_file_sha256": config_hash, "config_readback_at": "2026-09-25T12:01:00Z"},
        "binding": ids | {"guard_sha256": guard_hash, "bound_prompt_sha256": prompt_hash,
                          "release_scope": None, "po_disposition_sha256": None},
        "run": {key: native[key] for key in ("task_record_id", "started_at", "completed_at", "observed_status")}
               | {"invocation_kind": "SCHEDULED", "record_source": references[3]},
        "artifact": artifact | {"readback_sha256": artifact_hash, "readback_at": completed},
        "scheduled_provenance": {"result": level, "evidence_refs": references, "unsupported_fields": []}}
    packet = {"case_id": case, "campaign_id": ids["campaign_id"], "session_id": ids["session_id"],
        "manifest_hash": ids["backend_manifest_sha256"], "server_timestamp": "2026-09-25T12:39:00Z", "truncated": False,
        "facts": notification_fixture(case) | {"provenance": proof,
            "provenance_runtime_binding": {"path": "ledger.json", "sha256": ledger_hash}}}
    bound = {"local_producer_policy": policy, "calendar_id": "qa-calendar", "source_context": {"source_id": "qa-resource", "source_generation": 3},
             "policy": {"opens_after_seconds": 1500 if case == "N1" else 4200, "ends_after_seconds": 2400 if case == "N1" else 5400}}
    return packet, bound, blobs, {"PACKAGE_SHA256": package_hash, "SCHEMA_SHA256": schema_hash}


def independent_fixture(case="N1", release_changes=None, audit="NONE_OBSERVED"):
    data = fixture(safety=local.HISTORICAL_UNRESOLVED, case=case)
    packet, bound, blobs, pins = data
    proof = packet["facts"]["provenance"]
    guard = json.loads(blobs["guard.json"])
    blobs["po.md"] = b"Synthetic PO disposition for unit tests, not runtime authority."
    po_hash = local.digest(blobs["po.md"])
    pins["PO_DISPOSITION_SHA256"] = po_hash
    identity = {k: proof["binding"][k] for k in ("campaign_id", "session_id", "case_id", "backend_manifest_sha256")}
    blobs["independence.json"] = json.dumps(identity | {"synthetic_review": "independent notification"}).encode()
    guard["independent_notification_release"] = identity | {
        "scope": local.INDEPENDENT_SCOPE, "po_disposition_path": local.PO_DISPOSITION_PATH,
        "po_disposition_sha256": po_hash, "release_decision": "RELEASED_FOR_INDEPENDENT_NOTIFICATION",
        "historical_planning_block_status": local.HISTORICAL_UNRESOLVED,
        "new_rejection_status": "NONE_OBSERVED", "independence_evidence": [{"path": "independence.json",
            "sha256": local.digest(blobs["independence.json"]), "media_type": "application/json", "normalization": "RAW_BYTES"}]
    } | (release_changes or {})
    blobs["guard.json"] = json.dumps(guard,sort_keys=True).encode()
    guard_hash = local.digest(blobs["guard.json"])
    proof["binding"].update(guard_sha256=guard_hash, release_scope=local.INDEPENDENT_SCOPE, po_disposition_sha256=po_hash)
    next(r for r in proof["scheduled_provenance"]["evidence_refs"] if r["kind"] == "RELEASE_GUARD")["sha256"] = guard_hash
    ledger = json.loads(blobs["ledger.json"]) | {"guard_sha256": guard_hash, "new_rejection_status": audit}
    blobs["ledger.json"] = json.dumps(ledger,sort_keys=True).encode()
    packet["facts"]["provenance_runtime_binding"]["sha256"] = local.digest(blobs["ledger.json"])
    return data


def option2_fixture(**kwargs):
    data = fixture(**kwargs)
    packet, bound, blobs, pins = data
    proof = packet["facts"]["provenance"]
    ledger = json.loads(blobs["ledger.json"])
    blobs["schedule-po.md"] = b"Synthetic schedule PO authority, not runtime evidence."
    pins["SCHEDULE_PO_SHA256"] = local.digest(blobs["schedule-po.md"])
    def ref(kind, name):
        return {"kind": kind, "path": name, "sha256": local.digest(blobs[name]),
                "media_type": "application/json", "normalization": "RAW_BYTES"}
    admitted = (local.utc(proof["artifact"]["readback_at"]) + timedelta(seconds=1)).isoformat()
    blobs["admission.json"] = json.dumps({"campaign_id": packet["campaign_id"], "session_id": packet["session_id"],
        "case_id": packet["case_id"], "manifest_sha256": packet["manifest_hash"], "admitted_at": admitted,
        "bindings": {"event_id": "event"}, "evidence": {"artifact_readback": "synthetic-server-source-hash"}}).encode()
    sidecar = {"schema_version": "finance-dec009-local-schedule-runtime-sidecar.v1", "decision": "PO-DEC-009",
        "po_disposition": {"path": local.SCHEDULE_PO_PATH, "sha256": pins["SCHEDULE_PO_SHA256"],
            "policy": "OPTION_2_UNKNOWN_PREDICTION_FIELDS_ALLOWED"},
        "automation_id": ledger["automation_id"], "campaign_id": packet["campaign_id"],
        "session_id": packet["session_id"], "case_id": packet["case_id"],
        "intended_schedule": {"requested_timezone": "Asia/Ho_Chi_Minh", "intended_absolute_instant": ledger["scheduled_at"],
            "intended_local_wall_clock": local.utc(ledger["scheduled_at"]).astimezone(timezone(timedelta(hours=7))).isoformat(),
            "saved_rrule": ledger["rrule"], "saved_config_sha256": ledger["config_file_sha256"],
            "config_readback_ref": proof["scheduled_provenance"]["evidence_refs"][0]},
        "scheduler_observation": {"availability": "UNKNOWN_UNEXPOSED", "resolved_next_run_at": None,
            "resolved_timezone": None, "unavailability_reason": local.UNEXPOSED_REASON, "evidence_ref": None},
        "pre_arm_verification": {"supported_contract_verified": True, "intended_wall_clock_verified": True,
            "evidence_refs": [ref("BA_LINKAGE_REVIEW", "approval.json")]},
        "actual_scheduled_run_ref": proof["run"]["record_source"],
        "artifact_admission": {"case_window_start": "2026-09-25T12:00:00Z",
            "case_window_end": "2026-09-25T12:20:00Z" if packet["case_id"] == "N1" else "2026-09-25T13:05:00Z",
            "admitted_at": admitted, "in_window": True, "readback_ref": ref("ARTIFACT_READBACK", "admission.json"),
            "acceptance_result": "ACCEPTED"}}
    blobs["schedule.json"] = json.dumps(sidecar).encode()
    ledger["schedule_evidence_ref"] = ref("BA_LINKAGE_REVIEW", "schedule.json")
    ledger["observed_next_run_at"] = None
    blobs["ledger.json"] = json.dumps(ledger).encode()
    packet["facts"]["provenance_runtime_binding"]["sha256"] = local.digest(blobs["ledger.json"])
    return data


def change_sidecar(data, section, key, value):
    packet, _, blobs, _ = data
    sidecar = json.loads(blobs["schedule.json"])
    if section is None: sidecar[key] = value
    else: sidecar[section][key] = value
    blobs["schedule.json"] = json.dumps(sidecar).encode()
    ledger = json.loads(blobs["ledger.json"])
    ledger["schedule_evidence_ref"]["sha256"] = local.digest(blobs["schedule.json"])
    blobs["ledger.json"] = json.dumps(ledger).encode()
    packet["facts"]["provenance_runtime_binding"]["sha256"] = local.digest(blobs["ledger.json"])
    return data


class LocalProvenanceTest(unittest.TestCase):
    def review(self, data):
        packet, bound, blobs, pins = data
        with tempfile.TemporaryDirectory() as directory, patch.multiple(local, **pins):
            root = Path(directory)
            packet = copy.deepcopy(packet)
            packet["evidence"] = []
            for name, raw in blobs.items():
                (root/name).write_bytes(raw)
                packet["evidence"].append({"path": name, "sha256": local.digest(raw)})
            return evaluate(packet, root, bound)

    def test_direct_and_indirect_require_parsed_records_and_label_local_scope(self):
        for level in ("DIRECT", "INDIRECT_ACCEPTED"):
            result = self.review(fixture(level))
            self.assertEqual("PASS", result["result"], result)
            self.assertEqual(local.SCOPE, result["acceptance_scope"])
            self.assertEqual("NOT_EVALUATED", result["cloud_scheduled_proof"])

    def test_manual_cannot_hide_behind_direct_or_indirect_claim(self):
        for level in ("DIRECT", "INDIRECT_ACCEPTED"):
            result = self.review(fixture(level, "MANUAL"))
            self.assertEqual("FAIL", result["result"], result)

    def test_wrong_owner_marker_prompt_or_cloud_runtime_fails(self):
        for section, key, value in (("automation", "automation_id", local.AUTOMATIONS["monthly"]),
                ("automation", "project_id", "other-project"), ("automation", "execution_environment", "cloud"),
                ("artifact", "campaign_marker", "other-campaign"), ("binding", "case_id", "N2"),
                ("automation", "saved_prompt_sha256", "f"*64), ("automation", "saved_status", "PAUSED")):
            data = fixture(); data[0]["facts"]["provenance"][section][key] = value
            self.assertEqual("FAIL", self.review(data)["result"], (section,key))

    def test_missing_content_boolean_assertion_and_tampering_cannot_pass(self):
        data = fixture(); del data[2]["run.json"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        data = fixture(); data[2]["run.json"] = b'{}'
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        data = fixture(); data[0]["facts"]["provenance"] = {"level": "DIRECT", "task_id": "t", "run_id": "r", "platform_link": "link"}
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        data = fixture("INDIRECT_ACCEPTED"); del data[2]["review.json"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])

    def test_frozen_policy_has_no_absolute_time_or_prompt_hash_circularity(self):
        data=fixture()
        data[1]["local_producer_policy"]["cases"]["N1"]["scheduled_at"]="2026-09-25T12:05:00Z"
        self.assertEqual("UNKNOWN", self.review(data)["result"])

    def test_recurrence_or_unreleased_guard_cannot_prove_one_shot(self):
        for data in (fixture(count=2), fixture(guard_status="NOT_RELEASED")):
            self.assertEqual("FAIL", self.review(data)["result"])

    def test_runtime_ledger_is_separate_and_mandatory(self):
        data = fixture()
        guard = json.loads(data[2]["guard.json"])
        self.assertNotIn("runtime_binding", guard)
        del data[0]["facts"]["provenance_runtime_binding"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])

    def test_missing_toml_parser_remains_unknown(self):
        with patch.dict("sys.modules", {"tomllib": None, "tomli": None}):
            self.assertEqual("UNKNOWN", self.review(fixture())["result"])

    def test_historical_write_blocker_cannot_be_relabelled_by_local_schedule(self):
        self.assertEqual("FAIL", self.review(fixture(safety="NOT_ESTABLISHED_FOR_HISTORICAL_REJECTED_OPERATION"))["result"])

    def test_independent_n1_and_n2_preserve_unresolved_history_and_all_other_checks(self):
        for case in ("N1", "N2"):
            data = independent_fixture(case)
            self.assertEqual(local.HISTORICAL_UNRESOLVED, json.loads(data[2]["guard.json"])["safety_block_resolution"])
            result = self.review(data)
            self.assertEqual("PASS", result["result"], result)
            data[0]["facts"]["opened_receipt_accepted"] = False
            self.assertEqual("FAIL", self.review(data)["result"])

    def test_independent_release_requires_exact_po_case_and_hashed_independence(self):
        for change in ({"case_id": "N2"}, {"campaign_id": "other"}, {"po_disposition_sha256": "f"*64},
                       {"release_decision": "NOT_RELEASED"}, {"historical_planning_block_status": "RESOLVED_SUPPORTED"}):
            self.assertEqual("FAIL", self.review(independent_fixture(release_changes=change))["result"], change)
        for filename in ("po.md", "independence.json"):
            data = independent_fixture(); del data[2][filename]
            self.assertEqual("UNKNOWN", self.review(data)["result"])
        self.assertEqual("UNKNOWN", self.review(independent_fixture(release_changes={"independence_evidence": []}))["result"])

    def test_independent_notification_does_not_release_p1_or_p4(self):
        for case in ("P1", "P4"):
            packet, _, blobs, pins = independent_fixture()
            guard = json.loads(blobs["guard.json"])
            guard["independent_notification_release"]["case_id"] = case
            identity = {k: packet["facts"]["provenance"]["binding"][k] for k in
                        ("campaign_id", "session_id", "case_id", "backend_manifest_sha256")}
            identity["case_id"] = case
            with patch.multiple(local, **pins):
                self.assertFalse(local.safety_disposition(guard, packet["facts"]["provenance"]["binding"], identity, blobs))
                del guard["independent_notification_release"]
                self.assertFalse(local.safety_disposition(guard, {}, identity, blobs))

    def test_new_generic_rejection_stops_before_or_after_notification_release(self):
        self.assertEqual("FAIL", self.review(independent_fixture(release_changes={"new_rejection_status": "REJECTION_OBSERVED"}))["result"])
        self.assertEqual("FAIL", self.review(independent_fixture(audit="REJECTION_OBSERVED"))["result"])

    def test_unexposed_prediction_requires_actual_scheduled_origin_and_admission(self):
        for case in ("N1", "N2"):
            for level in ("DIRECT", "INDIRECT_ACCEPTED"):
                result = self.review(option2_fixture(case=case, level=level))
                self.assertEqual("PASS", result["result"], result)
        self.assertEqual("FAIL", self.review(option2_fixture(native_trigger="MANUAL"))["result"])
        data = option2_fixture(); del data[2]["run.json"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        data = change_sidecar(option2_fixture(), None, "actual_scheduled_run_ref", None)
        self.assertEqual("FAIL", self.review(data)["result"])

    def test_unexposed_prediction_requires_exact_po_and_honest_intent(self):
        data = option2_fixture(); del data[2]["schedule-po.md"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        for section, key, value in (
                ("po_disposition", "sha256", "f"*64),
                ("intended_schedule", "requested_timezone", "UTC"),
                ("intended_schedule", "intended_absolute_instant", "2026-09-25T12:06:00Z"),
                ("intended_schedule", "intended_local_wall_clock", "2026-09-25T12:05:00"),
                ("intended_schedule", "saved_rrule", "FREQ=DAILY;COUNT=1;BYHOUR=12;BYMINUTE=5;BYSECOND=0"),
                ("scheduler_observation", "resolved_next_run_at", "2026-09-25T12:05:00Z"),
                ("scheduler_observation", "unavailability_reason", "calculated by assistant")):
            self.assertEqual("FAIL", self.review(change_sidecar(option2_fixture(), section, key, value))["result"], key)

    def test_admission_is_server_artifact_window_not_later_processing_window(self):
        for case, processing_start, deadline in (("N1", "2026-09-25T12:25:00Z", "2026-09-25T12:20:00Z"),
                                                ("N2", "2026-09-25T13:10:00Z", "2026-09-25T13:05:00Z")):
            for key, value in (("case_window_start", processing_start), ("admitted_at", deadline),
                               ("acceptance_result", "LATE_AUDIT_ONLY")):
                self.assertEqual("FAIL", self.review(change_sidecar(option2_fixture(case=case),
                    "artifact_admission", key, value))["result"], (case,key))
        data = option2_fixture(); del data[2]["admission.json"]
        self.assertEqual("UNKNOWN", self.review(data)["result"])
        data = option2_fixture()
        data[0]["facts"]["provenance"]["artifact"]["readback_at"] = "2026-09-25T12:21:00Z"
        self.assertEqual("FAIL", self.review(data)["result"])


if __name__ == "__main__":
    unittest.main()
