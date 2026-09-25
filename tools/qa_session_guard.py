#!/usr/bin/env python3
"""Pure guards shared by the bounded Sprint 1 QA runners."""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass


MAX_SESSION_SECONDS = 2 * 60 * 60


class SessionWindowError(ValueError):
    pass


def _parse_utc(value: str, field: str) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError) as error:
        raise SessionWindowError(f"invalid_{field}") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise SessionWindowError(f"{field}_must_be_timezone_aware")
    return parsed.astimezone(dt.timezone.utc)


@dataclass(frozen=True)
class SessionWindow:
    start: dt.datetime
    end: dt.datetime

    @classmethod
    def parse(cls, start: str, end: str) -> "SessionWindow":
        value = cls(_parse_utc(start, "session_start"), _parse_utc(end, "session_end"))
        seconds = (value.end - value.start).total_seconds()
        if seconds <= 0:
            raise SessionWindowError("session_end_must_follow_start")
        if seconds != MAX_SESSION_SECONDS:
            raise SessionWindowError("session_window_must_be_exactly_two_hours")
        return value

    def require_active(self, now: dt.datetime | None = None) -> None:
        current = (now or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
        if current < self.start:
            raise SessionWindowError("session_not_started")
        if current >= self.end:
            raise SessionWindowError("session_deadline_reached")

    def remaining_seconds(self, now: dt.datetime | None = None) -> float:
        current = (now or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
        return max(0.0, (self.end - current).total_seconds())

    def manifest(self) -> dict:
        return {
            "start_utc": self.start.isoformat().replace("+00:00", "Z"),
            "end_utc": self.end.isoformat().replace("+00:00", "Z"),
            "duration_seconds": int((self.end - self.start).total_seconds()),
            "deadline_enforced": True,
        }


def parse_reverse_list(raw: str) -> dict[str, str]:
    """Return local-to-remote mappings from `adb reverse --list` output."""
    result: dict[str, str] = {}
    for line in raw.splitlines():
        columns = line.split()
        if len(columns) >= 2:
            local, remote = columns[-2:]
            if local.startswith("tcp:") and remote.startswith("tcp:"):
                result[local] = remote
    return result


def reverse_restore_args(local: str, previous: str | None) -> tuple[str, ...]:
    if previous is None:
        return ("reverse", "--remove", local)
    return ("reverse", local, previous)
