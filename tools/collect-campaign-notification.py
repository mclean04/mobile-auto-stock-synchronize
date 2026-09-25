#!/usr/bin/env python3
"""Read actual tablet notification evidence; no FCM send, registration or synthetic open."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from uuid import UUID
from campaign_oracles import utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--transport-id", required=True)
    parser.add_argument("--event-id", required=True, type=UUID)
    parser.add_argument("--expected-device-id", required=True, type=UUID)
    parser.add_argument("--expected-uid", required=True)
    parser.add_argument("--session-start-utc", required=True)
    parser.add_argument("--campaign-id", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--case-id", required=True, choices=["N1", "N2"])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--app-sha256", required=True)
    parser.add_argument("--test-sha256", required=True)
    parser.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.:@+-]{1,128}", args.expected_uid):
        raise ValueError("invalid_uid")
    utc(args.session_start_utc)
    if not all(re.fullmatch(r"[A-Za-z0-9_-]{1,100}", v) for v in (args.campaign_id, args.session_id)):
        raise ValueError("invalid_campaign_identity")
    if not re.fullmatch(r"[0-9a-f]{64}", args.manifest_sha256):
        raise ValueError("invalid_manifest_hash")
    if args.output.exists():
        raise ValueError("fresh_evidence_directory_required")
    args.output.mkdir(mode=0o700, parents=True)
    package = "com.example.finance_planning"
    def adb(*command, check=True):
        return subprocess.run([args.adb, "-t", args.transport_id, *command],
                              check=check, capture_output=True, timeout=60)
    for pkg, digest in ((package, args.app_sha256), (package + ".qa.test", args.test_sha256)):
        paths = adb("shell", "pm", "path", pkg).stdout.decode().strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
            raise ValueError("unexpected_installed_package")
        remote = paths[0].removeprefix("package:")
        if not re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+", remote):
            raise ValueError("unexpected_package_path")
        actual = adb("shell", "sha256sum", remote).stdout.decode().split()[0]
        if actual != digest:
            raise ValueError("installed_hash_mismatch")
    try:
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
            package + ".CampaignNotificationEvidenceTest", "-e", "event_id", str(args.event_id),
            "-e", "expected_device_id", str(args.expected_device_id), "-e", "expected_uid", args.expected_uid,
            "-e", "session_start_utc", args.session_start_utc,
            "-e", "campaign_id", args.campaign_id, "-e", "session_id", args.session_id,
            "-e", "manifest_sha256", args.manifest_sha256, "-e", "case_id", args.case_id,
            package + ".qa.test/androidx.test.runner.AndroidJUnitRunner")
        if b"OK (1 test)" not in result.stdout:
            raise RuntimeError("notification_capture_failed:" + hashlib.sha256(result.stdout).hexdigest())
        raw = adb("exec-out", "run-as", package, "cat", "files/campaign-notification-evidence.json").stdout
        record = json.loads(raw)
        if record["event_id"] != str(args.event_id) or record["device_id"] != str(args.expected_device_id):
            raise ValueError("capture_identity_mismatch")
        for key in ("campaign_id", "session_id", "manifest_sha256", "case_id"):
            if record[key] != getattr(args, key):
                raise ValueError("capture_campaign_mismatch")
        fd = os.open(args.output / "notification.json", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
        subprocess.run([sys.executable, str(Path(__file__).with_name("export-production-observation.py")),
                        "--transport-id", args.transport_id, "--adb", args.adb,
                        "--output", str(args.output / "observations")], check=True, capture_output=True, timeout=60)
        print(json.dumps({"captured": True, "runtime_acceptance": "REQUIRES_BACKEND_AND_QA_RECONCILIATION"}))
    finally:
        adb("shell", "run-as", package, "rm", "-f", "files/campaign-notification-evidence.json", check=False)


if __name__ == "__main__":
    main()
