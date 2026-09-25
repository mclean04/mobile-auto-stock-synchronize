import datetime as dt
import unittest

from qa_session_guard import (
    SessionWindow,
    SessionWindowError,
    parse_reverse_list,
    reverse_restore_args,
)


class IntegratedQaSessionTest(unittest.TestCase):
    def test_business_cutoff_leaves_completion_window(self):
        window = SessionWindow.parse("2026-09-25T12:00:00Z", "2026-09-25T14:00:00Z")
        now = dt.datetime(2026, 9, 25, 13, 30, tzinfo=dt.timezone.utc)
        window.require_active(now)
        with self.assertRaisesRegex(SessionWindowError, "business_deadline_reached"):
            window.require_business(now)

    def test_exact_two_hour_window_is_active_and_has_stable_manifest(self):
        window = SessionWindow.parse("2026-09-25T12:00:00Z", "2026-09-25T14:00:00Z")
        window.require_active(dt.datetime(2026, 9, 25, 13, 0, tzinfo=dt.timezone.utc))
        self.assertEqual(3600, window.remaining_seconds(
            dt.datetime(2026, 9, 25, 13, 0, tzinfo=dt.timezone.utc)))
        self.assertEqual(7200, window.manifest()["duration_seconds"])
        self.assertTrue(window.manifest()["deadline_enforced"])

    def test_window_rejects_early_expired_and_longer_sessions(self):
        window = SessionWindow.parse("2026-09-25T12:00:00+00:00", "2026-09-25T14:00:00Z")
        with self.assertRaisesRegex(SessionWindowError, "session_not_started"):
            window.require_active(dt.datetime(2026, 9, 25, 11, 59, tzinfo=dt.timezone.utc))
        with self.assertRaisesRegex(SessionWindowError, "session_deadline_reached"):
            window.require_active(dt.datetime(2026, 9, 25, 14, 0, tzinfo=dt.timezone.utc))
        with self.assertRaisesRegex(SessionWindowError, "exactly_two_hours"):
            SessionWindow.parse("2026-09-25T12:00:00Z", "2026-09-25T14:00:01Z")
        with self.assertRaisesRegex(SessionWindowError, "exactly_two_hours"):
            SessionWindow.parse("2026-09-25T12:00:00Z", "2026-09-25T13:59:59Z")

    def test_route_snapshot_and_restore_preserve_preexisting_mapping(self):
        current = parse_reverse_list(
            "R52Y908PQXA tcp:8899 tcp:18899\nR52Y908PQXA tcp:8123 tcp:8123\n")
        self.assertEqual("tcp:18899", current["tcp:8899"])
        self.assertEqual(("reverse", "tcp:8899", "tcp:18899"),
                         reverse_restore_args("tcp:8899", current["tcp:8899"]))
        self.assertEqual(("reverse", "--remove", "tcp:9000"),
                         reverse_restore_args("tcp:9000", current.get("tcp:9000")))


if __name__ == "__main__":
    unittest.main()
