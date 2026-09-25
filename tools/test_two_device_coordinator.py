import json
import threading
import time
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

from two_device_coordinator import CoordinationError, TwoDeviceCoordinator, make_handler


def ready(participant, device, request, intent="intent-1"):
    return {
        "run_id": "run-1", "participant_id": participant, "device_id": device,
        "request_id": request, "intent_id": intent, "version": 3,
        "source_id": "source-A", "source_generation": 7,
        "client_ready_at_utc": "2026-09-25T12:00:00+00:00",
        "client_ready_monotonic_ns": time.monotonic_ns(),
    }


class CoordinatorTest(unittest.TestCase):
    def release(self, coordinator):
        results = {}
        threads = [threading.Thread(target=lambda key, value: results.setdefault(key, coordinator.ready(value)),
                                    args=(participant, ready(participant, f"device-{index}", f"request-{index}")))
                   for index, participant in enumerate(("one", "two"), 1)]
        for thread in threads: thread.start()
        for thread in threads: thread.join(1)
        self.assertTrue(all(not thread.is_alive() for thread in threads))
        self.assertEqual({"one", "two"}, set(results))
        return results

    def test_deterministic_release_and_aggregate_oracle(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.5)
        self.release(coordinator)
        base = {"run_id": "run-1", "at_utc": "2026-09-25T12:00:01+00:00"}
        coordinator.event(base | {"participant_id": "one", "device_id": "device-1",
            "request_id": "request-1", "event": "PREFLIGHT_ACCEPTED", "http_status": 200,
            "preflight_id": "preflight-1", "elapsed_ms": 12})
        coordinator.event(base | {"participant_id": "two", "device_id": "device-2",
            "request_id": "request-2", "event": "PREFLIGHT_CONFLICT", "http_status": 409,
            "code": "intent_execution_claimed", "elapsed_ms": 14})
        for event in ("FAKE_BROKER_INVOKED", "FAKE_BROKER_ACCEPTED"):
            coordinator.event(base | {"participant_id": "one", "device_id": "device-1",
                "request_id": "request-1", "event": event,
                "route": "ANDROID_INJECTED_FAKE_ONLY", "order_id": "order-device-1"})
        coordinator.event(base | {"participant_id": "one", "device_id": "device-1",
            "request_id": "request-1", "event": "PLACED_REPORT_ACCEPTED", "http_status": 200})
        coordinator.event(base | {"participant_id": "one", "device_id": "device-1",
            "request_id": "request-1", "event": "PARTICIPANT_COMPLETE", "result": "SUBMITTED"})
        coordinator.event(base | {"participant_id": "two", "device_id": "device-2",
            "request_id": "request-2", "event": "PARTICIPANT_COMPLETE", "result": "CLAIM_CONFLICT"})
        self.assertTrue(coordinator.oracle()["pass"])

    def test_timeout_aborts_and_never_releases_one_device(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.03)
        with self.assertRaisesRegex(CoordinationError, "barrier_timeout"):
            coordinator.ready(ready("one", "device-1", "request-1"))
        self.assertEqual("ABORTED", coordinator.status()["state"])

    def test_one_device_failure_and_cancel_abort_waiter(self):
        for reason in ("participant_failed", "participant_cancelled"):
            coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.5)
            errors = []
            thread = threading.Thread(target=lambda: self._capture(
                lambda: coordinator.ready(ready("one", "device-1", "request-1")), errors))
            thread.start(); time.sleep(0.02)
            coordinator.abort("two", reason)
            thread.join(1)
            self.assertEqual(reason, str(errors[0]))
            self.assertEqual("ABORTED", coordinator.status()["state"])

    def test_abort_can_recover_new_generation_only_before_broker(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.02)
        with self.assertRaises(CoordinationError):
            coordinator.ready(ready("one", "device-1", "request-1"))
        coordinator.reset_after_abort()
        self.assertEqual(2, coordinator.generation)
        self.release(coordinator)

    def test_non_fake_route_aborts(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.5)
        self.release(coordinator)
        with self.assertRaisesRegex(CoordinationError, "non_fake_broker_route"):
            coordinator.event({"run_id": "run-1", "participant_id": "one",
                "device_id": "device-1", "request_id": "request-1",
                "event": "FAKE_BROKER_INVOKED", "at_utc": "2026-09-25T12:00:01+00:00",
                "route": "PRODUCTION"})
        self.assertEqual("ABORTED", coordinator.status()["state"])

    def test_aggregate_oracle_rejects_two_economic_actions(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.5)
        self.release(coordinator)
        base = {"run_id": "run-1", "at_utc": "2026-09-25T12:00:01+00:00"}
        for index, participant in enumerate(("one", "two"), 1):
            identity = {"participant_id": participant, "device_id": f"device-{index}",
                        "request_id": f"request-{index}"}
            coordinator.event(base | identity | {"event": "PREFLIGHT_ACCEPTED", "http_status": 200,
                "preflight_id": f"preflight-{index}"})
            coordinator.event(base | identity | {"event": "FAKE_BROKER_INVOKED",
                "route": "ANDROID_INJECTED_FAKE_ONLY", "order_id": f"order-{index}"})
            coordinator.event(base | identity | {"event": "FAKE_BROKER_ACCEPTED",
                "route": "ANDROID_INJECTED_FAKE_ONLY", "order_id": f"order-{index}"})
            coordinator.event(base | identity | {"event": "PARTICIPANT_COMPLETE", "result": "SUBMITTED"})
        self.assertFalse(coordinator.oracle()["pass"])
        self.assertEqual(2, coordinator.oracle()["counts"]["FAKE_BROKER_ACCEPTED"])

    def test_loopback_http_barrier_round_trip(self):
        coordinator = TwoDeviceCoordinator("run-1", ["one", "two"], 0.5)
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(coordinator))
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        results = []
        def post(payload):
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/v1/barrier/ready",
                data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=1) as response:
                results.append(json.load(response))
        threads = [threading.Thread(target=post,
            args=(ready(participant, f"device-{index}", f"request-{index}"),))
            for index, participant in enumerate(("one", "two"), 1)]
        try:
            for thread in threads: thread.start()
            for thread in threads: thread.join(1)
            self.assertEqual(2, len(results))
            self.assertTrue(all(result["released"] for result in results))
        finally:
            server.shutdown(); server.server_close()

    @staticmethod
    def _capture(block, output):
        try: block()
        except Exception as error: output.append(error)


if __name__ == "__main__":
    unittest.main()
