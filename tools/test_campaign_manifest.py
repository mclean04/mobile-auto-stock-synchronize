import copy
import hashlib
import importlib.util
import json
import unittest
import tempfile
import os
from campaign_oracles import evaluate
from test_campaign_review_regressions import planning_fixture
from pathlib import Path

spec = importlib.util.spec_from_file_location("campaign_runner", Path(__file__).with_name("run-finance-campaign.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ManifestTest(unittest.TestCase):
    def test_output_does_not_chmod_an_existing_shared_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            os.chmod(root, 0o755)
            with self.assertRaisesRegex(ValueError, "private_evidence_directory"):
                runner.write_private(root / "result.json", {})
            self.assertEqual(0o755, root.stat().st_mode & 0o777)
            runner.write_private(root / "private" / "result.json", {})
            self.assertEqual(0o600, (root / "private" / "result.json").stat().st_mode & 0o777)

    def fixture(self):
        policy = {"preparation_seconds": 3600, "cases": {"P4": {
            "required_evidence": ["p2_canonical", "p3_display", "p3_unapproved_denial"],
            "accept_before_seconds": 2100, "artifact_accept_before_seconds": 3300,
            "opens_after_seconds": 0, "ends_after_seconds": 5400}}}
        wire = {"campaign_id": "campaign", "sessions": [{"id": "planning", "kind": "PLANNING",
                "cases": ["P4"], "backend_policy": policy}], "case_records": [],
                "backend": {}, "android": {}, "resources": {"label": "Kế hoạch"}, "release": {}, "fixtures": []}
        return wire

    def index(self, wire):
        index = copy.deepcopy(wire)
        digest = hashlib.sha256(json.dumps(wire, sort_keys=True, separators=(",", ":"),
                                         ensure_ascii=False, allow_nan=False).encode()).hexdigest()
        index["sessions"][0]["preparation_manifest_hash"] = digest
        return index

    def test_hash_uses_immutable_wire_not_mutable_result_index(self):
        wire = self.fixture()
        index = self.index(wire)
        index["case_records"] = [{"case_id": "P3", "result": "PASS", "runtime_id": "observed-later"}]
        runner.validate_wire_manifest(wire, index, index["sessions"][0])
        changed = copy.deepcopy(wire)
        changed["resources"]["label"] = "changed after READY"
        with self.assertRaisesRegex(ValueError, "digest_mismatch"):
            runner.validate_wire_manifest(changed, index, index["sessions"][0])

    def test_future_hashes_and_conflated_p4_gates_rejected(self):
        for modify in (lambda p: p.update(artifact_accept_before_seconds=2100),
                       lambda p: p.update(required_evidence=[{"id": "p3_display", "sha256": "a" * 64}])):
            wire = self.fixture()
            modify(wire["sessions"][0]["backend_policy"]["cases"]["P4"])
            index = self.index(wire)
            with self.assertRaises(ValueError):
                runner.validate_wire_manifest(wire, index, index["sessions"][0])

    def test_expected_delta_and_cash_are_manifest_bound_and_missing_pin_is_unknown(self):
        wire = self.fixture()
        facts, bound = planning_fixture()
        payload = bound["payload"] | {"thesis": "reviewed $campaign_id/$session_id", "source_context": "$source_context"}
        digest = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        wire["fixtures"] = [{"id": "p4", "payload": payload, "sha256": digest, "cash_requirements": bound["cash_requirements"]}]
        wire["resources"]["source_context"] = bound["source_context"]
        resolved = runner.case_expectations(wire, wire["sessions"][0], "P4")
        self.assertEqual("reviewed campaign/planning", resolved["payload"]["thesis"])
        self.assertEqual("1102200", resolved["cash_requirements"]["required_cash_vnd"])
        del wire["fixtures"][0]["cash_requirements"]
        with self.assertRaises(KeyError):
            runner.case_expectations(wire, wire["sessions"][0], "P4")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); path = root / "actual.json"; path.write_text("{}")
            packet = {"case_id": "P4", "campaign_id": "campaign", "session_id": "planning",
                      "manifest_hash": "a" * 64, "server_timestamp": "2026-09-25T12:45:00Z", "facts": facts,
                      "truncated": False, "evidence": [{"path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}]}
            self.assertEqual("UNKNOWN", evaluate(packet, root)["result"])
            self.assertEqual("missing_immutable_case_expectations", evaluate(packet, root)["reason"])


if __name__ == "__main__":
    unittest.main()
