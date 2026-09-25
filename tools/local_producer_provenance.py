"""DEC009 offline local-scheduler evidence consumer; never calls a scheduler."""
import hashlib
import json
import re
from datetime import datetime, timezone, timedelta

CASES = {"P1": "monthly", "P4": "daily", "N1": "daily", "N2": "monthly"}
AUTOMATIONS = {"monthly": "k-ho-ch-c-phi-u-dnse-th-ng-test",
               "daily": "ki-m-tra-c-phi-u-h-ng-ng-y-test"}
PROJECT_ID = "0bc81dc2-c04b-4fe1-b01e-22ab22e14b39"
PROJECT_PATH = "/Users/tuanh/finance root"
SCHEMA_SHA256 = "3c8d376020ffc65aae4613fb7afa7157bb8ef96b0930d2b39aab29e608f6f60a"
PACKAGE_SHA256 = "e5655405c859dfae57e94fa90fa5de73ed9eb87e9516300fa6930c0b869e24d9"
SCOPE = "CODEX_LOCAL_SCHEDULED_REAL_QA_CHAIN"
HISTORICAL_UNRESOLVED = "NOT_ESTABLISHED_FOR_HISTORICAL_REJECTED_OPERATION"
INDEPENDENT_SCOPE = "CASE_SCOPED_NOTIFICATION"
PO_DISPOSITION_PATH = "Planning/PO-DEC009-independent-runtime-disposition-20260925.md"
PO_DISPOSITION_SHA256 = "0709cd1f986b3dceef53611c9432f0b5a0bce89184f345e8456b7d80961c2944"


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def normalized_bytes(ref, raw):
    mode = ref.get("normalization", "RAW_BYTES")
    if mode == "RAW_BYTES":
        return raw
    if mode == "CANONICAL_JSON_UTF8" and ref.get("media_type") == "application/json":
        return json.dumps(json.loads(raw), sort_keys=True, separators=(",", ":"),
                          ensure_ascii=False, allow_nan=False).encode("utf-8")
    raise ValueError("unsupported_evidence_normalization")


def utc(value):
    time = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if time.tzinfo is None:
        raise ValueError("timezone_required")
    return time.astimezone(timezone.utc)


def policy_valid(policy):
    try:
        if (policy["schema_version"] != "finance-local-producer-policy.v1" or
                policy["evidence_schema_sha256"] != SCHEMA_SHA256 or
                policy["package_manifest_sha256"] != PACKAGE_SHA256 or
                policy["project_id"] != PROJECT_ID or policy["project_path"] != PROJECT_PATH or
                policy["automations"] != AUTOMATIONS or set(policy["cases"]) != set(CASES)):
            return False
        for case, rule in policy["cases"].items():
            if set(rule) != {"template_sha256", "allowed_substitutions", "trigger_offset_seconds",
                             "artifact_accept_before_seconds"}:
                return False
            allowed = rule["allowed_substitutions"]
            if (not isinstance(allowed, list) or len(set(allowed)) != len(allowed) or
                    any(not isinstance(k, str) or not re.fullmatch(r"<[A-Z][A-Z0-9_]+>", k) for k in allowed)):
                return False
            if not re.fullmatch(r"[0-9a-f]{64}", rule["template_sha256"]):
                return False
            trigger, cutoff = rule["trigger_offset_seconds"], rule["artifact_accept_before_seconds"]
            if type(trigger) is not int or type(cutoff) is not int or not 0 <= trigger < cutoff <= 5400:
                return False
            if (trigger, cutoff) != {"P1": (300, 1200),
                    "P4": (2400, 3300), "N1": (300, 1200), "N2": (3000, 3900)}[case]:
                return False
        return True
    except (KeyError, TypeError, ValueError):
        return False


def safety_disposition(guard, binding, identities, blobs):
    """Only the exact PO disposition permits an independent notification exception."""
    independent = guard.get("independent_notification_release")
    if independent is None:
        if guard["safety_block_resolution"] != "RESOLVED_SUPPORTED":
            return False
        return any(digest(value) == guard["safety_resolution_evidence_sha256"] for value in blobs.values())
    if (identities["case_id"] not in {"N1", "N2"} or
            guard["safety_block_resolution"] != HISTORICAL_UNRESOLVED or
            independent["historical_planning_block_status"] != HISTORICAL_UNRESOLVED or
            independent["scope"] != INDEPENDENT_SCOPE or
            independent["release_decision"] != "RELEASED_FOR_INDEPENDENT_NOTIFICATION" or
            independent["new_rejection_status"] != "NONE_OBSERVED" or
            any(independent[k] != v for k, v in identities.items()) or
            independent["po_disposition_path"] != PO_DISPOSITION_PATH or
            independent["po_disposition_sha256"] != PO_DISPOSITION_SHA256 or
            binding["release_scope"] != INDEPENDENT_SCOPE or
            binding["po_disposition_sha256"] != PO_DISPOSITION_SHA256):
        return False
    if not any(digest(value) == PO_DISPOSITION_SHA256 for value in blobs.values()):
        raise ValueError("missing_hashed_PO_disposition")
    evidence = independent["independence_evidence"]
    if not isinstance(evidence, list) or not evidence:
        raise ValueError("missing_case_independence_evidence")
    for ref in evidence:
        value = normalized_bytes(ref, blobs[ref["path"]])
        if not value or digest(value) != ref["sha256"]:
            raise ValueError("independence_evidence_digest_mismatch")
    return True


def verify(proof, packet, bound, blobs):
    """blobs contains ONLY already hash-checked packet evidence, indexed by path.

    Missing records raise -> UNKNOWN. Contradictory records return False -> FAIL.
    Hashes establish integrity, not source authenticity; independent QA still reviews
    native captures/normalization and any indirect linkage acceptance.
    """
    policy = bound["local_producer_policy"]
    if not policy_valid(policy):
        raise ValueError("missing_or_invalid_local_producer_policy")
    role = CASES[packet["case_id"]]
    rule = policy["cases"][packet["case_id"]]
    refs = proof["scheduled_provenance"]["evidence_refs"]
    if not refs or len(refs) != len({r["kind"] for r in refs}):
        return False
    selected = {}
    by_kind = {}
    for ref in refs:
        value = normalized_bytes(ref, blobs[ref["path"]])
        if digest(value) != ref["sha256"]:
            raise ValueError("provenance_reference_digest_mismatch")
        selected[ref["path"]] = value
        by_kind[ref["kind"]] = ref
    if not {"SAVED_CONFIG", "BOUND_PROMPT", "RELEASE_GUARD", "SCHEDULER_RUN_RECORD", "ARTIFACT_READBACK"} <= set(by_kind):
        raise ValueError("missing_typed_provenance_reference")

    def raw(sha):
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise ValueError("missing_evidence_digest")
        return next(value for value in blobs.values() if digest(value) == sha)

    def document(sha):
        return json.loads(raw(sha))

    package = document(policy["package_manifest_sha256"])
    raw(policy["evidence_schema_sha256"])
    package_rule = package["cases"][packet["case_id"]]
    if (package_rule["prompt_sha256"] != rule["template_sha256"] or
            package_rule["allowed_substitutions"] != rule["allowed_substitutions"] or
            any(package_rule["relative_timing"][k] != rule[k] for k in
                ("trigger_offset_seconds", "artifact_accept_before_seconds"))):
        return False

    automation, binding, run, artifact = (proof[k] for k in ("automation", "binding", "run", "artifact"))
    provenance = proof["scheduled_provenance"]
    for kind, sha in (("SAVED_CONFIG", automation["config_file_sha256"]),
                      ("BOUND_PROMPT", binding["bound_prompt_sha256"]),
                      ("RELEASE_GUARD", binding["guard_sha256"]),
                      ("ARTIFACT_READBACK", artifact["readback_sha256"])):
        if by_kind[kind]["sha256"] != sha:
            return False
    if run["record_source"] != by_kind["SCHEDULER_RUN_RECORD"]:
        return False
    identities = {"campaign_id": packet["campaign_id"], "session_id": packet["session_id"],
                  "case_id": packet["case_id"], "backend_manifest_sha256": packet["manifest_hash"]}
    if (proof["schema_version"] != "finance-local-producer-evidence.v1" or proof["decision"] != "PO-DEC-009" or
            proof["evidence_status"] != "RECORDED" or provenance["unsupported_fields"] or
            provenance["result"] not in {"DIRECT", "INDIRECT_ACCEPTED"} or run["invocation_kind"] != "SCHEDULED"):
        return False
    if any(binding[k] != v for k, v in identities.items()):
        return False
    expected_auto = {"automation_id": AUTOMATIONS[role], "project_id": PROJECT_ID,
                     "project_path": PROJECT_PATH, "kind": "cron", "execution_environment": "local",
                     "model": "gpt-6-sol", "reasoning_effort": "medium", "saved_status": "ACTIVE"}
    if any(automation[k] != v for k, v in expected_auto.items()):
        return False

    config_bytes = raw(automation["config_file_sha256"])
    if by_kind["SAVED_CONFIG"]["media_type"] != "application/toml":
        return False
    try:
        import tomllib
    except ImportError:
        try:
            import tomli as tomllib
        except ImportError:
            raise ValueError("TOML_reader_unavailable")
    config = tomllib.loads(config_bytes.decode("utf-8"))
    config = {"automation_id": config["id"], "project_id": config["project_id"],
                  "project_path": config.get("cwd", config.get("project_path")), "kind": config["kind"],
                  "execution_environment": config["execution_environment"], "model": config["model"],
                  "reasoning_effort": config["reasoning_effort"], "saved_status": config["status"],
                  "prompt": config["prompt"], "rrule": config["rrule"]}
    if any(config[k] != v for k, v in expected_auto.items()) or config["rrule"] != automation["saved_rrule"]:
        return False
    prompt = raw(binding["bound_prompt_sha256"]).decode("utf-8")
    if config["prompt"] != prompt or digest(prompt.encode()) != automation["saved_prompt_sha256"]:
        return False

    guard = document(binding["guard_sha256"])
    if guard["status"] != "RELEASED" or any(guard[k] != v for k, v in identities.items() if k != "case_id"):
        return False
    if not safety_disposition(guard, binding, identities, blobs):
        return False
    if not guard["readiness_evidence"] or not guard["ba_release_evidence"]:
        raise ValueError("missing_readiness_or_release_evidence")
    for sha in guard["readiness_evidence"] + [guard["ba_release_evidence"]]:
        raw(sha)
    release = guard[role]
    if release["automation_id"] != AUTOMATIONS[role] or release["case_id"] != packet["case_id"]:
        return False
    if release["bound_prompt_sha256"] != binding["bound_prompt_sha256"]:
        return False
    # The ledger binds actual server T0 and relative scheduling AFTER manifest sealing.
    ledger_ref = packet["facts"]["provenance_runtime_binding"]
    if digest(blobs[ledger_ref["path"]]) != ledger_ref["sha256"]:
        raise ValueError("runtime_binding_digest_mismatch")
    ledger = json.loads(blobs[ledger_ref["path"]])
    if ledger["schema_version"] != "finance-local-run-binding.v1":
        return False
    if (guard.get("independent_notification_release") is not None and
            ledger["new_rejection_status"] != "NONE_OBSERVED"):
        return False
    if any(ledger[k] != v for k, v in identities.items()):
        return False
    if (ledger["automation_id"] != AUTOMATIONS[role] or ledger["config_file_sha256"] != automation["config_file_sha256"] or
            ledger["guard_sha256"] != binding["guard_sha256"]):
        return False
    server = document(ledger["server_status_sha256"])
    if server["state"] != "ACTIVE" or any(server[k] != identities[k] for k in ("campaign_id", "session_id")):
        return False
    if server["manifest_hash"] != packet["manifest_hash"]:
        return False
    start = utc(server["started_at"])
    scheduled, cutoff = utc(release["scheduled_at"]), utc(release["accept_by"])
    if (scheduled - start).total_seconds() != rule["trigger_offset_seconds"] or not scheduled < cutoff <= start + timedelta(seconds=rule["artifact_accept_before_seconds"]):
        return False
    if ledger["scheduled_at"] != release["scheduled_at"] or ledger["accept_by"] != release["accept_by"]:
        return False
    if ledger["rrule"] != config["rrule"] or ledger["one_shot"] is not True:
        return False
    recurrence = config["rrule"].removeprefix("RRULE:").split(";")
    if recurrence.count("COUNT=1") != 1 or sum(item.startswith("COUNT=") for item in recurrence) != 1:
        return False
    # Scheduler's saved one-shot next-run observation must agree with the released instant.
    if utc(ledger["observed_next_run_at"]) != scheduled:
        return False
    if not start <= utc(automation["config_readback_at"]) <= scheduled <= utc(run["started_at"]) <= utc(run["completed_at"]) < cutoff:
        return False
    if packet["case_id"] == "P4":
        gate = document(ledger["p2_p3_gate_sha256"])
        if (any(gate[k] != identities[k] for k in ("campaign_id", "session_id")) or
                not all(gate[k] is True for k in ("p2_verified", "p3_verified", "p3_unapproved_denial_verified")) or
                not 0 <= (utc(gate["verified_at"]) - start).total_seconds() < 2100 or
                not utc(gate["verified_at"]) <= utc(automation["config_readback_at"]) < start + timedelta(seconds=2100)):
            return False
    template = raw(rule["template_sha256"]).decode("utf-8")
    substitutions = ledger["substitutions"]
    tokens = set(re.findall(r"<[A-Z][A-Z0-9_]+>", template))
    if set(substitutions) != tokens or tokens != set(rule["allowed_substitutions"]):
        return False
    # Additional future canonical IDs/times come from a hashed BA rendering binding,
    # itself referencing the trusted case readback. They are never frozen future outputs.
    rendering = document(ledger["rendering_binding_sha256"])
    case_readback = document(rendering["case_readback_sha256"])
    if (rendering["reviewer_role"] != "SYSTEM_BA" or rendering["decision"] != "ACCEPTED" or
            any(rendering[k] != v for k, v in identities.items()) or
            any(case_readback[k] != v for k, v in identities.items()) or
            rendering["template_sha256"] != rule["template_sha256"] or
            rendering["substitutions"] != substitutions):
        return False
    expected = {"CAMPAIGN_ID": packet["campaign_id"], "SESSION_ID": packet["session_id"],
                "PLANNING_SESSION_ID": packet["session_id"], "NOTIFICATION_SESSION_ID": packet["session_id"],
                "CASE_ID": packet["case_id"], "BACKEND_MANIFEST_SHA256": packet["manifest_hash"],
                "FRESH_SOURCE_GENERATION": bound["source_context"]["source_generation"]}
    if packet["case_id"] == "P4":
        expected |= {"VERIFIED_P2_INTENT_ID": gate["intent_id"], "VERIFIED_P2_CURRENT_CANONICAL_VERSION": gate["expected_version"]}
    if any(k[1:-1] in expected and str(substitutions[k]) != str(expected[k[1:-1]]) for k in substitutions):
        return False
    rendered = re.sub(r"<[A-Z][A-Z0-9_]+>", lambda match: str(substitutions[match[0]]), template)
    if rendered != prompt or re.search(r"<[A-Z][A-Z0-9_]+>", rendered):
        return False
    # Native/normalized run record must be an actual hashed input, not facts booleans.
    native = json.loads(selected[run["record_source"]["path"]])
    if (not run["task_record_id"] or native["task_record_id"] != run["task_record_id"] or
            native["invocation_kind"] != "SCHEDULED" or native["manual_invocation"] is not False or
            not native["run_id"] or any(native[k] != identities[k] for k in identities) or
            any(native[k] != run[k] for k in ("started_at", "completed_at", "observed_status")) or
            native["observed_status"] != "COMPLETED" or native["project_id"] != PROJECT_ID or
            native["config_file_sha256"] != automation["config_file_sha256"] or
            utc(native["scheduled_at"]) != scheduled):
        return False
    if provenance["result"] == "DIRECT":
        if native["automation_id"] != AUTOMATIONS[role]:
            return False
    else:
        review = document(ledger["indirect_review_sha256"])
        if by_kind["BA_LINKAGE_REVIEW"]["sha256"] != ledger["indirect_review_sha256"]:
            return False
        if (review["decision"] != "INDIRECT_ACCEPTED" or review["reviewer_role"] != "SYSTEM_BA" or
                not review["rationale"] or review["manual_invocation"] is not False or
                review["automation_id"] != AUTOMATIONS[role] or
                review["task_record_id"] != run["task_record_id"] or
                review["run_record_sha256"] != digest(selected[run["record_source"]["path"]]) or
                review["config_file_sha256"] != automation["config_file_sha256"] or
                review["artifact_readback_sha256"] != artifact["readback_sha256"] or
                review["last_run_before"] == review["last_run_after"] or
                review["last_run_after"] != native["run_id"]):
            return False
        raw(review["downstream_chain_evidence_sha256"])
        if native.get("automation_id") not in (None, AUTOMATIONS[role]):
            return False
    observed = document(artifact["readback_sha256"])
    if (artifact["artifact_kind"] != ("PLANNING_ROW" if packet["case_id"].startswith("P") else "NOTIFICATION_ROW_AND_EVENT") or
            any(artifact[k + "_marker"] != identities[k + "_id"] for k in ("campaign", "session", "case")) or
            not artifact["resource_id"] or not artifact["range_or_event_id"] or
            any(observed[k] != artifact[k] for k in ("artifact_kind", "resource_id", "range_or_event_id", "campaign_marker", "session_marker", "case_marker")) or
            native["artifact_readback_sha256"] != artifact["readback_sha256"] or
            observed["task_record_id"] != run["task_record_id"] or observed["run_id"] != native["run_id"] or
            observed["automation_id"] != AUTOMATIONS[role] or
            not utc(run["started_at"]) <= utc(artifact["readback_at"]) < cutoff):
        return False
    if (observed["source_context"] != bound["source_context"] or
            artifact["resource_id"] != bound["source_context"]["source_id"] or
            not isinstance(observed["producer_values"], dict) or not observed["producer_values"] or
            not isinstance(observed["backend_values"], dict)):
        return False
    if packet["case_id"].startswith("P"):
        if observed["producer_payload_sha256"] != packet["facts"]["sheet_payload_sha256"]:
            return False
    elif (observed["event_id"] not in packet["facts"]["event_ids"] or
          observed["calendar_id"] != bound["calendar_id"]):
        return False
    return True
