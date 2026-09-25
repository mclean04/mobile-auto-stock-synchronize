#!/usr/bin/env python3
"""Run the concurrent B3 phase behind one deterministic localhost barrier."""
import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

from two_device_coordinator import TwoDeviceCoordinator, make_handler
from http.server import ThreadingHTTPServer


parser = argparse.ArgumentParser()
parser.add_argument("--device-one-config", type=Path, required=True)
parser.add_argument("--device-two-config", type=Path, required=True)
parser.add_argument("--transport-one", required=True)
parser.add_argument("--transport-two", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--coordinator-port", type=int, default=0)
parser.add_argument("--barrier-timeout-seconds", type=float, default=45)
parser.add_argument("--install", action="store_true")
parser.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
args = parser.parse_args()

if args.transport_one == args.transport_two:
    raise SystemExit("two distinct ADB transports are required")
if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
    raise SystemExit("output must be a new or empty private directory")
if args.coordinator_port and not 1024 <= args.coordinator_port <= 65535:
    raise SystemExit("coordinator port must be zero or unprivileged")
for source in (args.device_one_config, args.device_two_config):
    if not source.is_file() or stat.S_IMODE(source.stat().st_mode) != 0o600:
        raise SystemExit("each private device config must exist with mode 0600")

configs = [json.loads(args.device_one_config.read_text()), json.loads(args.device_two_config.read_text())]
same = ("run_id", "uid", "account", "base_url")
if any(config.get(key) != configs[0].get(key) for config in configs[1:] for key in same):
    raise SystemExit("both configs must share run, owner, account and Backend loopback URL")
intent_ids = [config.get("intents", {}).get("concurrent") for config in configs]
if not intent_ids[0] or len(set(intent_ids)) != 1:
    raise SystemExit("both configs must name the same non-empty concurrent canonical intent")
device_ids = [config.get("device_id") for config in configs]
if None in device_ids or len(set(device_ids)) != 2:
    raise SystemExit("two distinct verified device UUIDs are required")
if not re.fullmatch(r"[A-Za-z0-9-]{1,60}", configs[0]["run_id"]):
    raise SystemExit("invalid run ID")

participants = ["device-one", "device-two"]
coordinator = TwoDeviceCoordinator(configs[0]["run_id"], participants,
                                   args.barrier_timeout_seconds)
server = ThreadingHTTPServer(("127.0.0.1", args.coordinator_port), make_handler(coordinator))
port = server.server_port
server_thread = threading.Thread(target=server.serve_forever, daemon=True)
server_thread.start()

args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(args.output, 0o700)
root = Path(__file__).resolve().parents[1]
runner = root / "tools/run-b3-device.py"
processes = []
summaries = []

try:
    with tempfile.TemporaryDirectory(prefix="b3-two-device-") as private:
        os.chmod(private, 0o700)
        for index, config in enumerate(configs):
            config = dict(config)
            config["participant_id"] = participants[index]
            config["coordinator_url"] = f"http://127.0.0.1:{port}"
            path = Path(private) / f"device-{index + 1}.json"
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "w") as stream:
                json.dump(config, stream, separators=(",", ":"))
            device_output = args.output / participants[index]
            device_output.mkdir(mode=0o700)
            command = [sys.executable, str(runner), "--config", str(path),
                       "--transport-id", [args.transport_one, args.transport_two][index],
                       "--phase", "concurrent", "--output", str(device_output),
                       "--adb", args.adb]
            if args.install:
                command.append("--install")
            processes.append(subprocess.Popen(command, stdout=subprocess.PIPE,
                                                stderr=subprocess.PIPE, text=True))

        for index, process in enumerate(processes):
            try:
                stdout, stderr = process.communicate(timeout=240)
            except subprocess.TimeoutExpired:
                coordinator.abort(participants[index], "participant_process_timeout")
                process.terminate()
                stdout, stderr = process.communicate(timeout=10)
            safe = {"participant_id": participants[index], "returncode": process.returncode,
                    "stdout_sha256": hashlib.sha256(stdout.encode()).hexdigest(),
                    "stderr_sha256": hashlib.sha256(stderr.encode()).hexdigest()}
            if process.returncode != 0:
                try:
                    coordinator.abort(participants[index], "participant_process_failed")
                except Exception:
                    pass
            summary_path = args.output / participants[index] / "concurrent-summary.json"
            if summary_path.is_file():
                safe["device_summary"] = json.loads(summary_path.read_text())
            summaries.append(safe)

    oracle = coordinator.oracle()
    result = {"schema_version": 1, "run_id": configs[0]["run_id"],
              "coordinator": {"host": "127.0.0.1", "port": port,
                              "automatic_upload": False, "new_cloud_service": False},
              "participants": summaries, "oracle": oracle,
              "cases_executed": True, "real_broker_route": False}
    result_path = args.output / "two-device-concurrent-summary.json"
    descriptor = os.open(result_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
    print(json.dumps({"run_id": result["run_id"], "pass": oracle["pass"],
                      "output": str(args.output), "secrets_printed": False}, sort_keys=True))
    if any(item["returncode"] != 0 for item in summaries) or not oracle["pass"]:
        raise SystemExit("Two-device aggregate oracle failed; inspect private evidence")
except KeyboardInterrupt:
    for participant in participants:
        try:
            coordinator.abort(participant, "operator_cancelled")
        except Exception:
            pass
    raise
finally:
    for process in processes:
        if process.poll() is None:
            process.terminate()
    for transport in (args.transport_one, args.transport_two):
        subprocess.run([args.adb, "-t", transport, "reverse", "--remove", f"tcp:{port}"],
                       check=False, capture_output=True)
    server.shutdown()
    server.server_close()
