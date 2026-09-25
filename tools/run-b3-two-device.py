#!/usr/bin/env python3
"""Run the concurrent B3 phase within one bounded integrated QA session."""
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
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from qa_session_guard import SessionWindow, parse_reverse_list, reverse_restore_args
from two_device_coordinator import TwoDeviceCoordinator, make_handler


def private_write(path: Path, value: str):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        stream.write(value)


parser = argparse.ArgumentParser()
parser.add_argument("--device-one-config", type=Path, required=True)
parser.add_argument("--device-two-config", type=Path, required=True)
parser.add_argument("--transport-one", required=True)
parser.add_argument("--transport-two", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--session-start-utc", required=True)
parser.add_argument("--session-end-utc", required=True)
parser.add_argument("--coordinator-port", type=int, default=0)
parser.add_argument("--barrier-timeout-seconds", type=float, default=45)
parser.add_argument("--install", action="store_true")
parser.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
args = parser.parse_args()

session = SessionWindow.parse(args.session_start_utc, args.session_end_utc)
session.require_active()
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
if "campaign_id" in configs[0]:
    session.require_business()
same = ("run_id", "uid", "account", "base_url", "campaign_id", "session_id", "manifest_hash", "session_kind", "case_id")
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
base = urlparse(configs[0]["base_url"])
if not (base.scheme == "http" and base.hostname == "127.0.0.1" and 1024 <= base.port <= 65535):
    raise SystemExit("Backend route must be an unprivileged loopback port")

participants = ["device-one", "device-two"]
transports = [args.transport_one, args.transport_two]
coordinator = TwoDeviceCoordinator(configs[0]["run_id"], participants,
                                   min(args.barrier_timeout_seconds, session.remaining_seconds()))
server = ThreadingHTTPServer(("127.0.0.1", args.coordinator_port), make_handler(coordinator))
port = server.server_port
if port == base.port:
    server.server_close()
    raise SystemExit("coordinator and Backend loopback ports must differ")
server_thread = threading.Thread(target=server.serve_forever, daemon=True)
server_thread.start()

args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(args.output, 0o700)
root = Path(__file__).resolve().parents[1]
runner = root / "tools/run-b3-device.py"
processes = []
process_outputs = []
summaries = []
route_previous = {}
teardown = {"processes_stopped": False, "device_configs_removed": False,
            "reverse_routes_restored": False, "notification_routes_unchanged": True,
            "qa_notification_configs_unchanged": True, "production_registration_called": False}
failure = None


def adb(transport, *command, check=False, timeout=10):
    try:
        return subprocess.run([args.adb, "-t", transport, *command], check=check,
                              capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        if check:
            raise RuntimeError("adb_command_unavailable") from None
        return subprocess.CompletedProcess(command, 124, b"", b"")


def snapshot(transport):
    result = adb(transport, "reverse", "--list")
    if result.returncode:
        raise RuntimeError("reverse_snapshot_unavailable")
    return parse_reverse_list(result.stdout.decode(errors="replace"))


try:
    # The parent owns and restores every temporary reverse route, including child failures.
    for transport in transports:
        current = snapshot(transport)
        for local in (f"tcp:{base.port}", f"tcp:{port}"):
            route_previous[(transport, local)] = current.get(local)
            adb(transport, "reverse", local, local, check=True)

    with tempfile.TemporaryDirectory(prefix="b3-two-device-") as private:
        os.chmod(private, 0o700)
        for index, config in enumerate(configs):
            config = dict(config)
            config["participant_id"] = participants[index]
            config["coordinator_url"] = f"http://127.0.0.1:{port}"
            config["session_start_utc"] = session.manifest()["start_utc"]
            config["session_end_utc"] = session.manifest()["end_utc"]
            path = Path(private) / f"device-{index + 1}.json"
            private_write(path, json.dumps(config, separators=(",", ":")))
            device_output = args.output / participants[index]
            device_output.mkdir(mode=0o700)
            command = [sys.executable, str(runner), "--config", str(path),
                       "--transport-id", transports[index], "--phase", "concurrent",
                       "--output", str(device_output), "--session-start-utc", args.session_start_utc,
                       "--session-end-utc", args.session_end_utc, "--routes-managed-by-parent",
                       "--adb", args.adb]
            if args.install:
                command.append("--install")
            stdout_file, stderr_file = tempfile.TemporaryFile(), tempfile.TemporaryFile()
            process_outputs.append((stdout_file, stderr_file))
            processes.append(subprocess.Popen(command, stdout=stdout_file, stderr=stderr_file))

        while any(process.poll() is None for process in processes):
            if session.remaining_seconds() <= 0:
                failure = "session_deadline_reached"
                for participant in participants:
                    try:
                        coordinator.abort(participant, failure)
                    except Exception:
                        pass
                break
            failed = next((index for index, process in enumerate(processes)
                           if process.poll() not in (None, 0)), None)
            if failed is not None:
                failure = "participant_process_failed"
                try:
                    coordinator.abort(participants[failed], failure)
                except Exception:
                    pass
                break
            time.sleep(0.05)

        if failure:
            for process in processes:
                if process.poll() is None:
                    process.terminate()

        for index, process in enumerate(processes):
            try:
                process.wait(timeout=min(10, max(0.1, session.remaining_seconds())))
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                failure = failure or "participant_process_teardown_timeout"
            stdout_file, stderr_file = process_outputs[index]
            stdout_file.seek(0); stderr_file.seek(0)
            safe = {"participant_id": participants[index], "returncode": process.returncode,
                    "stdout_sha256": hashlib.sha256(stdout_file.read()).hexdigest(),
                    "stderr_sha256": hashlib.sha256(stderr_file.read()).hexdigest()}
            summary_path = args.output / participants[index] / "concurrent-summary.json"
            if summary_path.is_file():
                safe["device_summary"] = json.loads(summary_path.read_text())
            summaries.append(safe)

    oracle = coordinator.oracle()
    if any(item["returncode"] != 0 for item in summaries) or not oracle["pass"]:
        failure = failure or "aggregate_oracle_failed"
except KeyboardInterrupt:
    failure = "operator_cancelled"
    for participant in participants:
        try:
            coordinator.abort(participant, failure)
        except Exception:
            pass
except Exception as error:
    failure = "runner_" + type(error).__name__
finally:
    for process in processes:
        if process.poll() is None:
            process.terminate()
    for process in processes:
        if process.poll() is None:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
    teardown["processes_stopped"] = all(process.poll() is not None for process in processes)
    removed = []
    for transport in transports:
        adb(transport, "shell", "am", "force-stop", "com.example.finance_planning")
        result = adb(transport, "shell", "run-as", "com.example.finance_planning",
                     "rm", "-f", "files/b3-config.json")
        removed.append(result.returncode == 0)
    teardown["device_configs_removed"] = all(removed)
    restored = []
    for (transport, local), previous in reversed(list(route_previous.items())):
        result = adb(transport, *reverse_restore_args(local, previous))
        restored.append(result.returncode == 0)
    for transport in transports:
        try:
            after = snapshot(transport)
            restored.extend(after.get(local) == previous for (saved_transport, local), previous
                            in route_previous.items() if saved_transport == transport)
        except RuntimeError:
            restored.append(False)
    teardown["reverse_routes_restored"] = bool(route_previous) and all(restored)
    server.shutdown()
    server.server_close()
    for files in process_outputs:
        for output in files:
            output.close()

oracle = coordinator.oracle()
result = {"schema_version": 2, "run_id": configs[0]["run_id"], "session": session.manifest(),
          "coordinator": {"host": "127.0.0.1", "port": port,
                          "automatic_upload": False, "new_cloud_service": False},
          "participants": summaries, "oracle": oracle, "teardown": teardown,
          "cases_executed": bool(summaries), "real_broker_route": False,
          "failure": failure}
result_path = args.output / "two-device-concurrent-summary.json"
private_write(result_path, json.dumps(result, indent=2, sort_keys=True))
print(json.dumps({"run_id": result["run_id"], "pass": oracle["pass"] and not failure,
                  "output": str(args.output), "secrets_printed": False}, sort_keys=True))
teardown_ok = (teardown["processes_stopped"] and teardown["device_configs_removed"] and
               teardown["reverse_routes_restored"] and teardown["notification_routes_unchanged"] and
               teardown["qa_notification_configs_unchanged"] and
               not teardown["production_registration_called"])
if failure or not teardown_ok:
    raise SystemExit(failure or "two-device teardown failed; inspect private evidence")
