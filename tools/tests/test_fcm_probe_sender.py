import contextlib
import http.server
import io
import json
import os
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import fcm_probe_sender as sender


DEVICE_TOKEN = "owned-token-value-with-enough-length:abc_123"
ACCESS_TOKEN = "oauth-access-token-value"


class FcmProbeSenderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "private-run"
        sender.private_files.ensure_private_directory(self.root)
        self.token_file = self.root / "device-token.txt"
        self.auth_file = self.root / "access-token.txt"
        self.token_file.write_text(DEVICE_TOKEN + "\n", encoding="utf-8")
        self.auth_file.write_text(ACCESS_TOKEN + "\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_dry_run_does_not_read_credentials_or_call_transport(self):
        missing_token = self.root / "missing-token"
        missing_auth = self.root / "missing-auth"
        stdout = io.StringIO()
        real_dry_run = sender.dry_run

        def fixed_clock_dry_run(**kwargs):
            return real_dry_run(**kwargs, clock=lambda: 9000)

        with contextlib.redirect_stdout(stdout), mock.patch.object(
            sender, "dry_run", side_effect=fixed_clock_dry_run
        ):
            result = sender.main([
                "--project", "example-project",
                "--token-file", str(missing_token),
                "--access-token-file", str(missing_auth),
                "--test-id", "dry_1",
            ])
        self.assertEqual(0, result)
        output = json.loads(stdout.getvalue())
        self.assertEqual("DRY_RUN", output["mode"])
        self.assertFalse(output["network_performed"])
        self.assertEqual("9000", output["message"]["data"]["sent_at_epoch_ms"])
        self.assertNotIn(DEVICE_TOKEN, stdout.getvalue())
        self.assertNotIn(ACCESS_TOKEN, stdout.getvalue())

    def test_exact_contract_uses_clock_value_and_normal_priority(self):
        message = sender.make_message(DEVICE_TOKEN, "case_123", 1_700_000_000_123, "normal")
        self.assertEqual(
            {
                "guardian_probe_version": "1",
                "test_id": "case_123",
                "sent_at_epoch_ms": "1700000000123",
            },
            message["message"]["data"],
        )
        self.assertEqual({"priority": "NORMAL", "ttl": "60s"}, message["message"]["android"])
        self.assertNotIn("notification", message["message"])

    def test_validation_rejects_injection_and_whitespace(self):
        for project in ("https://evil.invalid", "valid-project/messages:send", "UPPER-project"):
            with self.subTest(project=project), self.assertRaises(sender.ProbeError):
                sender.validate_project(project)
        for test_id in ("space id", "../../escape", "x" * 81):
            with self.subTest(test_id=test_id), self.assertRaises(sender.ProbeError):
                sender.validate_test_id(test_id)
        self.token_file.write_text("token with whitespace and enough length", encoding="utf-8")
        with self.assertRaises(sender.ProbeError):
            sender.read_device_token(self.token_file)

    def test_server_echo_and_exception_never_reach_artifact_or_error(self):
        output = self.root / "echo.jsonl"

        def echo_transport(*_args):
            body = json.dumps({
                "error": {
                    "code": 400,
                    "status": "INVALID_ARGUMENT",
                    "message": DEVICE_TOKEN + ACCESS_TOKEN,
                }
            }).encode()
            return sender.TransportResponse(400, body)

        outcome = sender.execute_send(
            project="example-project",
            token_file=self.token_file,
            access_token_file=self.auth_file,
            test_id="echo_1",
            priority="normal",
            output=output,
            transport=echo_transport,
            clock=iter((1000, 1001)).__next__,
        )
        artifact = output.read_text(encoding="utf-8")
        self.assertEqual("SEND_REJECTED", outcome["send_status"])
        self.assertNotIn(DEVICE_TOKEN, artifact)
        self.assertNotIn(ACCESS_TOKEN, artifact)
        self.assertNotIn("message", outcome)

        uncertain_output = self.root / "uncertain.jsonl"

        def secret_exception(*_args):
            raise RuntimeError(DEVICE_TOKEN + ACCESS_TOKEN)

        with self.assertRaisesRegex(sender.ProbeError, "SEND_OUTCOME_UNCERTAIN_NO_RETRY"):
            sender.execute_send(
                project="example-project",
                token_file=self.token_file,
                access_token_file=self.auth_file,
                test_id="echo_2",
                priority="normal",
                output=uncertain_output,
                transport=secret_exception,
                clock=iter((2000, 2001)).__next__,
            )
        uncertain = uncertain_output.read_text(encoding="utf-8")
        self.assertNotIn(DEVICE_TOKEN, uncertain)
        self.assertNotIn(ACCESS_TOKEN, uncertain)

        accepted_echo_output = self.root / "accepted-echo.jsonl"
        accepted_echo = sender.execute_send(
            project="example-project",
            token_file=self.token_file,
            access_token_file=self.auth_file,
            test_id="echo_3",
            priority="normal",
            output=accepted_echo_output,
            transport=lambda *_: sender.TransportResponse(
                200,
                json.dumps({
                    "name": "projects/example-project/messages/" + DEVICE_TOKEN
                }).encode(),
            ),
            clock=iter((2100, 2101)).__next__,
        )
        self.assertEqual("SENT_ACCEPTED", accepted_echo["send_status"])
        self.assertNotIn("message_name", accepted_echo)
        self.assertNotIn(DEVICE_TOKEN, accepted_echo_output.read_text(encoding="utf-8"))

    def test_single_attempt_and_intent_is_durable_before_transport(self):
        output = self.root / "ordered.jsonl"
        attempts = []
        markers = []
        real_create = sender.create_intent_artifact

        def retain_marker(path, record):
            marker = real_create(path, record)
            markers.append(marker)
            return marker

        def inspect_transport(_url, _headers, body, _timeout, _limit):
            attempts.append(1)
            self.assertTrue(output.exists())
            marker = markers[0]
            self.assertIsNotNone(marker.handle)
            marker._validate()  # Actual descriptor/NTFS protected ACL check, not Windows mode bits.
            if os.name == "posix":
                self.assertEqual(0o600, os.stat(output).st_mode & 0o777)
            records = output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(records))
            self.assertEqual("INTENT", json.loads(records[0])["record_type"])
            request = json.loads(body)
            self.assertEqual("3000", request["message"]["data"]["sent_at_epoch_ms"])
            return sender.TransportResponse(
                200, b'{"name":"projects/example-project/messages/abc123"}'
            )

        with mock.patch.object(sender, "create_intent_artifact", side_effect=retain_marker):
            outcome = sender.execute_send(
                project="example-project",
                token_file=self.token_file,
                access_token_file=self.auth_file,
                test_id="ordered_1",
                priority="high",
                output=output,
                transport=inspect_transport,
                clock=iter((3000, 3005)).__next__,
            )
        self.assertEqual([1], attempts)
        self.assertEqual("SENT_ACCEPTED", outcome["send_status"])
        self.assertEqual("UNKNOWN", outcome["delivery_status"])
        records = [json.loads(line) for line in output.read_text(encoding="utf-8").splitlines()]
        self.assertEqual("HIGH", records[0]["message"]["android"]["priority"])
        self.assertEqual(3000, records[0]["host_before_request_epoch_ms"])
        self.assertEqual(3005, records[1]["host_after_response_epoch_ms"])
        self.assertIsNone(markers[0].handle)
        self.assertNotIn(b"\r", output.read_bytes())

    def test_partial_intent_write_and_flush_failure_block_transport_and_keep_exclusive_marker(self):
        for failure in ("partial_write", "flush"):
            with self.subTest(failure=failure):
                output = self.root / (failure + ".jsonl")
                transport = mock.Mock()
                if failure == "partial_write":
                    def partial(marker, data):
                        marker.append(data[:5])
                        raise sender.private_files.PrivateFileError("PRIVATE_FILE_WRITE_FAILED")
                    fault = mock.patch.object(sender.private_files.PrivateMarker, "replace", partial)
                elif os.name == "nt":
                    fault = mock.patch.object(sender.private_files._windows(), "flush", return_value=False)
                else:
                    fault = mock.patch.object(sender.private_files.os, "fsync", side_effect=OSError("private path"))
                with fault, self.assertRaisesRegex(sender.ProbeError, "^INTENT_PERSIST_FAILED$"):
                    sender.execute_send(
                        project="example-project", token_file=self.token_file,
                        access_token_file=self.auth_file, test_id="persist_failure",
                        priority="normal", output=output, transport=transport, clock=lambda: 4000,
                    )
                transport.assert_not_called()
                self.assertTrue(output.exists())
                before = output.read_bytes()
                self.assertTrue(before)
                self.assertNotIn(DEVICE_TOKEN.encode(), before)
                self.assertNotIn(ACCESS_TOKEN.encode(), before)
                with self.assertRaisesRegex(sender.ProbeError, "^OUTPUT_ALREADY_EXISTS$"):
                    sender.execute_send(
                        project="example-project", token_file=self.token_file,
                        access_token_file=self.auth_file, test_id="persist_failure",
                        priority="normal", output=output, transport=transport, clock=lambda: 4000,
                    )
                transport.assert_not_called()
                self.assertEqual(before, output.read_bytes())

    def test_outcome_failure_closes_same_marker_and_never_retries(self):
        for transport_error in (False, True):
            with self.subTest(transport_error=transport_error):
                output = self.root / ("outcome-%s.jsonl" % transport_error)
                markers = []
                real_create = sender.create_intent_artifact

                def retain_marker(path, record):
                    marker = real_create(path, record)
                    markers.append(marker)
                    return marker

                transport = mock.Mock(side_effect=RuntimeError(ACCESS_TOKEN)) if transport_error else mock.Mock(
                    return_value=sender.TransportResponse(200, b"{}"))
                expected = "SEND_OUTCOME_UNCERTAIN_AND_OUTCOME_PERSIST_FAILED_NO_RETRY" if transport_error else \
                    "SEND_ACCEPTED_BUT_OUTCOME_PERSIST_FAILED"
                with mock.patch.object(sender, "create_intent_artifact", side_effect=retain_marker), \
                    mock.patch.object(sender.private_files.PrivateMarker, "append", side_effect=
                        sender.private_files.PrivateFileError("PRIVATE_FILE_FLUSH_FAILED")), \
                    self.assertRaisesRegex(sender.ProbeError, "^" + expected + "$"):
                    sender.execute_send(
                        project="example-project", token_file=self.token_file,
                        access_token_file=self.auth_file, test_id="outcome_failure",
                        priority="normal", output=output, transport=transport,
                        clock=iter((5000, 5001)).__next__,
                    )
                transport.assert_called_once()
                self.assertIsNone(markers[0].handle)
                records = output.read_bytes().splitlines()
                self.assertEqual(1, len(records))
                self.assertEqual("INTENT", json.loads(records[0])["record_type"])

    def test_output_replacement_cannot_redirect_outcome_to_an_unverified_file(self):
        output = self.root / "pinned.jsonl"
        moved = self.root / "original.jsonl"
        replacement = b"unrelated-user-data"
        attempts = []
        swapped = []

        def transport(*_args):
            attempts.append(1)
            try:
                output.rename(moved)
            except PermissionError:
                self.assertEqual("nt", os.name)  # Held NTFS handle denies rename/delete sharing.
            else:
                output.write_bytes(replacement)
                swapped.append(True)
            return sender.TransportResponse(200, b"{}")

        try:
            result = sender.execute_send(
                project="example-project", token_file=self.token_file,
                access_token_file=self.auth_file, test_id="pinned_output",
                priority="normal", output=output, transport=transport,
                clock=iter((5000, 5001)).__next__,
            )
        except sender.ProbeError as error:
            self.assertTrue(swapped)
            self.assertEqual("SEND_ACCEPTED_BUT_OUTCOME_PERSIST_FAILED", error.code)
        else:
            self.assertFalse(swapped)
            self.assertEqual("SENT_ACCEPTED", result["send_status"])
        self.assertEqual([1], attempts)
        if swapped:
            self.assertEqual(replacement, output.read_bytes())
            self.assertEqual(1, len(moved.read_bytes().splitlines()))

    def test_error_status_cannot_echo_uppercase_secrets(self):
        for secret in ("PRIVATE_BEARER_SECRET", "INVALID_ARGUMENT"):
            with self.subTest(secret=secret):
                self.auth_file.write_text(secret, encoding="utf-8")
                output = self.root / (secret + ".jsonl")
                result = sender.execute_send(
                    project="example-project", token_file=self.token_file,
                    access_token_file=self.auth_file, test_id="redaction",
                    priority="normal", output=output,
                    transport=lambda *_: sender.TransportResponse(
                        400, json.dumps({"error": {"status": secret, "code": 400}}).encode()
                    ),
                    clock=iter((6000, 6001)).__next__,
                )
                self.assertNotIn("error_status", result)
                self.assertNotIn(secret, output.read_text())

    def test_secret_read_is_bounded_before_validation(self):
        stream = mock.MagicMock()
        stream.__enter__.return_value = stream
        stream.read.return_value = b"x" * (sender.MAX_CREDENTIAL_BYTES + 1)
        with mock.patch.object(Path, "open", return_value=stream):
            with self.assertRaisesRegex(sender.ProbeError, "TOKEN_FILE_TOO_LARGE"):
                sender.read_device_token(self.token_file)
        stream.read.assert_called_once_with(sender.MAX_CREDENTIAL_BYTES + 1)

    def test_existing_output_refuses_before_transport(self):
        output = self.root / "exists.jsonl"
        output.write_text("user data", encoding="utf-8")
        called = False

        def transport(*_args):
            nonlocal called
            called = True
            return sender.TransportResponse(200, b"{}")

        with self.assertRaisesRegex(sender.ProbeError, "OUTPUT_ALREADY_EXISTS"):
            sender.execute_send(
                project="example-project",
                token_file=self.token_file,
                access_token_file=self.auth_file,
                test_id="existing_1",
                priority="normal",
                output=output,
                transport=transport,
                clock=lambda: 4000,
            )
        self.assertFalse(called)
        self.assertEqual("user data", output.read_text(encoding="utf-8"))

    def test_http_acceptance_is_explicitly_not_delivery(self):
        output = self.root / "accepted.jsonl"

        outcome = sender.execute_send(
            project="example-project",
            token_file=self.token_file,
            access_token_file=self.auth_file,
            test_id="accepted_1",
            priority="normal",
            output=output,
            transport=lambda *_: sender.TransportResponse(
                200, b'{"name":"projects/example-project/messages/message-1"}'
            ),
            clock=iter((5000, 5002)).__next__,
        )
        self.assertEqual("SENT_ACCEPTED", outcome["send_status"])
        self.assertEqual("UNKNOWN", outcome["delivery_status"])
        self.assertNotIn("DELIVERED", output.read_text(encoding="utf-8"))

    def test_actual_urllib_transport_never_follows_redirect_or_forwards_auth(self):
        requests = []
        secret = "redirect-bearer-secret"

        class RedirectServer(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                requests.append((self.path, self.headers.get("Authorization")))
                if self.path.startswith("/initial-"):
                    redirect_status = int(self.path.rsplit("-", 1)[1])
                    self.send_response(redirect_status)
                    self.send_header(
                        "Location",
                        f"http://127.0.0.1:{self.server.server_port}/capture?secret={secret}",
                    )
                    self.end_headers()
                    self.wfile.write(("echo:" + secret).encode())
                else:
                    self.send_response(204)
                    self.end_headers()

            def do_GET(self):
                requests.append((self.path, self.headers.get("Authorization")))
                self.send_response(204)
                self.end_headers()

            def log_message(self, _format, *_args):
                pass

        server = http.server.HTTPServer(("127.0.0.1", 0), RedirectServer)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            responses = []
            real_build_opener = urllib.request.build_opener

            def direct_opener(*handlers):
                self.assertTrue(any(isinstance(handler, sender._RejectRedirectHandler) for handler in handlers))
                return real_build_opener(urllib.request.ProxyHandler({}), *handlers)

            # Test isolation only: real local HTTP and the production redirect handler remain.
            with mock.patch.object(sender.urllib.request, "build_opener", side_effect=direct_opener):
                for redirect_status in (301, 302, 303, 307, 308):
                    responses.append(sender.urllib_transport(
                        f"http://127.0.0.1:{server.server_port}/initial-{redirect_status}",
                        {
                            "Authorization": f"Bearer {secret}",
                            "Content-Type": "application/json",
                        },
                        b"{}",
                        2.0,
                        sender.MAX_RESPONSE_BYTES,
                    ))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2.0)

        self.assertEqual(
            [
                (f"/initial-{status}", f"Bearer {secret}")
                for status in (301, 302, 303, 307, 308)
            ],
            requests,
        )
        self.assertEqual([301, 302, 303, 307, 308], [item.http_status for item in responses])
        self.assertTrue(all(item.body == b"" for item in responses))


if __name__ == "__main__":
    unittest.main()
