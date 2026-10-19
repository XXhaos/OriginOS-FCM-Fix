#!/usr/bin/env python3
"""Send one privacy-bounded FCM HTTP v1 probe to an owned app token.

The command is a dry run unless --send is present. Real sends use an OAuth2
bearer token supplied in a local file; this module does not discover or mint
credentials.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence

import private_host_files as private_files


API_ROOT = "https://fcm.googleapis.com/v1/projects"
REQUEST_TIMEOUT_SECONDS = 10.0
MAX_RESPONSE_BYTES = 16 * 1024
MAX_CREDENTIAL_BYTES = 16 * 1024
PROJECT_RE = re.compile(r"[a-z][a-z0-9-]{4,28}[a-z0-9]\Z")
TEST_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
MESSAGE_NAME_RE = re.compile(
    r"projects/[A-Za-z0-9_-]{1,128}/messages/[A-Za-z0-9%._~+:-]{1,512}\Z"
)
ERROR_STATUSES = frozenset({
    "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND",
    "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION",
    "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE",
    "DATA_LOSS", "UNAUTHENTICATED",
})


class ProbeError(Exception):
    """An expected error whose code is safe to print."""

    def __init__(self, code: str, exit_code: int = 2) -> None:
        super().__init__(code)
        self.code = code
        self.exit_code = exit_code


@dataclass(frozen=True)
class TransportResponse:
    http_status: int
    body: bytes


Transport = Callable[[str, Mapping[str, str], bytes, float, int], TransportResponse]
Clock = Callable[[], int]


class _RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Turn every redirect into a terminal response without a follow-up request."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


def validate_project(project: str) -> str:
    if not PROJECT_RE.fullmatch(project):
        raise ProbeError("INVALID_PROJECT")
    return project


def validate_test_id(test_id: str) -> str:
    if not TEST_ID_RE.fullmatch(test_id):
        raise ProbeError("INVALID_TEST_ID")
    return test_id


def endpoint_for(project: str) -> str:
    return f"{API_ROOT}/{validate_project(project)}/messages:send"


def _read_secret(path: Path, kind: str) -> str:
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAX_CREDENTIAL_BYTES + 1)
    except (OSError, ValueError) as exc:
        raise ProbeError(f"{kind}_FILE_UNREADABLE") from None
    if len(raw) > MAX_CREDENTIAL_BYTES:
        raise ProbeError(f"{kind}_FILE_TOO_LARGE")
    try:
        value = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise ProbeError(f"{kind}_FILE_INVALID") from None
    # A final line delimiter is file formatting, not part of the secret.
    value = value.rstrip("\r\n")
    if (
        not value
        or len(value) < 10
        or any(character.isspace() or not 33 <= ord(character) <= 126 for character in value)
    ):
        raise ProbeError(f"{kind}_INVALID")
    return value


def read_device_token(path: Path) -> str:
    value = _read_secret(path, "TOKEN")
    if len(value) < 20 or len(value) > 4096:
        raise ProbeError("TOKEN_INVALID")
    return value


def read_access_token(path: Path) -> str:
    value = _read_secret(path, "ACCESS_TOKEN")
    if len(value) > 8192:
        raise ProbeError("ACCESS_TOKEN_INVALID")
    return value


def make_message(device_token: str, test_id: str, sent_at_epoch_ms: int, priority: str) -> dict:
    validate_test_id(test_id)
    if not isinstance(sent_at_epoch_ms, int) or sent_at_epoch_ms <= 0:
        raise ProbeError("INVALID_SEND_TIME")
    if priority not in ("normal", "high"):
        raise ProbeError("INVALID_PRIORITY")
    return {
        "message": {
            "token": device_token,
            "data": {
                "guardian_probe_version": "1",
                "test_id": test_id,
                "sent_at_epoch_ms": str(sent_at_epoch_ms),
            },
            "android": {"priority": priority.upper(), "ttl": "60s"},
        }
    }


def _sanitized_message(message: Mapping[str, object]) -> dict:
    message_body = message["message"]  # Built only by make_message.
    assert isinstance(message_body, dict)
    return {
        "data": dict(message_body["data"]),
        "android": dict(message_body["android"]),
        "target": "OWNED_SINGLE_TOKEN_REDACTED",
    }


def default_output_path(test_id: str) -> Path:
    return Path("diagnostics-private/phase3") / f"fcm-probe-{test_id}.jsonl"


def _json_line(record: Mapping[str, object]) -> bytes:
    return (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def create_intent_artifact(path: Path, record: Mapping[str, object]) -> private_files.PrivateMarker:
    try:
        # Return only after the exclusive, private intent has been fully flushed.
        # Keep this exact handle through the request and outcome; do not reopen a path.
        return private_files.create_private_marker(path, _json_line(record))
    except FileExistsError:
        raise ProbeError("OUTPUT_ALREADY_EXISTS") from None
    except (private_files.PrivateFileError, OSError, TypeError, ValueError):
        raise ProbeError("INTENT_PERSIST_FAILED") from None


def append_outcome(marker: private_files.PrivateMarker, record: Mapping[str, object]) -> None:
    try:
        marker.append(_json_line(record))
    except (private_files.PrivateFileError, OSError, TypeError, ValueError):
        raise ProbeError("OUTCOME_PERSIST_FAILED", exit_code=1) from None


def urllib_transport(
    url: str,
    headers: Mapping[str, str],
    body: bytes,
    timeout: float,
    response_limit: int,
) -> TransportResponse:
    request = urllib.request.Request(url, data=body, headers=dict(headers), method="POST")
    opener = urllib.request.build_opener(_RejectRedirectHandler())
    try:
        with opener.open(request, timeout=timeout) as response:
            response_body = response.read(response_limit + 1)
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
        # A redirect is an uncertain terminal outcome. Do not follow it, and do
        # not retain its Location header or body even in transient memory here.
        if 300 <= status < 400:
            return TransportResponse(http_status=int(status), body=b"")
        response_body = error.read(response_limit + 1)
    except Exception:
        # Never propagate transport text: it could contain request headers or input.
        raise ProbeError("TRANSPORT_OUTCOME_UNCERTAIN", exit_code=1) from None
    if len(response_body) > response_limit:
        raise ProbeError("RESPONSE_TOO_LARGE_OUTCOME_UNCERTAIN", exit_code=1)
    return TransportResponse(http_status=int(status), body=response_body)


def _parse_object(body: bytes) -> dict:
    try:
        parsed = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _accepted_outcome(
    response: TransportResponse, after_ms: int, forbidden_values: Sequence[str]
) -> dict:
    parsed = _parse_object(response.body)
    name = parsed.get("name")
    outcome: dict[str, object] = {
        "record_type": "OUTCOME",
        "send_status": "SENT_ACCEPTED",
        "delivery_status": "UNKNOWN",
        "http_status": response.http_status,
        "host_after_response_epoch_ms": after_ms,
    }
    if (
        isinstance(name, str)
        and MESSAGE_NAME_RE.fullmatch(name)
        and not any(value and value in name for value in forbidden_values)
    ):
        outcome["message_name"] = name
    return outcome


def _failure_outcome(
    response: TransportResponse, after_ms: int, forbidden_values: Sequence[str]
) -> dict:
    parsed = _parse_object(response.body)
    error = parsed.get("error")
    status = None
    code = None
    if isinstance(error, dict):
        raw_status = error.get("status")
        raw_code = error.get("code")
        if (
            isinstance(raw_status, str)
            and raw_status in ERROR_STATUSES
            and not any(value and value in raw_status for value in forbidden_values)
        ):
            status = raw_status
        if isinstance(raw_code, int) and 100 <= raw_code <= 599:
            code = raw_code
    definitely_rejected = (
        400 <= response.http_status < 500
        and response.http_status not in (408, 409, 425, 429)
    )
    send_status = "SEND_REJECTED" if definitely_rejected else "SEND_OUTCOME_UNCERTAIN"
    outcome: dict[str, object] = {
        "record_type": "OUTCOME",
        "send_status": send_status,
        "delivery_status": "UNKNOWN",
        "http_status": response.http_status,
        "host_after_response_epoch_ms": after_ms,
        "retry_performed": False,
    }
    if status is not None:
        outcome["error_status"] = status
    if code is not None:
        outcome["error_code"] = code
    return outcome


def execute_send(
    *,
    project: str,
    token_file: Path,
    access_token_file: Path,
    test_id: str,
    priority: str,
    output: Path,
    transport: Transport = urllib_transport,
    clock: Clock = epoch_ms,
) -> dict:
    endpoint = endpoint_for(project)
    validate_test_id(test_id)
    device_token = read_device_token(token_file)
    access_token = read_access_token(access_token_file)
    before_ms = clock()
    message = make_message(device_token, test_id, before_ms, priority)
    intent = {
        "record_type": "INTENT",
        "send_status": "INTENT_RECORDED",
        "delivery_status": "UNKNOWN",
        "endpoint": endpoint,
        "host_before_request_epoch_ms": before_ms,
        "message": _sanitized_message(message),
        "single_attempt": True,
    }
    encoded = json.dumps(message, separators=(",", ":")).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json; charset=utf-8",
    }
    marker = create_intent_artifact(output, intent)
    try:
        try:
            response = transport(
                endpoint, headers, encoded, REQUEST_TIMEOUT_SECONDS, MAX_RESPONSE_BYTES
            )
        except Exception:
            after_ms = clock()
            outcome = {
                "record_type": "OUTCOME",
                "send_status": "SEND_OUTCOME_UNCERTAIN",
                "delivery_status": "UNKNOWN",
                "host_after_response_epoch_ms": after_ms,
                "retry_performed": False,
                "error_status": "TRANSPORT_ERROR",
            }
            try:
                append_outcome(marker, outcome)
            except ProbeError:
                raise ProbeError(
                    "SEND_OUTCOME_UNCERTAIN_AND_OUTCOME_PERSIST_FAILED_NO_RETRY", exit_code=1
                ) from None
            raise ProbeError("SEND_OUTCOME_UNCERTAIN_NO_RETRY", exit_code=1) from None

        after_ms = clock()
        if 200 <= response.http_status < 300:
            outcome = _accepted_outcome(response, after_ms, (device_token, access_token))
        else:
            outcome = _failure_outcome(response, after_ms, (device_token, access_token))
        try:
            append_outcome(marker, outcome)
        except ProbeError:
            if outcome["send_status"] == "SENT_ACCEPTED":
                code = "SEND_ACCEPTED_BUT_OUTCOME_PERSIST_FAILED"
            else:
                code = "SEND_OUTCOME_UNCERTAIN_AND_OUTCOME_PERSIST_FAILED_NO_RETRY"
            raise ProbeError(code, exit_code=1) from None
        return outcome
    finally:
        try:
            marker.close()
        except (private_files.PrivateFileError, OSError, TypeError, ValueError):
            raise ProbeError("OUTPUT_CLOSE_FAILED_NO_RETRY", exit_code=1) from None


def dry_run(*, project: str, test_id: str, priority: str, clock: Clock = epoch_ms) -> dict:
    endpoint = endpoint_for(project)
    message = make_message("<redacted-device-token>", test_id, clock(), priority)
    return {
        "mode": "DRY_RUN",
        "network_performed": False,
        "endpoint": endpoint,
        "message": _sanitized_message(message),
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="Firebase project ID")
    parser.add_argument("--token-file", type=Path, help="file containing one owned FCM token")
    parser.add_argument(
        "--access-token-file", type=Path, help="file containing an OAuth2 bearer token"
    )
    parser.add_argument("--test-id", help="probe correlation ID; generated when omitted")
    parser.add_argument("--priority", choices=("normal", "high"), default="normal")
    parser.add_argument("--output", type=Path, help="private JSONL audit artifact")
    parser.add_argument("--send", action="store_true", help="perform exactly one real HTTP request")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    test_id = args.test_id or f"probe_{uuid.uuid4().hex}"
    try:
        validate_project(args.project)
        validate_test_id(test_id)
        if not args.send:
            preview = dry_run(
                project=args.project, test_id=test_id, priority=args.priority
            )
            print(json.dumps(preview, sort_keys=True))
            return 0
        if args.token_file is None or args.access_token_file is None:
            raise ProbeError("SEND_REQUIRES_TOKEN_AND_ACCESS_TOKEN_FILES")
        output = args.output or default_output_path(test_id)
        outcome = execute_send(
            project=args.project,
            token_file=args.token_file,
            access_token_file=args.access_token_file,
            test_id=test_id,
            priority=args.priority,
            output=output,
        )
        # This summary contains no token, auth value, response body, or credential path.
        print(json.dumps(outcome, sort_keys=True))
        return 0 if outcome["send_status"] == "SENT_ACCEPTED" else 1
    except ProbeError as error:
        print(json.dumps({"status": "ERROR", "error_code": error.code}), file=sys.stderr)
        return error.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
