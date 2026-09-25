#!/usr/bin/env python3
"""Run one B3 phase on one selected Android device; never invokes a real broker."""
import argparse
import hashlib
import json
import re
import subprocess
import uuid
from pathlib import Path
from urllib.parse import urlparse

p = argparse.ArgumentParser()
p.add_argument("--config", type=Path, required=True, help="Local test service config; contains only test auth")
p.add_argument("--transport-id", required=True)
p.add_argument("--phase", required=True, choices=[
    "readiness", "accepted", "resume_accepted", "unknown", "kill_unknown", "resume_unknown", "stale", "late", "resume_late"])
p.add_argument("--output", type=Path, required=True)
p.add_argument("--install", action="store_true")
p.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
config = json.loads(a.config.read_text())
if "service_url" in config:
    config.setdefault("evidence_kind", "native_qa_readiness" if a.phase == "readiness" else "native_qa_http")
if a.phase != "readiness":
    scenario = "unknown" if a.phase == "kill_unknown" else a.phase.removeprefix("resume_")
    assert config["intents"].get(scenario), "Business fixture intent is not ready; BA must supply captured canonical ID"
assert re.fullmatch(r"[A-Za-z0-9-]{1,60}", config["run_id"])
uuid.UUID(config["device_id"])
base = urlparse(config["base_url"])
assert base.scheme == "http" and base.hostname == "127.0.0.1" and 1024 <= base.port <= 65535
adb = [a.adb, "-t", a.transport_id]
package = "com.example.finance_planning"
test_package = "com.example.finance_planning.qa.test"
a.output.mkdir(parents=True, exist_ok=True)

def call(*args, **kw):
    return subprocess.run(adb + list(args), check=True, capture_output=True, **kw)

artifacts = {}
for name, file in [(package, root / "app/build/outputs/apk/debug/app-debug.apk"),
                   (test_package, root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
    digest = hashlib.sha256(file.read_bytes()).hexdigest()
    if a.install:
        result = call("install", "-r", str(file), text=True)
        assert "Success" in result.stdout
    paths = call("shell", "pm", "path", name, text=True).stdout.strip().splitlines()
    assert len(paths) == 1 and paths[0].startswith("package:/data/app/")
    remote = paths[0].removeprefix("package:")
    assert re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+", remote)
    actual = call("shell", "sha256sum", remote, text=True).stdout.split()[0]
    assert actual == digest, f"Installed APK hash differs: {name}"
    artifacts[name] = {"path": str(file), "sha256": digest, "installed_sha256": actual}

call("reverse", f"tcp:{base.port}", f"tcp:{base.port}")
call("shell", "am", "force-stop", package)
# Use stdin: do not put the local test bearer in argv or print it.
call("shell", "run-as", package, "sh", "-c", "'cat > files/b3-config.json'",
     input=json.dumps(config).encode())
result = call("shell", "am", "instrument", "-w", "-r",
              "-e", "class", package + ".B3DeviceFlowTest", "-e", "b3_phase", a.phase,
              test_package + "/androidx.test.runner.AndroidJUnitRunner", text=True)
(a.output / f"{a.phase}-instrumentation.txt").write_text(result.stdout + result.stderr)
trace = call("shell", "run-as", package, "cat",
             f"files/b3-{config['run_id']}/{a.phase}.jsonl", text=True).stdout
(a.output / f"{a.phase}-trace.jsonl").write_text(trace)
events = [json.loads(line) for line in trace.splitlines() if line.strip()]
summary = {"phase": a.phase, "run_id": config["run_id"], "artifacts": artifacts,
           "backend_boundary": config.get("evidence_kind", "UNVERIFIED"),
           "instrumentation_ok": "OK (1 test)" in result.stdout,
           "trace_pass": bool(events and events[-1]["event"] == "PASS"),
           "expected_crash_checkpoint": bool(a.phase == "kill_unknown" and events and
               events[-1]["event"] == "process_kill_after_unknown" and
               "Process crashed" in result.stdout and "OK (1 test)" not in result.stdout),
           "pid": next((e["data"]["pid"] for e in reversed(events) if e["event"] == "process"), None)}
(a.output / f"{a.phase}-summary.json").write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
assert (summary["instrumentation_ok"] and summary["trace_pass"]) or summary["expected_crash_checkpoint"], "See saved instrumentation/trace failure"
