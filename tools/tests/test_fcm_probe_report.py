import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "fcm_probe_report.py"
SPEC = importlib.util.spec_from_file_location("fcm_probe_report", MODULE_PATH)
assert SPEC and SPEC.loader
reporter = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = reporter
SPEC.loader.exec_module(reporter)


def sender_records(
    *,
    test_id="probe_1",
    sent_at=10_000,
    sender_status="SENT_ACCEPTED",
    http_status=200,
):
    intent = {
        "record_type": "INTENT",
        "send_status": "INTENT_RECORDED",
        "delivery_status": "UNKNOWN",
        "endpoint": "https://fcm.googleapis.com/v1/projects/example-project/messages:send",
        "host_before_request_epoch_ms": sent_at,
        "message": {
            "data": {
                "guardian_probe_version": "1",
                "test_id": test_id,
                "sent_at_epoch_ms": str(sent_at),
            },
            "android": {"priority": "HIGH", "ttl": "60s"},
            "target": "OWNED_SINGLE_TOKEN_REDACTED",
        },
        "single_attempt": True,
    }
    outcome = {
        "record_type": "OUTCOME",
        "send_status": sender_status,
        "delivery_status": "UNKNOWN",
        "http_status": http_status,
        "host_after_response_epoch_ms": sent_at + 5,
    }
    if sender_status != "SENT_ACCEPTED":
        outcome["retry_performed"] = False
    return [intent, outcome]


def receipt_line(
    *,
    test_id="probe_1",
    sent_at=10_000,
    received_at=10_120,
    priority="HIGH",
    interactive="NO",
    idle="YES",
    network="AVAILABLE",
    notification="POST_REQUESTED",
    duration="15",
    process_started_at=None,
):
    fields = [
        test_id,
        str(sent_at),
        str(received_at),
        "9000",
        priority,
        interactive,
        idle,
        network,
        notification,
        str(duration),
    ]
    if process_started_at is not None:
        fields.append(str(process_started_at))
    return "|".join(fields)


class FcmProbeReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.sender = self.root / "sender.jsonl"
        self.receipt = self.root / "receipt.txt"
        self.descriptor = self.root / "descriptor.json"

    def tearDown(self):
        self.temp.cleanup()

    def write_sender(self, records=None):
        records = records or sender_records()
        self.sender.write_bytes(
            "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records).encode("utf-8"),
        )

    def write_receipts(self, lines, *, header=None):
        self.receipt.write_bytes(
            ((header or reporter.RECEIPT_HEADER) + "\n"
            + "".join(line + "\n" for line in lines)).encode("utf-8"),
        )

    def write_descriptor(self, *, count, start=9_000, end=20_000, cutoff=None):
        value = {
            "schema": reporter.DESCRIPTOR_SCHEMA,
            "trust": reporter.DESCRIPTOR_TRUST,
            "receipt_sha256": hashlib.sha256(self.receipt.read_bytes()).hexdigest(),
            "complete_snapshot": True,
            "history_capacity": 50,
            "receipt_count": count,
            "window_start_host_epoch_ms": start,
            "window_end_host_epoch_ms": end,
        }
        if cutoff is not None:
            value["recovery_cutoff_device_epoch_ms"] = cutoff
        self.descriptor.write_text(json.dumps(value), encoding="utf-8")

    def run_main(self, *args):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = reporter.main(list(args))
        out = json.loads(stdout.getvalue()) if stdout.getvalue() else None
        err = json.loads(stderr.getvalue()) if stderr.getvalue() else None
        return code, out, err, stdout.getvalue() + stderr.getvalue()

    def test_accepted_without_export_remains_awaiting_even_after_ttl(self):
        self.write_sender()
        code, result, error, _ = self.run_main(
            "--sender", str(self.sender),
            "--observation-host-epoch-ms", "999999999",
        )
        self.assertEqual(0, code)
        self.assertIsNone(error)
        self.assertEqual("AWAITING_DEVICE_EVIDENCE", result["report_status"])
        self.assertEqual("SENT_ACCEPTED", result["sender_status"])
        self.assertEqual("NO_RECEIPT_EXPORT", result["coverage_status"])

    def test_trusted_snapshot_without_id_is_not_observed_with_bounded_history(self):
        self.write_sender()
        lines = [receipt_line(test_id=f"other_{index}", sent_at=9001 + index) for index in range(50)]
        self.write_receipts(lines)
        self.write_descriptor(count=50)
        code, result, _, _ = self.run_main(
            "--sender", str(self.sender),
            "--receipt", str(self.receipt),
            "--export-descriptor", str(self.descriptor),
            "--observation-host-epoch-ms", "20001",
        )
        self.assertEqual(0, code)
        self.assertEqual("NOT_OBSERVED", result["report_status"])
        self.assertEqual("NOT_PRESENT_IN_TRUSTED_BOUNDED_SNAPSHOT", result["coverage_status"])
        self.assertTrue(result["history_limit_reached"])
        self.assertEqual(50, result["history_capacity"])

    def test_missing_observation_or_outside_window_stays_unknown(self):
        self.write_sender()
        self.write_receipts([receipt_line(test_id="other")])
        self.write_descriptor(count=1)
        _, without_time, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt),
            "--export-descriptor", str(self.descriptor),
        )
        self.assertEqual("UNKNOWN", without_time["report_status"])
        self.assertEqual("OBSERVATION_TIME_REQUIRED", without_time["coverage_status"])

        self.write_descriptor(count=1, start=11_000, end=12_000)
        _, outside, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt),
            "--export-descriptor", str(self.descriptor),
            "--observation-host-epoch-ms", "12000",
        )
        self.assertEqual("UNKNOWN", outside["report_status"])
        self.assertEqual("OUTSIDE_TRUSTED_WINDOW", outside["coverage_status"])

    def test_received_slow_processing_keeps_notification_display_separate(self):
        self.write_sender()
        self.write_receipts([receipt_line(duration="1001", notification="POST_REQUESTED")])
        code, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(0, code)
        self.assertEqual("RECEIVED", result["report_status"])
        self.assertEqual("CONFIRMED", result["callback_status"])
        self.assertEqual("PROCESSING_SLOW", result["processing_status"])
        self.assertEqual("POST_REQUESTED", result["notification_outcome"])
        self.assertEqual("NOT_CONFIRMED", result["notification_display_status"])

    def test_missing_duration_is_pending_and_unavailable_network_is_point_context(self):
        self.write_sender()
        self.write_receipts([receipt_line(duration="-", network="UNAVAILABLE")])
        _, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual("RECEIVED", result["report_status"])
        self.assertEqual("PROCESSING_PENDING", result["processing_status"])
        self.assertIsNone(result["handler_duration_ms"])
        self.assertEqual("UNAVAILABLE", result["network_at_callback"])

    def test_v1_receipt_reports_unknown_process_start(self):
        self.write_sender()
        self.write_receipts([receipt_line()])
        code, result, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(0, code)
        self.assertIsNone(error)
        self.assertIsNone(result["process_started_at_elapsed_ms"])
        self.assertIsNone(result["process_age_at_callback_ms"])
        self.assertEqual(
            "LEGACY_V1_FIELD_UNAVAILABLE",
            result["process_start_not_observed_cause"],
        )

    def test_v2_receipt_reports_coherent_process_age_with_claim_limit(self):
        self.write_sender()
        self.write_receipts(
            [receipt_line(process_started_at="8750")],
            header=reporter.RECEIPT_HEADER_V2,
        )
        code, result, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(0, code)
        self.assertIsNone(error)
        self.assertEqual(8750, result["process_started_at_elapsed_ms"])
        self.assertEqual(250, result["process_age_at_callback_ms"])
        self.assertIsNone(result["process_start_not_observed_cause"])
        self.assertEqual(
            "PROCESS_TIMING_ALONE_DOES_NOT_PROVE_EXCLUSIVE_FCM_WAKE_OR_"
            "PRE_SEND_PROCESS_ABSENCE",
            result["lifecycle_claim_limit"],
        )

    def test_v2_future_process_start_is_invalid_input(self):
        self.write_sender()
        self.write_receipts(
            [receipt_line(process_started_at="9001")],
            header=reporter.RECEIPT_HEADER_V2,
        )
        code, _, error, combined = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertEqual("RECEIPT_CORRUPT", error["error_code"])
        self.assertNotIn(str(self.receipt), combined)

    def test_unsupported_receipt_header_is_invalid_input(self):
        self.write_sender()
        self.write_receipts([receipt_line()], header="GUARDIAN_FCM_EVIDENCE_V3")
        code, _, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertEqual("RECEIPT_CORRUPT", error["error_code"])

    def test_lf_fixture_is_exact_and_crlf_receipt_is_rejected(self):
        self.write_sender()
        self.write_receipts([receipt_line()])
        raw = self.receipt.read_bytes()
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b"\r", raw)
        self.assertNotIn(b"\r", self.sender.read_bytes())
        self.receipt.write_bytes(raw.replace(b"\n", b"\r\n"))
        code, result, error, combined = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertIsNone(result)
        self.assertEqual("RECEIPT_CORRUPT", error["error_code"])
        self.assertNotIn(str(self.receipt), combined)

    def test_v2_negative_process_start_is_invalid_without_echo(self):
        self.write_sender()
        secret = "-1"
        self.write_receipts(
            [receipt_line(process_started_at=secret)],
            header=reporter.RECEIPT_HEADER_V2,
        )
        code, _, error, combined = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertEqual("RECEIPT_CORRUPT", error["error_code"])
        self.assertNotIn(str(self.receipt), combined)

    def test_matched_callback_overrides_uncertain_send_but_retains_sender_status(self):
        self.write_sender(sender_records(
            sender_status="SEND_OUTCOME_UNCERTAIN", http_status=503
        ))
        self.write_receipts([receipt_line()])
        _, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual("RECEIVED", result["report_status"])
        self.assertEqual("SEND_OUTCOME_UNCERTAIN", result["sender_status"])
        self.assertEqual("CONFIRMED", result["callback_status"])

    def test_rejected_send_with_callback_is_evidence_conflict(self):
        self.write_sender(sender_records(sender_status="SEND_REJECTED", http_status=400))
        self.write_receipts([receipt_line()])
        code, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(1, code)
        self.assertEqual("EVIDENCE_CONFLICT", result["report_status"])
        self.assertEqual("REJECTED_SEND_HAS_MATCHED_CALLBACK", result["conflict_status"])
        self.assertEqual("CONFIRMED", result["callback_status"])

    def test_same_test_id_with_different_sent_at_is_conflict(self):
        self.write_sender()
        self.write_receipts([receipt_line(sent_at=10_001)])
        code, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(1, code)
        self.assertEqual("EVIDENCE_CONFLICT", result["report_status"])
        self.assertEqual("TEST_ID_SENT_AT_MISMATCH", result["conflict_status"])

    def test_duplicate_or_unknown_receipt_data_is_rejected(self):
        self.write_sender()
        duplicate = receipt_line()
        self.write_receipts([duplicate, duplicate])
        code, _, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertEqual("RECEIPT_DUPLICATE_TEST_ID", error["error_code"])

        self.write_receipts([receipt_line(network="MAYBE")])
        code, _, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual(2, code)
        self.assertEqual("RECEIPT_CORRUPT", error["error_code"])

    def test_negative_raw_delta_is_preserved_and_calibration_only_adds_range(self):
        self.write_sender()
        self.write_receipts([receipt_line(received_at=9_900)])
        _, raw, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt)
        )
        self.assertEqual("RAW_ONLY_UNCALIBRATED", raw["timing"]["status"])
        self.assertEqual(-100, raw["timing"]["raw_wall_delta_ms"])
        self.assertNotIn("latency_status", raw["timing"])

        _, adjusted, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt),
            "--phone-minus-host-offset-ms", "-150", "--clock-error-ms", "20",
        )
        self.assertEqual("ADJUSTED_RANGE_AVAILABLE", adjusted["timing"]["status"])
        self.assertEqual(30.0, adjusted["timing"]["adjusted_delta_min_ms"])
        self.assertEqual(70.0, adjusted["timing"]["adjusted_delta_max_ms"])

    def test_recovery_cutoff_marks_late_receipt_contaminated(self):
        self.write_sender()
        self.write_receipts([receipt_line(received_at=10_120)])
        self.write_descriptor(count=1, cutoff=10_100)
        _, result, _, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt),
            "--export-descriptor", str(self.descriptor),
        )
        self.assertEqual("RECEIVED", result["report_status"])
        self.assertEqual("POST_RECOVERY_CONTAMINATED", result["contamination_status"])

    def test_malformed_input_never_echoes_values_paths_or_error_contents(self):
        secret = "SUPER_SECRET_/private/credential.json"
        self.sender.write_text(secret + "\n", encoding="utf-8")
        code, _, error, combined = self.run_main("--sender", str(self.sender))
        self.assertEqual(2, code)
        self.assertEqual("SENDER_CORRUPT", error["error_code"])
        self.assertNotIn(secret, combined)
        self.assertNotIn(str(self.sender), combined)
        self.assertNotIn(str(self.root), combined)

    def test_descriptor_hash_mismatch_and_incomplete_clock_calibration_fail_closed(self):
        self.write_sender()
        self.write_receipts([])
        self.write_descriptor(count=0)
        descriptor = json.loads(self.descriptor.read_text(encoding="utf-8"))
        descriptor["receipt_sha256"] = "0" * 64
        self.descriptor.write_text(json.dumps(descriptor), encoding="utf-8")
        code, _, error, _ = self.run_main(
            "--sender", str(self.sender), "--receipt", str(self.receipt),
            "--export-descriptor", str(self.descriptor),
        )
        self.assertEqual(2, code)
        self.assertEqual("DESCRIPTOR_INVALID", error["error_code"])

        code, _, error, _ = self.run_main(
            "--sender", str(self.sender), "--phone-minus-host-offset-ms", "1"
        )
        self.assertEqual(2, code)
        self.assertEqual("INCOMPLETE_CLOCK_CALIBRATION", error["error_code"])


if __name__ == "__main__":
    unittest.main()
