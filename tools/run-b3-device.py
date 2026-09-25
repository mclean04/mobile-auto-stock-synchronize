#!/usr/bin/env python3
"""Run one bounded B3 phase on one device; never invokes a real broker."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import stat
import uuid
from pathlib import Path
from urllib.parse import urlparse

from qa_session_guard import SessionWindow, SessionWindowError, parse_reverse_list, reverse_restore_args
from campaign_artifacts import add_artifact_options, artifacts_from_args


def private_write(path: Path, value: str):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(value)


p = argparse.ArgumentParser()
p.add_argument("--config", type=Path, required=True, help="Local test service config; contains only test auth")
p.add_argument("--transport-id", required=True)
p.add_argument("--phase", required=True, choices=[
    "readiness", "accepted", "resume_accepted", "unknown", "kill_unknown", "resume_unknown",
    "stale", "late", "resume_late", "concurrent", "planning_display", "snapshot", "denied"])
p.add_argument("--output", type=Path, required=True)
p.add_argument("--install", action="store_true")
p.add_argument("--session-start-utc")
p.add_argument("--session-end-utc")
p.add_argument("--routes-managed-by-parent", action="store_true", help=argparse.SUPPRESS)
p.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
add_artifact_options(p)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
if stat.S_IMODE(a.config.stat().st_mode) != 0o600:
    raise SystemExit("private_config_mode_must_be_0600")
config = json.loads(a.config.read_text())
local_artifacts = artifacts_from_args(a, campaign="campaign_id" in config)
if "service_url" in config:
    config.setdefault("evidence_kind", "native_qa_readiness" if a.phase == "readiness" else "native_qa_http")
if a.phase != "readiness":
    if not a.session_start_utc or not a.session_end_utc:
        raise SystemExit("business phases require the reviewed session start and end")
    session = SessionWindow.parse(a.session_start_utc, a.session_end_utc)
    session.require_active()
    config["session_start_utc"] = session.manifest()["start_utc"]
    config["session_end_utc"] = session.manifest()["end_utc"]
    scenario = "unknown" if a.phase == "kill_unknown" else a.phase.removeprefix("resume_")
    if a.phase not in {"planning_display", "snapshot"}:
        assert config["intents"].get(scenario), "Business fixture intent is not ready; BA must supply captured canonical ID"
    if "campaign_id" in config and not a.phase.startswith("resume_"):
        session.require_business()
else:
    if bool(a.session_start_utc) != bool(a.session_end_utc):
        raise SystemExit("session start and end must be supplied together")
    session = SessionWindow.parse(a.session_start_utc, a.session_end_utc) if a.session_start_utc else None
assert re.fullmatch(r"[A-Za-z0-9-]{1,60}", config["run_id"])
uuid.UUID(config["device_id"])
base = urlparse(config["base_url"])
assert base.scheme == "http" and base.hostname == "127.0.0.1" and 1024 <= base.port <= 65535
coordinator = None
if a.phase == "concurrent":
    assert re.fullmatch(r"[A-Za-z0-9._:-]{1,200}", config["participant_id"])
    coordinator = urlparse(config["coordinator_url"])
    assert coordinator.scheme == "http" and coordinator.hostname == "127.0.0.1"
    assert 1024 <= coordinator.port <= 65535 and coordinator.port != base.port
adb = [a.adb, "-t", a.transport_id]
package = "com.example.finance_planning"
test_package = "com.example.finance_planning.qa.test"
a.output.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(a.output, 0o700)


def remaining_timeout(maximum=30):
    if session is None:
        return maximum
    session.require_active()
    remaining = session.remaining_seconds()
    if remaining <= 0:
        raise SessionWindowError("session_deadline_reached")
    return max(0.1, min(maximum, remaining))


def call(*args, **kw):
    kw.setdefault("timeout", remaining_timeout())
    return subprocess.run(adb + list(args), check=True, capture_output=True, **kw)


def cleanup_call(*args):
    try:
        return subprocess.run(adb + list(args), check=False, capture_output=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return subprocess.CompletedProcess(adb + list(args), 124, b"", b"")


def reverse_snapshot():
    result = cleanup_call("reverse", "--list")
    if result.returncode:
        raise RuntimeError("reverse_snapshot_unavailable")
    raw = result.stdout.decode(errors="replace")
    return parse_reverse_list(raw)


route_previous = {}
route_restored = True
config_written = False
artifacts = {}
try:
    for name, (file, digest) in [(package, local_artifacts["app"]),
                                 (test_package, local_artifacts["test"])]:
        if a.install:
            install = ["install", "--user", "0", "-r"]
            if name == test_package:
                install.append("-t")
            result = call(*install, str(file), text=True, timeout=remaining_timeout(120))
            assert "Success" in result.stdout
        paths = call("shell", "pm", "path", name, text=True).stdout.strip().splitlines()
        assert len(paths) == 1 and paths[0].startswith("package:/data/app/")
        remote = paths[0].removeprefix("package:")
        assert re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+", remote)
        actual = call("shell", "sha256sum", remote, text=True).stdout.split()[0]
        assert actual == digest, f"Installed APK hash differs: {name}"
        artifacts[name] = {"path": str(file), "sha256": digest, "installed_sha256": actual}

    if not a.routes_managed_by_parent:
        current = reverse_snapshot()
        for port in [base.port] + ([coordinator.port] if coordinator is not None else []):
            local = f"tcp:{port}"
            route_previous[local] = current.get(local)
            call("reverse", local, local)
    call("shell", "am", "force-stop", package)
    # Use stdin: do not put the local test bearer in argv or print it.
    call("shell", "run-as", package, "sh", "-c", "'cat > files/b3-config.json'",
         input=json.dumps(config).encode())
    config_written = True
    result = call("shell", "am", "instrument", "-w", "-r",
                  "-e", "class", package + ".B3DeviceFlowTest", "-e", "b3_phase", a.phase,
                  test_package + "/androidx.test.runner.AndroidJUnitRunner", text=True,
                  timeout=remaining_timeout(240))
    private_write(a.output / f"{a.phase}-instrumentation.txt", result.stdout + result.stderr)
    trace = call("shell", "run-as", package, "cat",
                 f"files/b3-{config['run_id']}/{a.phase}.jsonl", text=True).stdout
    private_write(a.output / f"{a.phase}-trace.jsonl", trace)
    for index in range(4):
        log = cleanup_call("shell", "run-as", package, "cat",
                           f"files/b3-{config['run_id']}/observations/observation-{index}.jsonl")
        if log.returncode == 0:
            private_write(a.output / f"{a.phase}-observation-{index}.jsonl", log.stdout.decode())
    events = [json.loads(line) for line in trace.splitlines() if line.strip()]
    summary = {"phase": a.phase, "run_id": config["run_id"], "artifacts": artifacts,
               "backend_boundary": config.get("evidence_kind", "UNVERIFIED"),
               "instrumentation_ok": "OK (1 test)" in result.stdout,
               "trace_pass": bool(events and events[-1]["event"] == "PASS" and '"TRUNCATED"' not in trace),
               "trace_truncated": '"TRUNCATED"' in trace,
               "expected_crash_checkpoint": bool(a.phase == "kill_unknown" and events and
                   events[-1]["event"] == "process_kill_after_unknown" and
                   "Process crashed" in result.stdout and "OK (1 test)" not in result.stdout),
               "pid": next((e["data"]["pid"] for e in reversed(events) if e["event"] == "process"), None),
               "session": session.manifest() if session else None,
               "routes_managed_by_parent": a.routes_managed_by_parent,
               "notification_route_mutated": False,
               "qa_notification_config_mutated": False,
               "production_registration_called": False}
    private_write(a.output / f"{a.phase}-summary.json", json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))
    assert (summary["instrumentation_ok"] and summary["trace_pass"]) or summary["expected_crash_checkpoint"], "See saved instrumentation/trace failure"
finally:
    cleanup_call("shell", "am", "force-stop", package)
    config_removed = True
    if config_written:
        config_removed = cleanup_call("shell", "run-as", package, "rm", "-f",
                                      "files/b3-config.json").returncode == 0
    if not a.routes_managed_by_parent:
        for local, previous in reversed(list(route_previous.items())):
            restored = cleanup_call(*reverse_restore_args(local, previous))
            route_restored = route_restored and restored.returncode == 0
        if route_previous:
            try:
                after = reverse_snapshot()
                route_restored = route_restored and all(after.get(local) == previous
                                                         for local, previous in route_previous.items())
            except RuntimeError:
                route_restored = False
    private_write(a.output / f"{a.phase}-teardown.json", json.dumps({
        "device_config_removed": config_removed,
        "reverse_routes_restored": route_restored,
        "notification_route_mutated": False,
        "qa_notification_config_mutated": False,
        "production_registration_called": False,
    }, indent=2, sort_keys=True))
    if not route_restored or not config_removed:
        raise SystemExit("B3 teardown failed")
