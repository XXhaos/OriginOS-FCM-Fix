#!/usr/bin/env python3
"""Make at most one fixed FCM Toolbox request for an owned device token."""

from __future__ import annotations

import argparse
import json
import os
import re
import socket
import stat
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, Mapping, Sequence


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
    try:
        value = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise SenderError("ATTEMPT_MARKER_EXISTS_UNREADABLE", 1) from None
    allowed = {"status", "http", "messageId", "start", "end", "testId", "tokenDelete"}
    return {key: value[key] for key in allowed if key in value} | {"existingAttempt": True}


def _write_marker_exclusive(path: Path, record: Mapping[str, object]) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SenderError("ATTEMPT_ALREADY_EXISTS") from None
    try:
        os.write(fd, json.dumps(record, separators=(",", ":")).encode())
        os.fsync(fd)
    finally:
        os.close(fd)
    parent_fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def _replace_marker(path: Path, record: Mapping[str, object]) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_TRUNC)
    try:
        os.write(fd, json.dumps(record, separators=(",", ":")).encode())
        os.fsync(fd)
    finally:
        os.close(fd)


def _open_token(token_file: Path, run_dir: Path) -> tuple[int, str, os.stat_result]:
    if token_file.parent.resolve() != run_dir.resolve():
        raise SenderError("TOKEN_FILE_OUTSIDE_RUN_DIR")
    try:
        before = token_file.lstat()
        if stat.S_ISLNK(before.st_mode) or not stat.S_ISREG(before.st_mode):
            raise SenderError("TOKEN_FILE_UNSAFE")
        if stat.S_IMODE(before.st_mode) != 0o600:
            raise SenderError("TOKEN_FILE_MODE_NOT_0600")
        fd = os.open(token_file, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        current = os.fstat(fd)
        if (before.st_dev, before.st_ino) != (current.st_dev, current.st_ino):
            os.close(fd)
            raise SenderError("TOKEN_FILE_CHANGED")
        raw = os.read(fd, MAX_TOKEN_BYTES + 1)
    except SenderError:
        raise
    except OSError:
        raise SenderError("TOKEN_FILE_UNREADABLE") from None
    if len(raw) > MAX_TOKEN_BYTES:
        os.close(fd)
        raise SenderError("TOKEN_INVALID")
    try:
        token = raw.decode().rstrip("\r\n")
    except UnicodeDecodeError:
        os.close(fd)
        raise SenderError("TOKEN_INVALID") from None
    if not TOKEN_RE.fullmatch(token):
        os.close(fd)
        raise SenderError("TOKEN_INVALID")
    return fd, token, current


def _consume_token(path: Path, fd: int, identity: os.stat_result) -> str:
    try:
        current = path.lstat()
        if (current.st_dev, current.st_ino) != (identity.st_dev, identity.st_ino):
            return "IDENTITY_GUARD_REFUSED"
        path.unlink()
        return "DELETED"
    except OSError:
        return "DELETE_FAILED"
    finally:
        os.close(fd)


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
    fd, token, identity = _open_token(token_file, run_dir)
    start = clock()
    pending = {"status": "ATTEMPTING", "http": None, "messageId": None,
               "start": start, "end": None, "testId": test_id}
    try:
        _write_marker_exclusive(marker, pending)
    except SenderError as error:
        os.close(fd)
        if error.code == "ATTEMPT_ALREADY_EXISTS":
            return _safe_existing(marker)
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
        record["end"] = clock()
        record["tokenDelete"] = _consume_token(token_file, fd, identity)
        _replace_marker(marker, record)
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
