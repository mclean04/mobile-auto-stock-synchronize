#!/usr/bin/env python3
"""Explicitly export bounded structured observation files without printing their contents."""
import argparse
import datetime
import json
import os
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--transport-id", required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
args = parser.parse_args()

package = "com.example.finance_planning"
relative = "no_backup/production-observation"
names = [f"observation-{index}.jsonl" for index in range(4)]
allowed = {
    "schema_version", "timestamp_utc", "timestamp_display", "display_timezone",
    "component", "action", "stage", "result", "duration_ms", "error_code",
    "run_id", "event_id", "plan_id", "action_id", "request_id",
    "broker_order_id", "version", "source_id", "source_generation",
}

def adb(*values):
    return subprocess.run([args.adb, "-t", args.transport_id, *values],
                          check=False, capture_output=True)

if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
    raise SystemExit("--output must be a new or empty private directory")
args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(args.output, 0o700)

if adb("get-state").stdout.strip() != b"device":
    raise SystemExit("Selected Android transport is unavailable")

exported = 0
total = 0
for name in names:
    result = adb("exec-out", "run-as", package, "cat", f"{relative}/{name}")
    if result.returncode != 0:
        continue
    raw = result.stdout
    for line in raw.splitlines():
        value = json.loads(line)
        if set(value) != allowed or value.get("schema_version") != 1:
            raise SystemExit("Observation file did not match the safe structured schema")
    destination = args.output / name
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(raw)
    os.chmod(destination, 0o600)
    exported += 1
    total += len(raw)

if exported == 0:
    raise SystemExit("No structured observation files were available for this app")

manifest = {
    "schema_version": 1,
    "captured_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "package": package,
    "source": "app-private-no-backup",
    "file_count": exported,
    "bytes": total,
    "automatic_upload": False,
    "source_cleared": False,
}
manifest_path = args.output / "manifest.json"
descriptor = os.open(manifest_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(descriptor, "w") as stream:
    json.dump(manifest, stream, separators=(",", ":"), sort_keys=True)
os.chmod(manifest_path, 0o600)
print(json.dumps({"exported": True, "files": exported, "bytes": total,
                  "output": str(args.output), "log_contents_printed": False},
                 separators=(",", ":"), sort_keys=True))
