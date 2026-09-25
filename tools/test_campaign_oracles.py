import hashlib
import tempfile
import unittest
from pathlib import Path

from campaign_oracles import checks, evaluate, campaign_result
from test_campaign_review_regressions import planning_fixture


class CampaignOracleTest(unittest.TestCase):
    def test_restoration_deadline_is_exclusive_and_monotonic(self):
        facts = {"before": {"source_id": "A", "source_generation": 2},
                 "switched": {"source_id": "B", "source_generation": 3},
                 "restored": {"source_id": "A", "source_generation": 4},
                 "restored_minute": 104.9, "server_confirmed": True, "final_state": "CLOSED"}
        self.assertTrue(all(checks("X6", facts).values()))
        self.assertFalse(checks("X6", facts | {"restored_minute": 105})["by_105"])
        facts["restored"]["source_generation"] = 2
        self.assertFalse(checks("X6", facts)["strictly_increasing"])

    def test_expiration_parses_timestamps_and_rejects_naive_clock(self):
        facts = {"server_admission_at": "2026-09-25T12:00:00+00:00", "expires_at": "2026-09-25T12:00:00Z",
                 "admitted": False, "new_provider_sends": 0, "denial_code": "expired", "audit_id": "a1"}
        self.assertTrue(all(checks("N4", facts).values()))
        with self.assertRaises(ValueError):
            checks("N4", facts | {"server_admission_at": "2026-09-25T12:00:00"})

    def test_evidence_hash_truncation_path_and_missing_facts_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "receipt.json"
            evidence.write_text("{}")
            packet = {"case_id": "N4", "campaign_id": "campaign", "session_id": "notification",
                      "manifest_hash": "a" * 64, "server_timestamp": "2026-09-25T12:00:00Z",
                      "truncated": False, "facts": {},
                      "evidence": [{"path": evidence.name, "sha256": hashlib.sha256(evidence.read_bytes()).hexdigest()}]}
            self.assertEqual("UNKNOWN", evaluate(packet, root)["result"])
            self.assertEqual("missing_or_truncated_evidence", evaluate(packet | {"truncated": True}, root)["reason"])
            packet["evidence"][0]["sha256"] = "b" * 64
            self.assertEqual("FAIL", evaluate(packet, root)["result"])
            packet["evidence"][0]["path"] = "../outside.json"
            self.assertEqual("evidence_path_unavailable", evaluate(packet, root)["reason"])
            self.assertEqual("NOT_RUN", evaluate(packet | {"result": "NOT_RUN"}, root)["result"])

    def test_p4_requires_both_verified_gates_and_preserves_canonical_identity(self):
        facts, bound = planning_fixture()
        self.assertTrue(all(checks("P4", facts, bound).values()))
        self.assertFalse(checks("P4", facts | {"p3_verified_minute": 35}, bound)["p3_gate_before_35"])
        self.assertFalse(checks("P4", facts | {"p3_unapproved_denial_verified": False}, bound)["p3_denial_verified"])
        self.assertFalse(checks("P4", facts | {"artifact_accepted_minute": 55}, bound)["artifact_before_55"])
        with self.assertRaises(KeyError):
            checks("P4", {k: v for k, v in facts.items() if k != "p2_verified_minute"}, bound)
        self.assertFalse(checks("P4", facts | {"submitted_payload_sha256": "", "sheet_payload_sha256": ""}, bound)["exact_sheet_readback"])

    def test_partial_or_duplicate_case_matrix_does_not_pass(self):
        self.assertEqual("NOT_RUN", campaign_result([]))
        self.assertEqual("BLOCKED", campaign_result([{"case_id": "P3", "result": "PASS"}]))


if __name__ == "__main__":
    unittest.main()
