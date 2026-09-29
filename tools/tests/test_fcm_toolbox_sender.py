import json
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fcm_toolbox_sender as sender

TOKEN = "owned-device-token:APA91synthetic_abcdefghijklmnopqrstuvwxyz"
MESSAGE = "projects/fir-cloudmessaging-4e2cd/messages/abc123"


class ToolboxSenderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp.name) / "diagnostics-private"
        self.run_dir.mkdir(mode=0o700)
        self.token_file = self.run_dir / "owned-token.txt"
        self.token_file.write_text(TOKEN + "\n", encoding="utf-8")
        self.token_file.chmod(0o600)

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
        self.assertEqual(0o600, os.stat(self.run_dir / "toolbox-ok_1.json").st_mode & 0o777)

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
                self.token_file.write_text(TOKEN)
                self.token_file.chmod(0o600)
                result = sender.send_once(
                    token_file=self.token_file, run_dir=self.run_dir, test_id="mixed_" + status,
                    transport=lambda _, s=status: (200, json.dumps({"result": MESSAGE,
                        "error": {"status": s, "message": TOKEN}}).encode()))
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["messageId"])

    def test_server_error_and_non_object_response_remain_unknown(self):
        for code, body in [(500, {"error": {"status": "INVALID_ARGUMENT"}}), (200, None)]:
            with self.subTest(code=code):
                self.token_file.write_text(TOKEN)
                self.token_file.chmod(0o600)
                result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir,
                    test_id="ambiguous_" + str(code),
                    transport=lambda _, c=code, b=body: (c, json.dumps(b).encode()))
                self.assertEqual("UNKNOWN", result["status"])
                self.assertIsNone(result["messageId"])

    def test_existing_attempt_returns_state_without_read_or_send(self):
        marker = self.run_dir / "toolbox-repeat_1.json"
        marker.write_text(json.dumps({"status": "ACCEPTED", "http": 200,
                                     "messageId": MESSAGE, "start": 1, "end": 2,
                                     "testId": "repeat_1"}))
        self.token_file.unlink()
        calls = []
        result = sender.send_once(token_file=self.token_file, run_dir=self.run_dir,
                                  test_id="repeat_1", transport=lambda body: calls.append(body))
        self.assertTrue(result["existingAttempt"])
        self.assertEqual("ACCEPTED", result["status"])
        self.assertEqual([], calls)

    def test_unsafe_token_files_are_rejected_without_deletion_or_marker(self):
        self.token_file.chmod(0o644)
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_FILE_MODE_NOT_0600"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="mode_1")
        self.assertTrue(self.token_file.exists())
        self.assertFalse((self.run_dir / "toolbox-mode_1.json").exists())
        self.token_file.unlink()
        target = self.run_dir / "target"
        target.write_text(TOKEN)
        target.chmod(0o600)
        self.token_file.symlink_to(target)
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_FILE_UNSAFE"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="link_1")
        self.assertTrue(target.exists())

    def test_topic_token_is_rejected_before_attempt(self):
        self.token_file.write_text("/topics/not-an-owned-device-token", encoding="utf-8")
        self.token_file.chmod(0o600)
        with self.assertRaisesRegex(sender.SenderError, "TOKEN_INVALID"):
            sender.send_once(token_file=self.token_file, run_dir=self.run_dir, test_id="topic_1")
        self.assertTrue(self.token_file.exists())

    def test_token_outside_run_dir_and_echoed_message_id_are_never_accepted(self):
        outside = Path(self.temp.name) / "outside-token"
        outside.write_text(TOKEN)
        outside.chmod(0o600)
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


if __name__ == "__main__":
    unittest.main()
