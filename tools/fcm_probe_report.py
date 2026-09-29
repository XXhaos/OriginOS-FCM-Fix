#!/usr/bin/env python3
"""Correlate one sanitized FCM sender audit with an optional Guardian receipt export.

This tool is deliberately offline.  It reads bounded local evidence and writes one
privacy-bounded JSON report to stdout.  It never sends a probe or accesses a device.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence


REPORT_SCHEMA = "GUARDIAN_FCM_PROBE_REPORT_V1"
DESCRIPTOR_SCHEMA = "GUARDIAN_FCM_TRUSTED_EXPORT_V1"
DESCRIPTOR_TRUST = "EXPLICIT_OPERATOR_TRUST"
RECEIPT_HEADER_V1 = "GUARDIAN_FCM_EVIDENCE_V1"
RECEIPT_HEADER_V2 = "GUARDIAN_FCM_EVIDENCE_V2"
# Retained for callers/tests that construct the legacy export explicitly.
RECEIPT_HEADER = RECEIPT_HEADER_V1

MAX_SENDER_BYTES = 64 * 1024
MAX_RECEIPT_BYTES = 64 * 1024
MAX_DESCRIPTOR_BYTES = 8 * 1024
MAX_RECEIPTS = 50
MAX_EPOCH_MS = 9_223_372_036_854_775_807
MAX_THRESHOLD_MS = 86_400_000
MAX_CLOCK_VALUE_MS = 86_400_000.0

TEST_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
ENDPOINT_RE = re.compile(
    r"https://fcm\.googleapis\.com/v1/projects/[a-z][a-z0-9-]{4,28}[a-z0-9]/messages:send\Z"
)
MESSAGE_NAME_RE = re.compile(
    r"projects/[A-Za-z0-9_-]{1,128}/messages/[A-Za-z0-9%._~+:-]{1,512}\Z"
)
SHA256_RE = re.compile(r"[0-9a-fA-F]{64}\Z")

SENDER_STATUSES = frozenset({
    "SENT_ACCEPTED",
    "SEND_REJECTED",
    "SEND_OUTCOME_UNCERTAIN",
})
ERROR_STATUSES = frozenset({
    "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND",
    "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION",
    "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE",
    "DATA_LOSS", "UNAUTHENTICATED", "TRANSPORT_ERROR",
})
PRIORITIES = frozenset({"HIGH", "NORMAL", "UNKNOWN"})
OBSERVED_BOOLEANS = frozenset({"YES", "NO", "UNKNOWN"})
NETWORK_AVAILABILITIES = frozenset({"AVAILABLE", "UNAVAILABLE", "UNKNOWN"})
NOTIFICATION_OUTCOMES = frozenset({
    "RECEIPT_STORED",
    "PERMISSION_DENIED",
    "APP_NOTIFICATIONS_BLOCKED",
    "CHANNEL_BLOCKED",
    "POST_REQUESTED",
    "POST_FAILED",
})


class ReportInputError(Exception):
    """A bounded error represented by a fixed code only."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, _message: str) -> None:
        raise ReportInputError("INVALID_ARGUMENTS")


@dataclass(frozen=True)
class SenderEvidence:
    test_id: str
    sent_at_epoch_ms: int
    sender_status: str
    priority: str
    ttl_seconds: int


@dataclass(frozen=True)
class Receipt:
    test_id: str
    sent_at_epoch_ms: int
    received_at_epoch_ms: int
    received_at_elapsed_ms: int
    priority: str
    interactive: str
    idle: str
    network: str
    notification_outcome: str
    handler_duration_ms: int | None
    process_started_at_elapsed_ms: int | None
    process_start_not_observed_cause: str | None


@dataclass(frozen=True)
class ExportDescriptor:
    receipt_count: int
    window_start_host_epoch_ms: int
    window_end_host_epoch_ms: int
    recovery_cutoff_device_epoch_ms: int | None


def _is_int(value: object) -> bool:
    return type(value) is int


def _bounded_positive_int(value: object, code: str, *, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if not _is_int(value) or not minimum <= value <= MAX_EPOCH_MS:
        raise ReportInputError(code)
    return value


def _require_keys(
    value: object,
    *,
    required: frozenset[str],
    optional: frozenset[str] = frozenset(),
    code: str,
) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise ReportInputError(code)
    keys = frozenset(value)
    if not required <= keys or not keys <= required | optional:
        raise ReportInputError(code)
    return value


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ReportInputError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def _decode_json(raw: bytes, code: str) -> object:
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicate_keys)
    except ReportInputError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, TypeError):
        raise ReportInputError(code) from None


def _read_bounded(path: Path, limit: int, code: str) -> bytes:
    try:
        with path.open("rb") as stream:
            raw = stream.read(limit + 1)
    except (OSError, ValueError):
        raise ReportInputError(code) from None
    if len(raw) > limit:
        raise ReportInputError(code + "_TOO_LARGE")
    return raw


def _parse_int_text(value: str | None, code: str, *, allow_zero: bool = False) -> int | None:
    if value is None:
        return None
    if not re.fullmatch(r"[0-9]{1,19}", value):
        raise ReportInputError(code)
    return _bounded_positive_int(int(value), code, allow_zero=allow_zero)


def _parse_float_text(value: str | None, code: str, *, nonnegative: bool) -> float | None:
    if value is None:
        return None
    try:
        parsed = float(value)
    except (TypeError, ValueError, OverflowError):
        raise ReportInputError(code) from None
    if not math.isfinite(parsed) or abs(parsed) > MAX_CLOCK_VALUE_MS:
        raise ReportInputError(code)
    if nonnegative and parsed < 0:
        raise ReportInputError(code)
    return parsed


def parse_sender(path: Path) -> SenderEvidence:
    raw = _read_bounded(path, MAX_SENDER_BYTES, "SENDER_UNREADABLE")
    if not raw or not raw.endswith(b"\n"):
        raise ReportInputError("SENDER_CORRUPT")
    lines = raw.splitlines()
    if len(lines) not in (1, 2) or any(not line for line in lines):
        raise ReportInputError("SENDER_CORRUPT")
    records = [_decode_json(line, "SENDER_CORRUPT") for line in lines]

    intent = _require_keys(
        records[0],
        required=frozenset({
            "record_type", "send_status", "delivery_status", "endpoint",
            "host_before_request_epoch_ms", "message", "single_attempt",
        }),
        code="SENDER_INTENT_INVALID",
    )
    if (
        intent["record_type"] != "INTENT"
        or intent["send_status"] != "INTENT_RECORDED"
        or intent["delivery_status"] != "UNKNOWN"
        or intent["single_attempt"] is not True
        or not isinstance(intent["endpoint"], str)
        or not ENDPOINT_RE.fullmatch(intent["endpoint"])
    ):
        raise ReportInputError("SENDER_INTENT_INVALID")
    before_ms = _bounded_positive_int(
        intent["host_before_request_epoch_ms"], "SENDER_INTENT_INVALID"
    )
    message = _require_keys(
        intent["message"],
        required=frozenset({"data", "android", "target"}),
        code="SENDER_INTENT_INVALID",
    )
    if message["target"] != "OWNED_SINGLE_TOKEN_REDACTED":
        raise ReportInputError("SENDER_INTENT_INVALID")
    data = _require_keys(
        message["data"],
        required=frozenset({"guardian_probe_version", "test_id", "sent_at_epoch_ms"}),
        code="SENDER_INTENT_INVALID",
    )
    android = _require_keys(
        message["android"],
        required=frozenset({"priority", "ttl"}),
        code="SENDER_INTENT_INVALID",
    )
    test_id = data["test_id"]
    sent_at_text = data["sent_at_epoch_ms"]
    if (
        data["guardian_probe_version"] != "1"
        or not isinstance(test_id, str)
        or not TEST_ID_RE.fullmatch(test_id)
        or not isinstance(sent_at_text, str)
        or sent_at_text != str(before_ms)
        or android["priority"] not in ("HIGH", "NORMAL")
        or android["ttl"] != "60s"
    ):
        raise ReportInputError("SENDER_INTENT_INVALID")

    if len(records) == 1:
        sender_status = "SEND_OUTCOME_UNCERTAIN"
    else:
        outcome = _require_keys(
            records[1],
            required=frozenset({
                "record_type", "send_status", "delivery_status",
                "host_after_response_epoch_ms",
            }),
            optional=frozenset({
                "http_status", "retry_performed", "error_status", "error_code", "message_name",
            }),
            code="SENDER_OUTCOME_INVALID",
        )
        sender_status = outcome["send_status"]
        if (
            outcome["record_type"] != "OUTCOME"
            or sender_status not in SENDER_STATUSES
            or outcome["delivery_status"] != "UNKNOWN"
        ):
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        after_ms = _bounded_positive_int(
            outcome["host_after_response_epoch_ms"], "SENDER_OUTCOME_INVALID"
        )
        if after_ms < before_ms:
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        http_status = outcome.get("http_status")
        if http_status is not None and (
            not _is_int(http_status) or not 100 <= http_status <= 599
        ):
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        if sender_status == "SENT_ACCEPTED" and (
            http_status is None or not 200 <= http_status < 300
        ):
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        definitely_rejected = (
            _is_int(http_status)
            and 400 <= http_status < 500
            and http_status not in (408, 409, 425, 429)
        )
        if sender_status == "SEND_REJECTED" and not definitely_rejected:
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        if sender_status == "SEND_OUTCOME_UNCERTAIN" and definitely_rejected:
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        if "retry_performed" in outcome and outcome["retry_performed"] is not False:
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        if "error_status" in outcome and outcome["error_status"] not in ERROR_STATUSES:
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        error_code = outcome.get("error_code")
        if error_code is not None and (
            not _is_int(error_code) or not 100 <= error_code <= 599
        ):
            raise ReportInputError("SENDER_OUTCOME_INVALID")
        message_name = outcome.get("message_name")
        if message_name is not None and (
            not isinstance(message_name, str) or not MESSAGE_NAME_RE.fullmatch(message_name)
        ):
            raise ReportInputError("SENDER_OUTCOME_INVALID")

    return SenderEvidence(
        test_id=test_id,
        sent_at_epoch_ms=before_ms,
        sender_status=sender_status,
        priority=android["priority"],
        ttl_seconds=60,
    )


def parse_receipts(path: Path) -> tuple[bytes, list[Receipt]]:
    raw = _read_bounded(path, MAX_RECEIPT_BYTES, "RECEIPT_UNREADABLE")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ReportInputError("RECEIPT_CORRUPT") from None
    if not text or not text.endswith("\n") or "\r" in text:
        raise ReportInputError("RECEIPT_CORRUPT")
    lines = text.split("\n")
    header = lines[0]
    if header not in (RECEIPT_HEADER_V1, RECEIPT_HEADER_V2) or lines[-1] != "":
        raise ReportInputError("RECEIPT_CORRUPT")
    expected_fields = 10 if header == RECEIPT_HEADER_V1 else 11
    encoded = lines[1:-1]
    if len(encoded) > MAX_RECEIPTS or any(not line for line in encoded):
        raise ReportInputError("RECEIPT_CORRUPT")

    receipts: list[Receipt] = []
    seen_ids: set[str] = set()
    for line in encoded:
        fields = line.split("|")
        if len(fields) != expected_fields or not TEST_ID_RE.fullmatch(fields[0]):
            raise ReportInputError("RECEIPT_CORRUPT")
        if fields[0] in seen_ids:
            raise ReportInputError("RECEIPT_DUPLICATE_TEST_ID")
        seen_ids.add(fields[0])
        try:
            sent_at = int(fields[1])
            received_at = int(fields[2])
            received_elapsed = int(fields[3])
            duration = None if fields[9] == "-" else int(fields[9])
        except ValueError:
            raise ReportInputError("RECEIPT_CORRUPT") from None
        if header == RECEIPT_HEADER_V1:
            process_started_elapsed = None
            process_start_not_observed_cause = "LEGACY_V1_FIELD_UNAVAILABLE"
        elif fields[10] == "-":
            process_started_elapsed = None
            process_start_not_observed_cause = "V2_PROCESS_START_NOT_OBSERVED"
        elif re.fullmatch(r"[0-9]{1,19}", fields[10]):
            process_started_elapsed = int(fields[10])
            process_start_not_observed_cause = None
        else:
            raise ReportInputError("RECEIPT_CORRUPT")
        if (
            not 1 <= sent_at <= MAX_EPOCH_MS
            or not 1 <= received_at <= MAX_EPOCH_MS
            or not 0 <= received_elapsed <= MAX_EPOCH_MS
            or duration is not None and not 0 <= duration <= MAX_EPOCH_MS
            or process_started_elapsed is not None
            and not 0 <= process_started_elapsed <= received_elapsed
            or fields[4] not in PRIORITIES
            or fields[5] not in OBSERVED_BOOLEANS
            or fields[6] not in OBSERVED_BOOLEANS
            or fields[7] not in NETWORK_AVAILABILITIES
            or fields[8] not in NOTIFICATION_OUTCOMES
        ):
            raise ReportInputError("RECEIPT_CORRUPT")
        receipts.append(Receipt(
            test_id=fields[0],
            sent_at_epoch_ms=sent_at,
            received_at_epoch_ms=received_at,
            received_at_elapsed_ms=received_elapsed,
            priority=fields[4],
            interactive=fields[5],
            idle=fields[6],
            network=fields[7],
            notification_outcome=fields[8],
            handler_duration_ms=duration,
            process_started_at_elapsed_ms=process_started_elapsed,
            process_start_not_observed_cause=process_start_not_observed_cause,
        ))
    return raw, receipts


def parse_descriptor(path: Path, receipt_raw: bytes, receipt_count: int) -> ExportDescriptor:
    value = _decode_json(
        _read_bounded(path, MAX_DESCRIPTOR_BYTES, "DESCRIPTOR_UNREADABLE"),
        "DESCRIPTOR_CORRUPT",
    )
    descriptor = _require_keys(
        value,
        required=frozenset({
            "schema", "trust", "receipt_sha256", "complete_snapshot", "history_capacity",
            "receipt_count", "window_start_host_epoch_ms", "window_end_host_epoch_ms",
        }),
        optional=frozenset({"recovery_cutoff_device_epoch_ms"}),
        code="DESCRIPTOR_INVALID",
    )
    digest = descriptor["receipt_sha256"]
    count = descriptor["receipt_count"]
    capacity = descriptor["history_capacity"]
    if (
        descriptor["schema"] != DESCRIPTOR_SCHEMA
        or descriptor["trust"] != DESCRIPTOR_TRUST
        or descriptor["complete_snapshot"] is not True
        or capacity != MAX_RECEIPTS
        or not _is_int(count)
        or not 0 <= count <= MAX_RECEIPTS
        or count != receipt_count
        or not isinstance(digest, str)
        or not SHA256_RE.fullmatch(digest)
        or digest.lower() != hashlib.sha256(receipt_raw).hexdigest()
    ):
        raise ReportInputError("DESCRIPTOR_INVALID")
    window_start = _bounded_positive_int(
        descriptor["window_start_host_epoch_ms"], "DESCRIPTOR_INVALID"
    )
    window_end = _bounded_positive_int(
        descriptor["window_end_host_epoch_ms"], "DESCRIPTOR_INVALID"
    )
    if window_end < window_start:
        raise ReportInputError("DESCRIPTOR_INVALID")
    cutoff_value = descriptor.get("recovery_cutoff_device_epoch_ms")
    cutoff = None if cutoff_value is None else _bounded_positive_int(
        cutoff_value, "DESCRIPTOR_INVALID"
    )
    return ExportDescriptor(count, window_start, window_end, cutoff)


def _timing(receipt: Receipt | None, offset_ms: float | None, error_ms: float | None) -> dict:
    if receipt is None:
        return {"status": "NOT_AVAILABLE"}
    raw_delta = receipt.received_at_epoch_ms - receipt.sent_at_epoch_ms
    result: dict[str, object] = {
        "status": "RAW_ONLY_UNCALIBRATED",
        "raw_wall_delta_ms": raw_delta,
    }
    if offset_ms is not None and error_ms is not None:
        adjusted = raw_delta - offset_ms
        result = {
            "status": "ADJUSTED_RANGE_AVAILABLE",
            "raw_wall_delta_ms": raw_delta,
            "phone_minus_host_offset_ms": offset_ms,
            "clock_error_ms": error_ms,
            "adjusted_delta_min_ms": adjusted - error_ms,
            "adjusted_delta_max_ms": adjusted + error_ms,
        }
    return result


def build_report(
    sender: SenderEvidence,
    *,
    receipts: list[Receipt] | None,
    descriptor: ExportDescriptor | None,
    observation_host_epoch_ms: int | None,
    processing_threshold_ms: int,
    phone_minus_host_offset_ms: float | None,
    clock_error_ms: float | None,
) -> dict:
    matching_id = [] if receipts is None else [r for r in receipts if r.test_id == sender.test_id]
    if matching_id and matching_id[0].sent_at_epoch_ms != sender.sent_at_epoch_ms:
        return {
            "schema": REPORT_SCHEMA,
            "report_status": "EVIDENCE_CONFLICT",
            "conflict_status": "TEST_ID_SENT_AT_MISMATCH",
            "test_id": sender.test_id,
            "sender_status": sender.sender_status,
            "callback_status": "UNKNOWN",
            "coverage_status": "CONFLICT",
            "processing_status": "NOT_APPLICABLE",
            "notification_outcome": "NOT_APPLICABLE",
            "notification_display_status": "NOT_CONFIRMED",
            "network_at_callback": "NOT_AVAILABLE",
            "device_interactive_at_callback": "NOT_AVAILABLE",
            "device_idle_at_callback": "NOT_AVAILABLE",
            "contamination_status": "UNKNOWN",
            "timing": {"status": "NOT_AVAILABLE"},
        }

    receipt = matching_id[0] if matching_id else None
    if receipt is not None:
        if sender.sender_status == "SEND_REJECTED":
            report_status = "EVIDENCE_CONFLICT"
            conflict_status = "REJECTED_SEND_HAS_MATCHED_CALLBACK"
        else:
            report_status = "RECEIVED"
            conflict_status = "NONE"
        if receipt.handler_duration_ms is None:
            processing_status = "PROCESSING_PENDING"
        elif receipt.handler_duration_ms > processing_threshold_ms:
            processing_status = "PROCESSING_SLOW"
        else:
            processing_status = "PROCESSING_WITHIN_THRESHOLD"
        cutoff = descriptor.recovery_cutoff_device_epoch_ms if descriptor else None
        if cutoff is None:
            contamination_status = "NOT_SPECIFIED"
        elif receipt.received_at_epoch_ms >= cutoff:
            contamination_status = "POST_RECOVERY_CONTAMINATED"
        else:
            contamination_status = "PRE_RECOVERY"
        result = {
            "schema": REPORT_SCHEMA,
            "report_status": report_status,
            "conflict_status": conflict_status,
            "test_id": sender.test_id,
            "sender_status": sender.sender_status,
            "callback_status": "CONFIRMED",
            "coverage_status": "MATCHED_EXACT_TEST_ID_AND_SENT_AT",
            "processing_status": processing_status,
            "processing_threshold_ms": processing_threshold_ms,
            "handler_duration_ms": receipt.handler_duration_ms,
            "process_started_at_elapsed_ms": receipt.process_started_at_elapsed_ms,
            "process_age_at_callback_ms": (
                None
                if receipt.process_started_at_elapsed_ms is None
                else receipt.received_at_elapsed_ms - receipt.process_started_at_elapsed_ms
            ),
            "process_start_not_observed_cause": receipt.process_start_not_observed_cause,
            "lifecycle_claim_limit": (
                "PROCESS_TIMING_ALONE_DOES_NOT_PROVE_EXCLUSIVE_FCM_WAKE_OR_"
                "PRE_SEND_PROCESS_ABSENCE"
            ),
            "notification_outcome": receipt.notification_outcome,
            "notification_display_status": "NOT_CONFIRMED",
            "network_at_callback": receipt.network,
            "device_interactive_at_callback": receipt.interactive,
            "device_idle_at_callback": receipt.idle,
            "message_priority_at_callback": receipt.priority,
            "contamination_status": contamination_status,
            "timing": _timing(receipt, phone_minus_host_offset_ms, clock_error_ms),
        }
        return result

    receipt_count = 0 if receipts is None else len(receipts)
    if receipts is None:
        report_status = "SEND_REJECTED" if sender.sender_status == "SEND_REJECTED" else "AWAITING_DEVICE_EVIDENCE"
        coverage_status = "NO_RECEIPT_EXPORT"
    elif descriptor is None:
        report_status = "SEND_REJECTED" if sender.sender_status == "SEND_REJECTED" else "UNKNOWN"
        coverage_status = "UNTRUSTED_OR_UNDESCRIBED_SNAPSHOT"
    elif observation_host_epoch_ms is None:
        report_status = "SEND_REJECTED" if sender.sender_status == "SEND_REJECTED" else "UNKNOWN"
        coverage_status = "OBSERVATION_TIME_REQUIRED"
    elif (
        not descriptor.window_start_host_epoch_ms <= sender.sent_at_epoch_ms <= descriptor.window_end_host_epoch_ms
        or observation_host_epoch_ms < descriptor.window_end_host_epoch_ms
    ):
        report_status = "SEND_REJECTED" if sender.sender_status == "SEND_REJECTED" else "UNKNOWN"
        coverage_status = "OUTSIDE_TRUSTED_WINDOW"
    else:
        report_status = "SEND_REJECTED" if sender.sender_status == "SEND_REJECTED" else "NOT_OBSERVED"
        coverage_status = "NOT_PRESENT_IN_TRUSTED_BOUNDED_SNAPSHOT"

    return {
        "schema": REPORT_SCHEMA,
        "report_status": report_status,
        "conflict_status": "NONE",
        "test_id": sender.test_id,
        "sender_status": sender.sender_status,
        "callback_status": "NOT_CONFIRMED",
        "coverage_status": coverage_status,
        "history_capacity": MAX_RECEIPTS,
        "receipt_count": receipt_count,
        "history_limit_reached": receipt_count == MAX_RECEIPTS,
        "processing_status": "NOT_APPLICABLE",
        "notification_outcome": "NOT_APPLICABLE",
        "notification_display_status": "NOT_CONFIRMED",
        "network_at_callback": "NOT_AVAILABLE",
        "device_interactive_at_callback": "NOT_AVAILABLE",
        "device_idle_at_callback": "NOT_AVAILABLE",
        "contamination_status": "UNKNOWN",
        "timing": {"status": "NOT_AVAILABLE"},
    }


def build_parser() -> argparse.ArgumentParser:
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument("--sender", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--export-descriptor", type=Path)
    parser.add_argument("--observation-host-epoch-ms")
    parser.add_argument("--processing-slow-threshold-ms", default="1000")
    parser.add_argument("--phone-minus-host-offset-ms")
    parser.add_argument("--clock-error-ms")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = build_parser().parse_args(argv)
        if args.sender is None:
            raise ReportInputError("SENDER_REQUIRED")
        if args.export_descriptor is not None and args.receipt is None:
            raise ReportInputError("DESCRIPTOR_REQUIRES_RECEIPT")
        observation = _parse_int_text(
            args.observation_host_epoch_ms, "INVALID_OBSERVATION_TIME"
        )
        threshold = _parse_int_text(
            args.processing_slow_threshold_ms, "INVALID_PROCESSING_THRESHOLD", allow_zero=True
        )
        assert threshold is not None
        if threshold > MAX_THRESHOLD_MS:
            raise ReportInputError("INVALID_PROCESSING_THRESHOLD")
        offset = _parse_float_text(
            args.phone_minus_host_offset_ms, "INVALID_CLOCK_CALIBRATION", nonnegative=False
        )
        clock_error = _parse_float_text(
            args.clock_error_ms, "INVALID_CLOCK_CALIBRATION", nonnegative=True
        )
        if (offset is None) != (clock_error is None):
            raise ReportInputError("INCOMPLETE_CLOCK_CALIBRATION")

        sender = parse_sender(args.sender)
        receipt_raw: bytes | None = None
        receipts: list[Receipt] | None = None
        if args.receipt is not None:
            receipt_raw, receipts = parse_receipts(args.receipt)
        descriptor = None
        if args.export_descriptor is not None:
            assert receipt_raw is not None and receipts is not None
            descriptor = parse_descriptor(args.export_descriptor, receipt_raw, len(receipts))
        report = build_report(
            sender,
            receipts=receipts,
            descriptor=descriptor,
            observation_host_epoch_ms=observation,
            processing_threshold_ms=threshold,
            phone_minus_host_offset_ms=offset,
            clock_error_ms=clock_error,
        )
        print(json.dumps(report, sort_keys=True, separators=(",", ":")))
        return 0 if report["report_status"] != "EVIDENCE_CONFLICT" else 1
    except ReportInputError as error:
        print(json.dumps({
            "schema": REPORT_SCHEMA,
            "report_status": "INVALID_INPUT",
            "error_code": error.code,
        }, sort_keys=True, separators=(",", ":")), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
