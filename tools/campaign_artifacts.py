"""Resolve reviewed local APK pins without changing artifacts or device state."""
import hashlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE_ROOT = ROOT.parent / "Planning/evidence"
DEFAULTS = {
    "app": "app/build/outputs/apk/debug/app-debug.apk",
    "test": "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
}


def resolve_artifacts(android):
    result = {}
    for name, default in DEFAULTS.items():
        pin = android[name + "_sha256"]
        if not isinstance(pin, str) or not re.fullmatch(r"[0-9a-f]{64}", pin):
            raise ValueError("invalid_" + name + "_sha256")
        path = Path(android.get(name + "_artifact_path", default))
        path = (path if path.is_absolute() else ROOT / path).resolve()
        if not any(path.is_relative_to(base.resolve()) for base in (ROOT, EVIDENCE_ROOT)):
            raise ValueError("artifact_outside_reviewed_roots")
        if path.suffix != ".apk" or not path.is_file():
            raise ValueError("artifact_unavailable_" + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != pin:
            raise ValueError("unpinned_" + name + "_sha256")
        result[name] = (path, pin)
    return result


def artifact_arguments(artifacts):
    return [arg for name, (path, pin) in artifacts.items()
            for arg in ("--" + name + "-apk", str(path), "--" + name + "-sha256", pin)]


def add_artifact_options(parser):
    for name in DEFAULTS:
        parser.add_argument("--" + name + "-apk", type=Path)
        parser.add_argument("--" + name + "-sha256")


def artifacts_from_args(args, campaign):
    values = [getattr(args, name + suffix) for name in DEFAULTS for suffix in ("_apk", "_sha256")]
    if any(v is not None for v in values) or campaign:
        if any(v is None for v in values):
            raise ValueError("complete_campaign_artifact_pins_required")
        return resolve_artifacts({key: value for name in DEFAULTS for key, value in (
            (name + "_artifact_path", str(getattr(args, name + "_apk"))),
            (name + "_sha256", getattr(args, name + "_sha256")))})
    # Preserve legacy non-campaign use; DEC campaigns always require explicit pins.
    return {name: (ROOT / relative, hashlib.sha256((ROOT / relative).read_bytes()).hexdigest())
            for name, relative in DEFAULTS.items()}
