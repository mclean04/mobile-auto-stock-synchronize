"""Offline DEC-008 evidence checks. No execution, credential reads or implicit PASS."""
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING, InvalidOperation
from pathlib import Path

KINDS = {
    "PLANNING": tuple(f"P{i}" for i in range(1, 7)),
    "NOTIFICATION": tuple(f"N{i}" for i in range(1, 5)),
    "SAFETY_RECOVERY": tuple(f"X{i}" for i in range(1, 7)),
}
RESULTS = {"PASS", "FAIL", "BLOCKED", "NOT_RUN", "UNKNOWN"}
CANONICAL = ("plan_id", "intent_id", "version", "source_context", "symbol", "side",
             "quantity", "limit_price_vnd", "scheduled_at", "authoring_state", "execution_state")
SECRET_KEYS = {"token", "bearer", "fcm_token", "api_key", "secret", "password", "authorization",
               "headers", "otp", "tradingToken", "refresh_token", "id_token"}


def utc(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
        raise ValueError("UTC_timestamp_required")
    return parsed.astimezone(timezone.utc)


def same_digest(left, right):
    return isinstance(left, str) and re.fullmatch(r"[0-9a-f]{64}", left) is not None and left == right


def no_secrets(value):
    if isinstance(value, dict):
        if any(key.lower() in {s.lower() for s in SECRET_KEYS} for key in value):
            raise ValueError("secret_or_header_field_in_evidence")
        for item in value.values():
            no_secrets(item)
    elif isinstance(value, list):
        for item in value:
            no_secrets(item)


def canonical_equal(left, right):
    return all(key in left and key in right and left[key] == right[key] for key in CANONICAL)


def producer_columns(f, bound):
    columns = f["written_columns"]
    required = set("ABCDEFGHIJKLMNOPQRSTU")
    source_columns = {"AA", "AB"}
    written = set(columns)
    metadata = written & source_columns
    return (len(written) == len(columns) and required <= written <= required | source_columns and
            metadata in (set(), source_columns) and
            (not bound["source_metadata_required"] or metadata == source_columns) and
            (not metadata or f["source_metadata"] == bound["source_context"]))


def field_equal(key, left, right):
    numeric = {"quantity", "limit_price_vnd", "principal_vnd", "fee_reserve_vnd",
               "required_cash_vnd", "fee_reserve_rate"}
    return Decimal(str(left)) == Decimal(str(right)) if key in numeric else left == right


def p4_delta_checks(f, bound):
    before, after, android = f["before"], f["after"], f["android"]
    payload, cash = bound["payload"], bound["cash_requirements"]
    unchanged = ("plan_id", "intent_id", "source_context", "environment", "account", "recipient_uid",
                 "symbol", "side", "quantity", "scheduled_at", "window_starts_at", "window_ends_at",
                 "conditions", "authoring_state", "execution_state")
    fixed = ("environment", "account", "recipient_uid", "symbol", "side", "quantity", "conditions")
    cash_fields = ("currency", "principal_vnd", "fee_reserve_vnd", "required_cash_vnd",
                   "fee_reserve_rate", "policy_version", "cash_only")
    principal = Decimal(str(payload["quantity"])) * Decimal(str(payload["limit_price_vnd"]))
    fee = (principal * Decimal(str(cash["fee_reserve_rate"]))).quantize(Decimal("1"), rounding=ROUND_CEILING)
    return {
        "expected_next_version": type(after["version"]) is int and after["version"] == before["version"] + 1,
        "manifest_delta_applied": all(field_equal(k, after[k], payload[k]) for k in ("limit_price_vnd", "thesis"))
            and any(not field_equal(k, before[k], payload[k]) for k in ("limit_price_vnd", "thesis")),
        "unchanged_business_fields": all(field_equal(k, before[k], after[k]) for k in unchanged),
        "manifest_business_fields": all(field_equal(k, after[k], payload[k]) for k in fixed)
            and after["source_context"] == bound["source_context"],
        "manifest_cash_applied": all(field_equal(k, after["cash_requirements"][k], cash[k]) for k in cash_fields),
        "cash_pin_consistent": cash["currency"] == "VND" and cash["cash_only"] is True and
            Decimal(str(cash["principal_vnd"])) == principal and Decimal(str(cash["fee_reserve_vnd"])) == fee and
            Decimal(str(cash["required_cash_vnd"])) == principal + fee,
        "android_delta_and_cash": all(field_equal(k, android[k], after[k]) for k in unchanged + ("limit_price_vnd", "thesis", "version"))
            and all(field_equal(k, android["cash_requirements"][k], cash[k]) for k in cash_fields),
    }


def provenance(p):
    if p.get("level") == "DIRECT":
        return bool(p.get("task_id") and p.get("run_id") and p.get("platform_link"))
    if p.get("level") == "INDIRECT_ACCEPTED":
        return (p.get("ba_accepted") is True and p.get("manual_invocation") is False and
                p.get("saved_one_shot") is True and bool(p.get("last_run_before")) and
                bool(p.get("last_run_after")) and p["last_run_before"] != p["last_run_after"] and
                p.get("artifact_markers_match") is True and bool(p.get("task_id")))
    return False


def checks(case, f, bound=None):
    """Missing fields raise rather than defaulting absent counts to zero."""
    if case in {"P1", "P4"}:
        common = {
            "scheduled_provenance": provenance(f["provenance"]),
            "producer_columns_only": producer_columns(f, bound),
            "exact_sheet_readback": same_digest(f["submitted_payload_sha256"], f["sheet_payload_sha256"]),
            "sandbox": f["environment"] == "sandbox",
            "accepted_before_cutoff": f["accepted_before_cutoff"] is True,
        }
        if case == "P1":
            common["monthly_create"] = f["operation"] == "CREATE" and f["producer_kind"] == "MONTHLY"
        else:
            common.update(daily_update=f["operation"] == "UPDATE" and f["producer_kind"] == "DAILY",
                same_intent=f["before"]["intent_id"] == f["after"]["intent_id"],
                same_plan=f["before"]["plan_id"] == f["after"]["plan_id"],
                same_source=f["before"]["source_context"] == f["after"]["source_context"],
                expected_version=f["expected_version"] == f["before"]["version"],
                increased_version=f["after"]["version"] > f["before"]["version"],
                no_auto_approval=f["after"]["authoring_state"] == "TENTATIVE",
                android_matches=canonical_equal(f["after"], f["android"]),
                p2_gate_before_35=0 <= f["p2_verified_minute"] < 35,
                p3_gate_before_35=0 <= f["p3_verified_minute"] < 35,
                p3_denial_verified=f["p3_unapproved_denial_verified"] is True,
                admitted_before_35=0 <= f["p4_admitted_minute"] < 35,
                artifact_before_55=f["p4_admitted_minute"] <= f["artifact_accepted_minute"] < 55,
                review_before_90=f["artifact_accepted_minute"] <= f["android_verified_minute"] < 90)
            common.update(p4_delta_checks(f, bound))
        return common
    if case == "P2":
        return {"canonical_ids": bool(f["canonical"]["plan_id"] and f["canonical"]["intent_id"]),
                "version": type(f["canonical"]["version"]) is int and f["canonical"]["version"] > 0,
                "no_auto_approval": f["canonical"]["authoring_state"] == "TENTATIVE" and
                    f["canonical"]["execution_state"] == "NOT_STARTED",
                "backend_columns_only": f["written_columns"] == list("VWXYZ"),
                "proposal_link": f["proposal_id"] == f["canonical_proposal_id"]}
    if case == "P3":
        return {"exact_android_fields": canonical_equal(f["canonical"], f["android"]),
                "unapproved": f["canonical"]["authoring_state"] != "APPROVED",
                "ui_action_absent": f["action_visible"] is False,
                "denied": f["execution_denied"] is True,
                "zero_broker": f["broker_calls"] == 0}
    if case == "P5":
        return {"same_payload": same_digest(f["first_payload_sha256"], f["replay_payload_sha256"]),
                "deduplicated": f["duplicate"] is True,
                "canonical_unchanged": f["before"] == f["after"],
                "no_new_effect": f["new_intent_count"] == 0 and f["new_effect_count"] == 0}
    if case == "P6":
        return {"stale_version": f["expected_version"] < f["before"]["version"],
                "rejected": f["http_status"] == 409,
                "canonical_unchanged": f["before"] == f["after"]}
    if case in {"N1", "N2"}:
        policy = bound["policy"]
        opens = max(25 if case == "N1" else 70, policy["opens_after_seconds"] / 60)
        ends = min(40 if case == "N1" else 90, policy["ends_after_seconds"] / 60)
        return {"kind": f["kind"] == ("DAILY" if case == "N1" else "MONTHLY"),
                "scheduled_provenance": provenance(f["provenance"]),
                "private_test_artifact": f["calendar_private"] is True and f["calendar_transparent"] is True
                    and f["attendee_count"] == 0 and f["reminder_count"] == 0 and f["mode"] == "TEST",
                "exact_chain": len(set(f["event_ids"])) == 1 and len(f["event_ids"]) >= 3 and all(f["event_ids"]),
                "artifact_timely": 0 <= f["artifact_accepted_minute"] < (20 if case == "N1" else 65),
                "scan_timely": opens <= f["scan_admitted_minute"] < ends,
                "device_review_timely": f["scan_admitted_minute"] <= f["device_reviewed_minute"] < ends,
                "tablet_only": f["target_device_id"] == f["tablet_device_id"] and f["phone_registered"] is False,
                "server_accepted": f["backend_accepted"] is True,
                "provider_accepted": f["provider_result"] == "ACCEPTED",
                "device_received": f["received_count"] >= 1,
                "displayed": f["displayed_count"] >= 1,
                "opened": f["opened_count"] >= 1 and f["opened_receipt_accepted"] is True,
                "private_export": f["export_schema_valid"] is True,
                "no_broker": f["broker_calls"] == 0}
    if case == "N3":
        return {"same_artifact": same_digest(f["before_artifact_sha256"], f["after_artifact_sha256"]),
                "one_logical_notification": f["logical_event_count"] == 1 and f["local_inbox_rows"] == 1,
                "no_new_provider_send": f["additional_provider_sends"] == 0,
                "delivery_attempts_accounted": f["delivery_attempt_count"] >= 1 and
                    f["receipt_attempt_count"] >= f["unique_receipt_count"] and
                    f["redelivery_contract_reviewed"] is True}
    if case == "N4":
        return {"expired": utc(f["server_admission_at"]) >= utc(f["expires_at"]),
                "denied": f["admitted"] is False, "zero_new_send": f["new_provider_sends"] == 0,
                "audit_reason": bool(f["denial_code"] and f["audit_id"])}
    if case == "X1":
        return {"host_oracle": f["host_oracle"]["pass"] is True,
                "distinct_devices": len(set(f["device_ids"])) == 2,
                "distinct_requests": len(set(f["request_ids"])) == 2,
                "exact_claim_action_report": f["accepted_claims"] == f["broker_calls"] == f["accepted_reports"] == 1,
                "explicit_conflict": f["conflicts"] == 1 and f["conflict_code"] == "intent_execution_claimed",
                "server_host_match": f["server_winner_device"] == f["host_winner_device"] and
                    f["server_preflight_id"] == f["host_preflight_id"] and f["server_order_id"] == f["host_order_id"],
                "fake_route": f["route"] == "ANDROID_INJECTED_FAKE_ONLY"}
    if case == "X2":
        result = {"restart": f["first_pid"] != f["restart_pid"],
                "lost_ack": f["ack_lost_after_commit"] is True,
                "immutable_retry": same_digest(f["original_report_sha256"], f["retry_report_sha256"]),
                "one_economic_action": f["total_broker_calls"] == 1 and f["restart_broker_calls"] == 0,
                "durable_reconciled": f["report_state"] == "REPORTED" and f["server_report_count"] == 1,
                "not_ambiguous": f["ambiguous"] is False}
        a = f.get("ambiguity")
        result["ambiguity_subcase_present"] = isinstance(a, dict) and bool(a)
        if result["ambiguity_subcase_present"]:
            result.update(
                ambiguity_independent=a["intent_id"] != f["intent_id"] and bool(a["intent_id"]) and
                    a["fixture_id"] != f["fixture_id"] and
                    {a["fixture_id"], f["fixture_id"]}.issubset(bound["policy"]["fixture_ids"]),
                ambiguity_restart=a["first_pid"] != a["restart_pid"],
                ambiguity_durable_unknown=a["journal_before"] == a["journal_after"] == "UNKNOWN",
                ambiguity_no_retry=a["total_broker_calls"] == 1 and a["restart_broker_calls"] == 0,
                ambiguity_no_report=a["server_report_count"] == 0,
                ambiguity_replay_denied=a["replay_denied"] is True,
                ambiguity_evidence=same_digest(a["evidence_sha256"], a["evidence_sha256"]))
        return result
    if case == "X3":
        return {name: f[name]["denied"] is True and f[name]["broker_calls"] == 0 and
                bool(f[name]["reason"]) for name in ("wrong_owner", "unapproved", "out_of_window")}
    if case == "X4":
        return {"two_snapshots": len(set(f["observer_device_ids"])) == 2,
                "source_switched": f["before"]["source_id"] != f["switched"]["source_id"] and
                    f["switched"]["source_generation"] > f["before"]["source_generation"],
                "retained_snapshot": f["attempt_source"] == f["before"],
                "operator_actor": f["switch_actor"] == "QA_OPERATOR",
                "denied_before_broker": f["denied"] is True and f["broker_calls"] == f["reports"] == 0}
    if case == "X5":
        return {"original_source": f["original_source"] == f["report_source"] != f["active_source"],
                "immutable_report": same_digest(f["original_payload_sha256"], f["retried_payload_sha256"]),
                "quarantined": f["destination"] == "QUARANTINED_SOURCE_CHANGED",
                "no_new_source_projection": f["new_source_order_count"] == 0,
                "no_broker_retry": f["restart_broker_calls"] == 0 and f["total_broker_calls"] == 1}
    if case == "X6":
        return {"source_restored": f["before"]["source_id"] == f["restored"]["source_id"],
                "strictly_increasing": f["before"]["source_generation"] < f["switched"]["source_generation"] <
                    f["restored"]["source_generation"],
                "by_105": 0 <= f["restored_minute"] < 105,
                "server_readback": f["server_confirmed"] is True,
                "closed": f["final_state"] in {"CLOSED", "EXPIRED"}}
    raise ValueError("unknown_case")


def evaluate(packet: dict, evidence_root: Path, bound=None) -> dict:
    """Verify referenced files before checking owner-produced facts. QA still reviews provenance."""
    no_secrets(packet)
    case = packet["case_id"]
    if case not in sum((list(ids) for ids in KINDS.values()), []):
        raise ValueError("unknown_case")
    base = {"case_id": case, "campaign_id": packet.get("campaign_id"),
            "session_id": packet.get("session_id"), "manifest_hash": packet.get("manifest_hash"),
            "checks": {}, "result": "UNKNOWN"}
    for key in ("campaign_id", "session_id", "manifest_hash", "server_timestamp"):
        if not packet.get(key):
            return base | {"reason": "missing_" + key}
    try:
        utc(packet["server_timestamp"])
        if not re.fullmatch(r"[0-9a-f]{64}", packet["manifest_hash"]):
            raise ValueError("manifest_digest")
    except (ValueError, TypeError, AttributeError):
        return base | {"reason": "invalid_identity_or_server_timestamp"}
    if packet.get("result") in {"FAIL", "BLOCKED", "NOT_RUN", "UNKNOWN"}:
        return base | {"result": packet["result"], "reason": packet.get("reason_code", "OWNER_NOT_EXECUTED")}
    refs = packet.get("evidence", [])
    if not refs or packet.get("truncated") is not False:
        return base | {"reason": "missing_or_truncated_evidence"}
    root = evidence_root.resolve()
    for ref in refs:
        path = (root / ref["path"]).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            return base | {"reason": "evidence_path_unavailable"}
        if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            return base | {"result": "FAIL", "reason": "evidence_digest_mismatch"}
    try:
        if case in {"P1", "P4", "N1", "N2", "X2"} and bound is None:
            return base | {"reason": "missing_immutable_case_expectations"}
        outcomes = checks(case, packet["facts"], bound)
        if case == "X2" and packet["facts"].get("ambiguity"):
            outcomes["ambiguity_evidence_linked"] = packet["facts"]["ambiguity"]["evidence_sha256"] in {
                ref["sha256"] for ref in refs}
    except (KeyError, TypeError, ValueError, IndexError, InvalidOperation):
        return base | {"reason": "incomplete_or_malformed_facts"}
    return base | {"checks": outcomes, "result": "PASS" if all(outcomes.values()) else "FAIL",
                   "basis": "OWNER_FACTS_WITH_HASHED_EVIDENCE_REQUIRES_INDEPENDENT_QA_REVIEW",
                   "observed_event_id": packet["facts"]["event_ids"][0] if case in {"N1", "N2"} else None,
                   "evidence": refs, "server_timestamp": packet["server_timestamp"]}


def campaign_result(records):
    expected = set(sum((list(ids) for ids in KINDS.values()), []))
    ids = [r["case_id"] for r in records]
    notifications = [r.get("observed_event_id") for r in records if r["case_id"] in {"N1", "N2"}]
    return "PASS" if set(ids) == expected and len(ids) == len(expected) and all(
        r["result"] == "PASS" for r in records) and len(set(notifications)) == 2 and all(notifications) \
        else "NOT_RUN" if not records else "BLOCKED"
