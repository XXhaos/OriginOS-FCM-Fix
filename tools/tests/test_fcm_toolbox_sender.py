import json
import os
import socket
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fcm_toolbox_sender as sender
import private_host_files as private
from test_private_host_files import set_windows_test_acl

TOKEN = "owned-device-token:APA91synthetic_abcdefghijklmnopqrstuvwxyz"
MESSAGE = "projects/fir-cloudmessaging-4e2cd/messages/abc123"


class ToolboxSenderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp.name) / "diagnostics-private"
        private.ensure_private_directory(self.run_dir)
        self.token_file = self.run_dir / "owned-token.txt"
        private.write_exclusive(self.token_file, (TOKEN + "\n").encode())

    def write_token(self, value):
        if self.token_file.exists():
            private.replace_existing(self.token_file, value.encode())
        else:
            private.write_exclusive(self.token_file, value.encode())

    def tearDown(self):
        self.temp.cleanup()

    def test_exact_fixed_envelope_is_data_only(self):
        self.assertEqual({"data": {"to": TOKEN, "ttl": 60, "priority": "high",
                                   "data": {"ping": {}, "testId": "trial_1"}}},
                         sender.make_envelope(TOKEN, "trial_1"))
        self.assertNotIn("notification", json.dumps(sender.make_envelope(TOKEN, "trial_1")))

    def test_success_persists_sanitized_result_and_consumes_token(self):
        bodies = []
        result = sender.send_once(
            token_file=self.token_file, run_dir=self.run_dir, test_id="ok_1",
            transport=lambda body: (bodies.append(json.loads(body)), (200, json.dumps({"result": MESSAGE}).encode()))[1],
            clock=iter((100, 101)).__next__)
        self.assertEqual("ACCEPTED", result["status"])
        self.assertEqual(MESSAGE, result["messageId"])
        self.assertEqual(sender.make_envelope(TOKEN, "ok_1"), bodies[0])
        self.assertFalse(self.token_file.exists())
        saved = (self.run_dir / "toolbox-ok_1.json").read_text()
        self.assertNotIn(TOKEN, saved)
        marker = private.open_private_token(self.run_dir / "toolbox-ok_1.json", 16 * 1024)
        marker.close()

    def test_http_200_callable_error_is_not_success_and_body_is_redacted(self):
        result = sender.send_once(
            token_file=self.token_file, run_dir=self.run_dir, test_id="error_1",
            transport=lambda _: (200, json.dumps({"error": {"status": "INVALID_ARGUMENT", "message": TOKEN}}).encode()),
            clock=iter((200, 201)).__next__)
        self.assertEqual("REJECTED", result["status"])
        self.assertIsNone(result["messageId"])
        self.assertNotIn(TOKEN, (self.run_dir / "toolbox-error_1.json").read_text())

    def test_timeout_is_unknown_and_never_retried(self):
        calls = []
        def timeout(_body):
            calls.append(1)
            raise socket.timeout("secret " + TOKEN)
        result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir,
                                  test_id="timeout_1", transport=timeout,
                                  clock=iter((300, 301)).__next__)
        self.assertEqual([1], calls)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertNotIn(TOKEN, (self.run_dir / "toolbox-timeout_1.json").read_text())

    def test_result_with_callable_error_never_counts_as_acceptance(self):
        for status in ("DEADLINE_EXCEEDED", "INVALID_ARGUMENT"):
            with self.subTest(status=status):
                self.write_token(TOKEN)
                result = sender.send_once(
                    token_file=self.token_file, run_dir=self.run_dir, test_id="mixed_" + status,
                    transport=lambda _, s=status: (200, json.dumps({"result": MESSAGE,
                        "error": {"status": s, "message": TOKEN}}).encode()))
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["messageId"])

    def test_server_error_and_non_object_response_remain_unknown(self):
        for code, body in [(500, {"error": {"status": "INVALID_ARGUMENT"}}), (200, None)]:
            with self.subTest(code=code):
                self.write_token(TOKEN)
                result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir,
                    test_id="ambiguous_" + str(code),
                    transport=lambda _, c=code, b=body: (c, json.dumps(b).encode()))
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["messageId"])

    def test_existing_attempt_returns_state_without_read_or_send(self):
        marker = self.run_dir / "toolbox-repeat_1.json"
        private.write_exclusive(marker, json.dumps({"status": "ACCEPTED", "http": 200,
                                     "messageId": MESSAGE, "start": 1, "end": 2,
                                     "testId": "repeat_1"}).encode())
        self.token_file.unlink()
        calls = []
        result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir,
                                  test_id="repeat_1", transport=lambda body: calls.append(body))
        self.assertTrue(result["existingAttempt"])
        self.assertEqual("ACCEPTED", result["status"])
        self.assertEqual([], calls)

    def test_unsafe_token_files_are_rejected_without_deletion_or_marker(self):
        if os.name == "nt":
            sid = private._windows().user_sid
            set_windows_test_acl(self.token_file, f"D:P(A;;FA;;;{sid})(A;;FR;;;WD)")
            expected_error = "TOKEN_FILE_UNSAFE"
        else:
            self.token_file.chmod(0o644)
            expected_error = "TOKEN_FILE_MODE_NOT_0600"
        with self.assertRaisesRegex(sender.SenderError, expected_error):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="mode_1")
        self.assertTrue(self.token_file.exists())
        self.assertFalse((self.run_dir / "toolbox-mode_1.json").exists())
        self.token_file.unlink()
        target = self.run_dir / "target"
        private.write_exclusive(target, TOKEN.encode())
        # Hardlinks exercise the same single-link ownership policy without requiring symlink privilege.
        os.link(target, self.token_file)
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_FILE_UNSAFE"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="link_1")
        self.assertTrue(target.exists())

    def test_topic_token_is_rejected_before_attempt(self):
        self.write_token("/topics/not-an-owned-device-token")
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_INVALID"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="topic_1")
        self.assertTrue(self.token_file.exists())

    def test_token_outside_run_dir_and_echoed_message_id_are_never_accepted(self):
        outside_dir = Path(self.temp.name) / "other-private"
        private.ensure_private_directory(outside_dir)
        outside = outside_dir / "outside-token"
        private.write_exclusive(outside, TOKEN.encode())
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_FILE_OUTSIDE_RUN_DIR"):
            sender.send_once(token_file=outside, run_dir=self.run_dir, test_id="outside_1")
        self.assertTrue(outside.exists())
        echoed = "projects/fir-cloudmessaging-4e2cd/messages/" + TOKEN
        result = sender.send_once(
            token_file=self.token_file, run_dir=self.run_dir, test_id="echo_1",
            transport=lambda _: (200, json.dumps({"result": echoed}).encode()),
            clock=iter((400, 401)).__next__)
        self.assertEqual("UNKNOWN", result["status"])
        self.assertNotIn(TOKEN, (self.run_dir / "toolbox-echo_1.json").read_text())

    def test_failed_durable_intent_never_calls_transport_and_releases_token(self):
        calls = []
        with mock.patch.object(sender, "create_private_marker", side_effect=private.PrivateFileError("PRIVATE_FILE_FLUSH_FAILED")):
            with self.assertRaisesRegex(sender.SenderError, "ATTEMPT_PERSIST_FAILED"):
                sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="failed_flush",
                                 transport=lambda _: calls.append(1))
        self.assertEqual([], calls)
        self.token_file.rename(self.run_dir / "closed-token")

    def test_corrupt_existing_marker_blocks_without_token_or_transport(self):
        private.write_exclusive(self.run_dir / "toolbox-corrupt.json", b"not-json")
        self.token_file.unlink()
        calls = []
        with self.assertRaisesRegex(sender.SenderError, "ATTEMPT_MARKER_EXISTS_UNREADABLE"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="corrupt",
                             transport=lambda _: calls.append(1))
        self.assertEqual([], calls)

    def test_existing_marker_never_echoes_unknown_private_values(self):
        private.write_exclusive(self.run_dir / "toolbox-private_marker.json", json.dumps({
            "status": TOKEN, "messageId": TOKEN, "start": TOKEN, "tokenDelete": TOKEN,
            "testId": TOKEN, "private": TOKEN}).encode())
        result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="private_marker",
                                  transport=lambda _: self.fail("Existing attempt must not send"))
        self.assertTrue(result["existingAttempt"])
        self.assertNotIn(TOKEN, json.dumps(result))


if __name__ == "__main__":
    unittest.main()
