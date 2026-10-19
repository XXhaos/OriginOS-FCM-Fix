#!/usr/bin/env python3
"""Make at most one fixed FCM Toolbox request for an owned device token."""

from __future__ import annotations

import argparse
import json
import re
import socket
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, Mapping, Sequence

from private_host_files import PrivateFileError, create_private_marker, open_private_token


ENDPOINT = "https://us-central1-fir-cloudmessaging-4e2cd.cloudfunctions.net/send"
TIMEOUT_SECONDS = 30.0
MAX_RESPONSE_BYTES = 8 * 1024
MAX_TOKEN_BYTES = 4096
TEST_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")
TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{10,}:APA91[A-Za-z0-9_-]{20,}\Z")
MESSAGE_ID_RE = re.compile(r"projects/fir-cloudmessaging-4e2cd/messages/[A-Za-z0-9%._~+:-]{1,512}\Z")


class SenderError(Exception):
    def __init__(self, code: str, exit_code: int = 2) -> None:
        super().__init__(code)
        self.code = code
        self.exit_code = exit_code


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def epoch_ms() -> int:
    return time.time_ns() // 1_000_000


def validate_test_id(value: str) -> str:
    if not TEST_ID_RE.fullmatch(value):
        raise SenderError("INVALID_TEST_ID")
    return value


def make_envelope(token: str, test_id: str) -> dict:
    validate_test_id(test_id)
    return {"data": {"to": token, "ttl": 60, "priority": "high",
                     "data": {"ping": {}, "testId": test_id}}}


def _marker_path(run_dir: Path, test_id: str) -> Path:
    return run_dir / f"toolbox-{validate_test_id(test_id)}.json"


def _safe_existing(marker: Path) -> dict:
    file = None
    try:
        file = open_private_token(marker, 16 * 1024)
        value = json.loads(file.raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError()
    except (OSError, PrivateFileError, UnicodeError, ValueError):
        raise SenderError("ATTEMPT_MARKER_EXISTS_UNREADABLE", 1) from None
    finally:
        if file is not None:
            file.close()
    result = {"existingAttempt": True, "status": "UNKNOWN"}
    if value.get("status") in ("ATTEMPTING", "ACCEPTED", "REJECTED", "UNKNOWN"):
        result["status"] = value["status"]
    for key in ("http", "start", "end"):
        item = value.get(key)
        if item is None or (type(item) is int and 0 <= item <= 9_223_372_036_854_775_807):
            result[key] = item
    message_id = value.get("messageId")
    result["messageId"] = message_id if isinstance(message_id, str) and MESSAGE_ID_RE.fullmatch(message_id) else None
    if isinstance(value.get("testId"), str) and TEST_ID_RE.fullmatch(value["testId"]):
        result["testId"] = value["testId"]
    if value.get("tokenDelete") in ("DELETED", "IDENTITY_GUARD_REFUSED", "DELETE_FAILED"):
        result["tokenDelete"] = value["tokenDelete"]
    return result


def _write_marker_exclusive(path: Path, record: Mapping[str, object]):
    try:
        return create_private_marker(path, json.dumps(record, separators=(",", ":")).encode())
    except FileExistsError:
        raise SenderError("ATTEMPT_ALREADY_EXISTS") from None
    except (OSError, PrivateFileError):
        raise SenderError("ATTEMPT_PERSIST_FAILED") from None


def _replace_marker(marker, record: Mapping[str, object]) -> None:
    try:
        marker.replace(json.dumps(record, separators=(",", ":")).encode())
    except (OSError, PrivateFileError):
        raise SenderError("OUTCOME_PERSIST_FAILED", 1) from None


def _open_token(token_file: Path, run_dir: Path):
    if token_file.parent.resolve() != run_dir.resolve():
        raise SenderError("TOKEN_FILE_OUTSIDE_RUN_DIR")
    try:
        file = open_private_token(token_file, MAX_TOKEN_BYTES)
        raw = file.raw
    except PrivateFileError as error:
        code = "TOKEN_FILE_MODE_NOT_0600" if error.code == "TOKEN_FILE_MODE_NOT_0600" else "TOKEN_FILE_UNSAFE"
        if error.code == "PRIVATE_FILE_TOO_LARGE":
            code = "TOKEN_INVALID"
        raise SenderError(code) from None
    except OSError:
        raise SenderError("TOKEN_FILE_UNREADABLE") from None
    try:
        token = raw.decode().rstrip("\r\n")
    except UnicodeDecodeError:
        file.close()
        raise SenderError("TOKEN_INVALID") from None
    if not TOKEN_RE.fullmatch(token):
        file.close()
        raise SenderError("TOKEN_INVALID")
    return file, token


def _consume_token(file) -> str:
    return {"CONSUMED": "DELETED", "TOKEN_PATH_CHANGED": "IDENTITY_GUARD_REFUSED",
            "TOKEN_DELETE_FAILED": "DELETE_FAILED"}[file.consume()]


def http_post(body: bytes) -> tuple[int, bytes]:
    request = urllib.request.Request(ENDPOINT, data=body,
                                     headers={"Content-Type": "application/json"}, method="POST")
    opener = urllib.request.build_opener(_NoRedirect())
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
            status_code = int(response.status)
    except urllib.error.HTTPError as error:
        status_code = int(error.code)
        payload = b"" if 300 <= status_code < 400 else error.read(MAX_RESPONSE_BYTES + 1)
    if len(payload) > MAX_RESPONSE_BYTES:
        raise SenderError("TRANSPORT_UNKNOWN", 1)
    return status_code, payload


def send_once(*, token_file: Path, run_dir: Path, test_id: str,
              transport: Callable[[bytes], tuple[int, bytes]] = http_post,
              clock: Callable[[], int] = epoch_ms) -> dict:
    marker = _marker_path(run_dir, test_id)
    if marker.exists():
        return _safe_existing(marker)
    token_handle, token = _open_token(token_file, run_dir)
    try:
        start = clock()
        pending = {"status": "ATTEMPTING", "http": None, "messageId": None,
                   "start": start, "end": None, "testId": test_id}
        marker_handle = _write_marker_exclusive(marker, pending)
    except SenderError as error:
        token_handle.close()
        if error.code == "ATTEMPT_ALREADY_EXISTS":
            return _safe_existing(marker)
        raise
    except BaseException:
        token_handle.close()
        raise
    record = dict(pending)
    try:
        status_code, response = transport(json.dumps(make_envelope(token, test_id),
                                                     separators=(",", ":")).encode())
        record["http"] = status_code
        try:
            decoded = json.loads(response.decode())
        except (UnicodeError, json.JSONDecodeError):
            decoded = {}
        result = decoded.get("result") if isinstance(decoded, dict) else None
        if (status_code == 200 and isinstance(decoded, dict) and "error" not in decoded and isinstance(result, str)
                and MESSAGE_ID_RE.fullmatch(result) and token not in result):
            record.update(status="ACCEPTED", messageId=result)
        else:
            error = decoded.get("error") if isinstance(decoded, dict) else None
            explicit_rejection = isinstance(error, dict) and error.get("status") in {
                "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND"
            }
            client_rejection = 400 <= status_code < 500 and status_code not in (408, 409, 425, 429)
            definitely_rejected = (status_code == 200 and explicit_rejection) or client_rejection
            if isinstance(decoded, dict) and "result" in decoded and "error" in decoded:
                definitely_rejected = False
            record["status"] = "REJECTED" if definitely_rejected else "UNKNOWN"
    except (TimeoutError, socket.timeout, urllib.error.URLError, OSError, SenderError):
        record["status"] = "UNKNOWN"
    finally:
        try:
            record["end"] = clock()
        finally:
            try:
                record["tokenDelete"] = _consume_token(token_handle)
                _replace_marker(marker_handle, record)
            finally:
                token_handle.close()
                marker_handle.close()
    return record


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--token-file", type=Path)
    parser.add_argument("--run-dir", type=Path, default=Path("diagnostics-private/phase4/toolbox"))
    parser.add_argument("--test-id", required=True)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)
    try:
        validate_test_id(args.test_id)
        if not args.execute:
            output = {"status": "DRY_RUN", "endpoint": ENDPOINT, "testId": args.test_id,
                      "ttl": 60, "priority": "high", "network": False}
        elif args.token_file is None:
            raise SenderError("TOKEN_FILE_REQUIRED")
        else:
            output = send_once(token_file=args.token_file, run_dir=args.run_dir, test_id=args.test_id)
        print(json.dumps(output, sort_keys=True, separators=(",", ":")))
        return 0 if output.get("status") in ("DRY_RUN", "ACCEPTED") or output.get("existingAttempt") else 1
    except SenderError as error:
        print(json.dumps({"error": error.code}, separators=(",", ":")))
        return error.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
