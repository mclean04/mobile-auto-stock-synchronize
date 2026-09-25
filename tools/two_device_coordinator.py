#!/usr/bin/env python3
"""Local-only deterministic barrier and aggregate oracle for two Android QA devices."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

READY_KEYS = {
    "run_id", "participant_id", "device_id", "request_id", "intent_id", "version",
    "source_id", "source_generation", "client_ready_at_utc", "client_ready_monotonic_ns",
}
EVENT_KEYS = {
    "run_id", "participant_id", "device_id", "request_id", "event", "at_utc",
    "elapsed_ms", "http_status", "code", "preflight_id", "order_id", "route", "result",
    "placed_request_id",
}
EVENTS = {
    "PREFLIGHT_ACCEPTED", "PREFLIGHT_CONFLICT", "PREFLIGHT_FAILED",
    "FAKE_BROKER_INVOKED", "FAKE_BROKER_ACCEPTED",
    "PLACED_REPORT_ACCEPTED", "PLACED_REPORT_FAILED", "PARTICIPANT_COMPLETE",
}
SAFE_ID = re.compile(r"[A-Za-z0-9._:-]{1,200}")


class CoordinationError(RuntimeError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


class TwoDeviceCoordinator:
    def __init__(self, run_id: str, participants: list[str], timeout_seconds: float = 30.0):
        if not SAFE_ID.fullmatch(run_id) or len(participants) != 2 or len(set(participants)) != 2:
            raise ValueError("run and exactly two unique participants are required")
        if not all(SAFE_ID.fullmatch(value) for value in participants):
            raise ValueError("participant identifier is invalid")
        if not 0.01 <= timeout_seconds <= 300:
            raise ValueError("timeout is outside the local QA bound")
        self.run_id = run_id
        self.participants = tuple(participants)
        self.timeout_seconds = timeout_seconds
        self.condition = threading.Condition()
        self.generation = 1
        self.state = "WAITING"
        self.abort_reason = None
        self.canonical = None
        self.ready_participants: dict[str, dict] = {}
        self.events: list[dict] = []
        self.release_at_utc = None
        self.release_monotonic_ns = None
        self.safety_violation = False

    @staticmethod
    def _safe_id(value, name):
        if not isinstance(value, str) or not SAFE_ID.fullmatch(value):
            raise CoordinationError(f"invalid_{name}")
        return value

    def _validate_ready(self, payload: dict) -> dict:
        if set(payload) != READY_KEYS or payload.get("run_id") != self.run_id:
            raise CoordinationError("invalid_ready_schema")
        for key in ("participant_id", "device_id", "request_id", "intent_id", "source_id"):
            self._safe_id(payload.get(key), key)
        if payload["participant_id"] not in self.participants:
            raise CoordinationError("unexpected_participant")
        if not isinstance(payload["version"], int) or payload["version"] <= 0:
            raise CoordinationError("invalid_version")
        if not isinstance(payload["source_generation"], int) or payload["source_generation"] <= 0:
            raise CoordinationError("invalid_source_generation")
        if not isinstance(payload["client_ready_monotonic_ns"], int) or payload["client_ready_monotonic_ns"] <= 0:
            raise CoordinationError("invalid_ready_clock")
        try:
            dt.datetime.fromisoformat(payload["client_ready_at_utc"].replace("Z", "+00:00"))
        except (TypeError, ValueError):
            raise CoordinationError("invalid_ready_timestamp")
        canonical = {key: payload[key] for key in ("intent_id", "version", "source_id", "source_generation")}
        return canonical

    def _abort_locked(self, reason: str):
        if self.state == "ABORTED":
            return
        self.state = "ABORTED"
        self.abort_reason = reason
        self.condition.notify_all()

    def ready(self, payload: dict) -> dict:
        canonical = self._validate_ready(payload)
        participant = payload["participant_id"]
        with self.condition:
            if self.state == "ABORTED":
                raise CoordinationError(self.abort_reason or "barrier_aborted")
            existing = self.ready_participants.get(participant)
            if existing is not None and existing != payload:
                self._abort_locked("participant_ready_changed")
                raise CoordinationError("participant_ready_changed")
            if self.canonical is not None and self.canonical != canonical:
                self._abort_locked("canonical_mismatch")
                raise CoordinationError("canonical_mismatch")
            if existing is None:
                self.canonical = canonical
                stored = dict(payload)
                stored["server_ready_at_utc"] = utc_now()
                stored["server_ready_monotonic_ns"] = time.monotonic_ns()
                self.ready_participants[participant] = stored
            if set(self.ready_participants) == set(self.participants) and self.state == "WAITING":
                devices = {value["device_id"] for value in self.ready_participants.values()}
                requests = {value["request_id"] for value in self.ready_participants.values()}
                if len(devices) != 2 or len(requests) != 2:
                    self._abort_locked("device_or_request_not_distinct")
                    raise CoordinationError("device_or_request_not_distinct")
                self.state = "RELEASED"
                self.release_at_utc = utc_now()
                self.release_monotonic_ns = time.monotonic_ns()
                self.condition.notify_all()
            deadline = time.monotonic() + self.timeout_seconds
            while self.state == "WAITING":
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    self._abort_locked("barrier_timeout")
                    break
                self.condition.wait(remaining)
            if self.state != "RELEASED":
                raise CoordinationError(self.abort_reason or "barrier_aborted")
            return {
                "released": True, "run_id": self.run_id, "generation": self.generation,
                "release_at_utc": self.release_at_utc,
                "release_monotonic_ns": self.release_monotonic_ns,
                "participants": list(self.participants), "canonical": dict(self.canonical),
            }

    def abort(self, participant_id: str, reason: str):
        self._safe_id(participant_id, "participant_id")
        self._safe_id(reason, "reason")
        with self.condition:
            if participant_id not in self.participants:
                raise CoordinationError("unexpected_participant")
            self._abort_locked(reason)

    def reset_after_abort(self):
        with self.condition:
            if self.state != "ABORTED":
                raise CoordinationError("reset_requires_aborted_generation")
            if any(event["event"] in {"FAKE_BROKER_INVOKED", "FAKE_BROKER_ACCEPTED"}
                   for event in self.events) or self.safety_violation:
                raise CoordinationError("broker_event_prevents_recovery")
            self.generation += 1
            self.state = "WAITING"
            self.abort_reason = None
            self.canonical = None
            self.ready_participants = {}
            self.events = []
            self.release_at_utc = None
            self.release_monotonic_ns = None
            self.safety_violation = False

    def event(self, payload: dict):
        if not set(payload).issubset(EVENT_KEYS) or set(payload) < {
            "run_id", "participant_id", "device_id", "request_id", "event", "at_utc"
        }:
            raise CoordinationError("invalid_event_schema")
        if payload["run_id"] != self.run_id or payload["event"] not in EVENTS:
            raise CoordinationError("invalid_event")
        participant = payload["participant_id"]
        with self.condition:
            ready = self.ready_participants.get(participant)
            if ready is None:
                raise CoordinationError("participant_not_ready")
            if payload["device_id"] != ready["device_id"] or payload["request_id"] != ready["request_id"]:
                raise CoordinationError("event_identity_mismatch")
            if payload["event"].startswith("FAKE_BROKER_") and \
                    payload.get("route") != "ANDROID_INJECTED_FAKE_ONLY":
                self.safety_violation = True
                self._abort_locked("non_fake_broker_route")
                raise CoordinationError("non_fake_broker_route")
            stored = dict(payload)
            stored["server_received_at_utc"] = utc_now()
            stored["server_received_monotonic_ns"] = time.monotonic_ns()
            self.events.append(stored)

    def status(self) -> dict:
        with self.condition:
            return {
                "schema_version": 1, "run_id": self.run_id, "generation": self.generation,
                "state": self.state, "abort_reason": self.abort_reason,
                "canonical": dict(self.canonical) if self.canonical else None,
                "release_at_utc": self.release_at_utc,
                "release_monotonic_ns": self.release_monotonic_ns,
                "ready": [dict(self.ready_participants[key]) for key in sorted(self.ready_participants)],
                "events": [dict(value) for value in self.events],
            }

    def oracle(self) -> dict:
        snapshot = self.status()
        events = snapshot["events"]
        counts = {name: sum(event["event"] == name for event in events) for name in EVENTS}
        accepted = [event for event in events if event["event"] == "PREFLIGHT_ACCEPTED"]
        conflicts = [event for event in events if event["event"] == "PREFLIGHT_CONFLICT"]
        winner = accepted[0]["participant_id"] if len(accepted) == 1 else None
        broker_events = [event for event in events if event["event"].startswith("FAKE_BROKER_")]
        placed = [event for event in events if event["event"] == "PLACED_REPORT_ACCEPTED"]
        order_ids = [event.get("order_id") for event in events
                     if event["event"] == "FAKE_BROKER_ACCEPTED"]
        complete = [event.get("result") for event in events if event["event"] == "PARTICIPANT_COMPLETE"]
        passed = (
            snapshot["state"] == "RELEASED" and len(snapshot["ready"]) == 2 and
            counts["PREFLIGHT_ACCEPTED"] == 1 and counts["PREFLIGHT_CONFLICT"] == 1 and
            accepted[0].get("http_status") in range(200, 300) and
            accepted[0].get("preflight_id") not in (None, "", "UNKNOWN") and
            conflicts[0].get("http_status") == 409 and
            conflicts[0].get("code") == "intent_execution_claimed" and
            all(isinstance(event.get("elapsed_ms"), int) and event["elapsed_ms"] >= 0
                for event in accepted + conflicts) and
            counts["PREFLIGHT_FAILED"] == 0 and counts["FAKE_BROKER_INVOKED"] <= 1 and
            counts["FAKE_BROKER_ACCEPTED"] <= 1 and counts["PLACED_REPORT_ACCEPTED"] <= 1 and
            all(event["participant_id"] == winner for event in broker_events + placed) and
            len(order_ids) == len(set(order_ids)) and
            sorted(complete) == ["CLAIM_CONFLICT", "SUBMITTED"]
        )
        return {"pass": passed, "counts": counts, "order_ids": order_ids,
                "participant_results": complete, "snapshot": snapshot}


def make_handler(coordinator: TwoDeviceCoordinator):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            return

        def _send(self, status, value):
            raw = json.dumps(value, separators=(",", ":"), sort_keys=True).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _body(self):
            length = int(self.headers.get("Content-Length", "0"))
            if length not in range(1, 65537):
                raise CoordinationError("invalid_body_length")
            return json.loads(self.rfile.read(length))

        def do_GET(self):
            if self.path != "/v1/status":
                self._send(404, {"error": "not_found"})
            else:
                self._send(200, coordinator.status())

        def do_POST(self):
            try:
                body = self._body()
                if self.path == "/v1/barrier/ready":
                    self._send(200, coordinator.ready(body))
                elif self.path == "/v1/barrier/abort":
                    coordinator.abort(body["participant_id"], body["reason"])
                    self._send(200, {"aborted": True})
                elif self.path == "/v1/events":
                    coordinator.event(body)
                    self._send(200, {"accepted": True})
                else:
                    self._send(404, {"error": "not_found"})
            except (CoordinationError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
                self._send(409, {"error": str(error)})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--participant", action="append", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--timeout-seconds", type=float, default=30)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        raise SystemExit("port must be unprivileged")
    coordinator = TwoDeviceCoordinator(args.run_id, args.participant, args.timeout_seconds)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(coordinator))
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
