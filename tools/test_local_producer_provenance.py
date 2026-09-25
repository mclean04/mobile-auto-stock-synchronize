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


def fixture(level="DIRECT", native_trigger="SCHEDULED", count=1, guard_status="RELEASED", safety="RESOLVED_SUPPORTED"):
    blobs = {}
    def save(name, value):
        raw = value.encode() if isinstance(value, str) else json.dumps(value, sort_keys=True).encode()
        blobs[name] = raw
        return local.digest(raw)
    def ref(kind, name, media="application/json"):
        return {"kind": kind, "path": name, "sha256": local.digest(blobs[name]),
                "media_type": media, "normalization": "RAW_BYTES"}
    ids = {"campaign_id": "synthetic-campaign", "session_id": "synthetic-session", "case_id": "N1",
           "backend_manifest_sha256": "a" * 64}
    role, automation = "daily", local.AUTOMATIONS["daily"]
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
    rrule = f"FREQ=DAILY;COUNT={count};BYHOUR=12;BYMINUTE=5;BYSECOND=0"
    config = {"id": automation, "project_id": local.PROJECT_ID, "cwd": local.PROJECT_PATH,
              "kind": "cron", "execution_environment": "local", "model": "gpt-6-sol",
              "reasoning_effort": "medium", "status": "ACTIVE", "prompt": prompt, "rrule": rrule}
    config_hash = save("config.toml", "\n".join(key + " = " + json.dumps(value) for key,value in config.items()))
    artifact = {"artifact_kind": "NOTIFICATION_ROW_AND_EVENT", "resource_id": "qa-resource",
                "range_or_event_id": "qa-row/event", "campaign_marker": ids["campaign_id"],
                "session_marker": ids["session_id"], "case_marker": "N1"}
    artifact_hash = save("artifact.json", artifact | {"task_record_id": "task-1", "run_id": "run-1",
                         "automation_id": automation, "producer_values": {"mode": "TEST"}, "backend_values": {},
                         "source_context": {"source_id": "qa-resource", "source_generation": 3},
                         "calendar_id": "qa-calendar", "event_id": "event"})
    native = ids | {"automation_id": automation if level == "DIRECT" else None,
                   "project_id": local.PROJECT_ID, "task_record_id": "task-1", "run_id": "run-1",
                   "invocation_kind": native_trigger, "manual_invocation": native_trigger == "MANUAL",
                   "started_at": "2026-09-25T12:05:02Z", "completed_at": "2026-09-25T12:06:00Z",
                   "observed_status": "COMPLETED", "config_file_sha256": config_hash,
                   "scheduled_at": "2026-09-25T12:05:00Z",
                   "artifact_readback_sha256": artifact_hash}
    native_hash = save("run.json", native)
    server_hash = save("server.json", {"state": "ACTIVE", "campaign_id": ids["campaign_id"],
        "session_id": ids["session_id"], "manifest_hash": ids["backend_manifest_sha256"], "started_at": "2026-09-25T12:00:00Z"})
    case_hash = save("case.json", ids | {"bindings": {"artifact": "synthetic"}})
    rendering_hash = save("rendering.json", ids | {"reviewer_role": "SYSTEM_BA", "decision": "ACCEPTED",
        "template_sha256": template_hash, "substitutions": substitutions, "case_readback_sha256": case_hash})
    ledger = ids | {"schema_version": "finance-local-run-binding.v1", "automation_id": automation,
        "config_file_sha256": config_hash, "server_status_sha256": server_hash,
        "scheduled_at": "2026-09-25T12:05:00Z", "accept_by": "2026-09-25T12:19:59Z",
        "rrule": rrule, "one_shot": True, "observed_next_run_at": "2026-09-25T12:05:00Z",
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
        "case_id": "N1", "bound_prompt_sha256": prompt_hash, "scheduled_at": ledger["scheduled_at"], "accept_by": ledger["accept_by"]}})
    ledger_hash = save("ledger.json", ledger | {"guard_sha256": guard_hash})
    references = [ref("SAVED_CONFIG", "config.toml", "application/toml"), ref("BOUND_PROMPT", "prompt.txt", "text/plain"),
                  ref("RELEASE_GUARD", "guard.json"), ref("SCHEDULER_RUN_RECORD", "run.json"), ref("ARTIFACT_READBACK", "artifact.json")]
    if level == "INDIRECT_ACCEPTED": references.append(ref("BA_LINKAGE_REVIEW", "review.json"))
    proof = {"schema_version": "finance-local-producer-evidence.v1", "decision": "PO-DEC-009", "evidence_status": "RECORDED",
        "automation": {"automation_id": automation, "project_id": local.PROJECT_ID, "project_path": local.PROJECT_PATH,
            "kind": "cron", "execution_environment": "local", "model": "gpt-6-sol", "reasoning_effort": "medium",
            "saved_status": "ACTIVE", "saved_prompt_sha256": prompt_hash, "saved_rrule": rrule,
            "config_file_sha256": config_hash, "config_readback_at": "2026-09-25T12:01:00Z"},
        "binding": ids | {"guard_sha256": guard_hash, "bound_prompt_sha256": prompt_hash},
        "run": {key: native[key] for key in ("task_record_id", "started_at", "completed_at", "observed_status")}
               | {"invocation_kind": "SCHEDULED", "record_source": references[3]},
        "artifact": artifact | {"readback_sha256": artifact_hash, "readback_at": "2026-09-25T12:06:00Z"},
        "scheduled_provenance": {"result": level, "evidence_refs": references, "unsupported_fields": []}}
    packet = {"case_id": "N1", "campaign_id": ids["campaign_id"], "session_id": ids["session_id"],
        "manifest_hash": ids["backend_manifest_sha256"], "server_timestamp": "2026-09-25T12:39:00Z", "truncated": False,
        "facts": notification_fixture() | {"provenance": proof,
            "provenance_runtime_binding": {"path": "ledger.json", "sha256": ledger_hash}}}
    bound = {"local_producer_policy": policy, "calendar_id": "qa-calendar", "source_context": {"source_id": "qa-resource", "source_generation": 3},
             "policy": {"opens_after_seconds": 1500, "ends_after_seconds": 2400}}
    return packet, bound, blobs, {"PACKAGE_SHA256": package_hash, "SCHEMA_SHA256": schema_hash}


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


if __name__ == "__main__":
    unittest.main()
