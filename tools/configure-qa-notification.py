#!/usr/bin/env python3
"""Install/clear encrypted QA notification routing without printing secrets."""
import argparse
import json
import stat
import subprocess
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("action", choices=["metadata", "install", "register", "clear"])
p.add_argument("--config", type=Path)
p.add_argument("--transport-id", required=True)
p.add_argument("--adb", default="/Users/tuanh/Library/Android/sdk/platform-tools/adb")
a = p.parse_args()
package = "com.example.finance_planning"
adb = [a.adb, "-t", a.transport_id]

def call(*args, **kwargs):
    return subprocess.run(adb + list(args), check=True, capture_output=True, **kwargs)

methods = {"install": "installPrivateConfig", "register": "registerConfiguredTarget",
           "clear": "clearPrivateConfig", "metadata": "verifiedTargetMetadata"}
if a.action == "install":
    if a.config is None or not a.config.is_file():
        raise SystemExit("--config must name a private JSON file")
    if stat.S_IMODE(a.config.stat().st_mode) != 0o600:
        raise SystemExit("QA config must have mode 0600")
    raw = a.config.read_bytes()
    value = json.loads(raw)
    if set(value) != {"target_uid", "target_device_id", "notification_namespace", "bearer"}:
        raise SystemExit("QA config keys do not match the Android contract")
    call("shell", "run-as", package, "sh", "-c", "'cat > files/qa-notification-config.json'",
         input=raw)
result = call("shell", "am", "instrument", "-w", "-r", "-e", "class",
              package + ".QaNotificationConfigTest#" + methods[a.action],
              package + ".test/androidx.test.runner.AndroidJUnitRunner", text=True)
if "OK (1 test)" not in result.stdout:
    raise SystemExit("QA notification configuration failed; inspect instrumentation locally")
print(json.dumps({"action": a.action, "configured": a.action != "clear", "secret_output": False}))
