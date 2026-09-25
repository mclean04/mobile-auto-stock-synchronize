import copy
import unittest
from campaign_oracles import checks


def planning_fixture():
    source = {"source_id": "source-A", "source_generation": 2}
    cash = {"currency": "VND", "principal_vnd": "1100000", "fee_reserve_vnd": "2200",
            "required_cash_vnd": "1102200", "fee_reserve_rate": "0.002", "policy_version": "cash-v1", "cash_only": True}
    row = {"plan_id": "p", "intent_id": "i", "version": 1, "source_context": source,
           "environment": "sandbox", "account": "test-account", "recipient_uid": "qa-user",
           "symbol": "TEST", "side": "BUY", "quantity": "100", "limit_price_vnd": "10000",
           "scheduled_at": "2026-09-25T12:10:00Z", "window_starts_at": "2026-09-25T12:00:00Z",
           "window_ends_at": "2026-09-25T13:30:00Z", "conditions": "fake only",
           "thesis": "original", "authoring_state": "TENTATIVE", "execution_state": "NOT_STARTED"}
    after = row | {"version": 2, "limit_price_vnd": "11000", "thesis": "reviewed update", "cash_requirements": cash}
    bound = {"source_context": source, "source_metadata_required": True,
             "payload": after, "cash_requirements": cash}
    facts = {"provenance": {"level": "DIRECT", "task_id": "t", "run_id": "r", "platform_link": "https://example.invalid/evidence"},
             "written_columns": list("ABCDEFGHIJKLMNOPQRSTU") + ["AA", "AB"], "source_metadata": source,
             "submitted_payload_sha256": "a" * 64, "sheet_payload_sha256": "a" * 64,
             "environment": "sandbox", "accepted_before_cutoff": True, "operation": "UPDATE", "producer_kind": "DAILY",
             "before": row, "after": after, "android": copy.deepcopy(after), "expected_version": 1,
             "p2_verified_minute": 29, "p3_verified_minute": 31, "p3_unapproved_denial_verified": True,
             "p4_admitted_minute": 34, "artifact_accepted_minute": 41, "android_verified_minute": 42}
    return facts, bound


def notification_fixture(case="N1"):
    return {"kind": "DAILY" if case == "N1" else "MONTHLY", "provenance": {
        "level": "DIRECT", "task_id": "t", "run_id": "r", "platform_link": "https://example.invalid/evidence"},
        "calendar_private": True, "calendar_transparent": True, "attendee_count": 0, "reminder_count": 0, "mode": "TEST",
        "event_ids": ["event"] * 3, "artifact_accepted_minute": 15 if case == "N1" else 60,
        "scan_admitted_minute": 26 if case == "N1" else 71, "device_reviewed_minute": 39 if case == "N1" else 89,
        "target_device_id": "tablet", "tablet_device_id": "tablet", "phone_registered": False,
        "backend_accepted": True, "provider_result": "ACCEPTED", "received_count": 1, "displayed_count": 1,
        "opened_count": 1, "opened_receipt_accepted": True, "export_schema_valid": True, "broker_calls": 0}


def lost_ack_fixture():
    return {"intent_id": "accepted-intent", "fixture_id": "x2", "first_pid": 1, "restart_pid": 2,
            "ack_lost_after_commit": True, "original_report_sha256": "a" * 64, "retry_report_sha256": "a" * 64,
            "total_broker_calls": 1, "restart_broker_calls": 0, "report_state": "REPORTED", "server_report_count": 1,
            "ambiguous": False}


class ReviewRegressions(unittest.TestCase):
    def test_source_metadata_columns_supported_without_backend_columns_or_duplicates(self):
        facts, bound = planning_fixture()
        self.assertTrue(all(checks("P4", facts, bound).values()))
        self.assertTrue(all(checks("P1", facts | {"operation": "CREATE", "producer_kind": "MONTHLY"}, bound).values()))
        self.assertFalse(checks("P4", facts | {"source_metadata": {"source_id": "other", "source_generation": 2}}, bound)["producer_columns_only"])
        for columns in (facts["written_columns"] + ["V"], facts["written_columns"] + ["A"],
                        facts["written_columns"] + ["AC"], list("ABCDEFGHIJKLMNOPQRSTU") + ["AA"],
                        list("ABCDEFGHIJKLMNOPQRSTU")):
            self.assertFalse(checks("P4", facts | {"written_columns": columns}, bound)["producer_columns_only"])
        optional = bound | {"source_metadata_required": False}
        self.assertTrue(checks("P4", facts | {"written_columns": list("ABCDEFGHIJKLMNOPQRSTU")}, optional)["producer_columns_only"])

    def test_p4_agreement_cannot_hide_missing_delta_or_cash_or_business_mutation(self):
        facts, bound = planning_fixture()
        # Omit source columns here so the old exact A:U gate cannot mask the P4 defect.
        facts["written_columns"] = list("ABCDEFGHIJKLMNOPQRSTU")
        bound["source_metadata_required"] = False
        for change in ({"limit_price_vnd": "10000"}, {"thesis": "original"}, {"version": 3},
                       {"cash_requirements": bound["cash_requirements"] | {"required_cash_vnd": "1002000"}},
                       {"quantity": "200"}, {"account": "other-account"}):
            changed = facts["after"] | change
            self.assertFalse(all(checks("P4", facts | {"after": changed, "android": changed}, bound).values()), change)

    def test_n1_rejects_scan_and_device_review_at40_or_later(self):
        facts = notification_fixture()
        bound = {"policy": {"opens_after_seconds": 1500, "ends_after_seconds": 2400}}
        self.assertTrue(all(checks("N1", facts, bound).values()))
        for change in ({"scan_admitted_minute": 40}, {"device_reviewed_minute": 40}, {"device_reviewed_minute": 89}):
            self.assertFalse(all(checks("N1", facts | change, bound).values()), change)

    def test_n2_honors_narrower_policy_and_requires_final_review(self):
        facts = notification_fixture("N2")
        bound = {"policy": {"opens_after_seconds": 4200, "ends_after_seconds": 4800}}
        self.assertFalse(all(checks("N2", facts, bound).values()))
        self.assertTrue(all(checks("N2", facts | {"device_reviewed_minute": 79}, bound).values()))
        self.assertFalse(all(checks("N2", facts | {"scan_admitted_minute": 80}, bound).values()))

    def test_x2_lost_ack_alone_cannot_pass_full_case(self):
        facts = lost_ack_fixture()
        self.assertFalse(all(checks("X2", facts).values()))
        ambiguity = {"intent_id": "independent-unknown-intent", "first_pid": 3, "restart_pid": 4,
                     "fixture_id": "x2-unknown",
                     "journal_before": "UNKNOWN", "journal_after": "UNKNOWN", "total_broker_calls": 1,
                     "restart_broker_calls": 0, "server_report_count": 0, "replay_denied": True,
                     "evidence_sha256": "b" * 64}
        bound = {"policy": {"fixture_ids": ["x2", "x2-unknown"]}}
        self.assertTrue(all(checks("X2", facts | {"ambiguity": ambiguity}, bound).values()))
        for bad in ({"restart_broker_calls": 1}, {"journal_after": "SUBMITTED"},
                    {"intent_id": "accepted-intent"}, {"replay_denied": False}, {"evidence_sha256": ""}):
            self.assertFalse(all(checks("X2", facts | {"ambiguity": ambiguity | bad}, bound).values()), bad)


if __name__ == "__main__":
    unittest.main()
