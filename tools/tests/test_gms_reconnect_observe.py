import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("gms_reconnect_observe", Path(__file__).resolve().parents[1] / "gms_reconnect_observe.py")
observer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = observer
SPEC.loader.exec_module(observer)

SECTION = "    Historical broadcasts:\n"
LOW = "2026-10-03 12:00:00"
HIGH = "2026-10-03 12:00:10"


def record(index=0, *, binder="abc123", action=observer.ACTION, package=observer.PACKAGE,
           user=0, caller=2000, uid=10208, stamp="2026-10-03 12:00:05", reason="remote app",
           state="DELIVERED", queue=""):
    queue = " " + queue if queue else ""
    return (f"    Historical Broadcast{queue} #{index}:\n"
            f"      BroadcastRecord{{{binder} u{user} {action}}} to user {user}\n"
            f"      Intent {{ act={action} flg=0x400000 pkg={package} }}\n"
            "      extras: Bundle[{secret=DO_NOT_OUTPUT_PRIVATE_CONTENT}]\n"
            f"      caller=null null pid=123 uid={caller} realCallingUid=2000\n"
            f"      enqueueClockTime={stamp} dispatchClockTime={stamp}\n"
            "      terminalCount=1\n"
            f"      {state} scheduled +1ms terminal +2ms (0) #0: BroadcastFilter{{ab12 {uid}/u0 ReceiverList{{cd34 123 com.google.android.gms.persistent/{uid}/u0 remote:ef56}}}}\n"
            f"        reason: {reason}\n")


def summary(text, complete=True, **kwargs):
    capture = observer.Capture(text.encode(), stdout_capped=not complete)
    return observer.observe(capture, 10208, LOW, HIGH, **kwargs)


class ParserTest(unittest.TestCase):
    def test_action_allowlist_uses_the_verified_dex_literal(self):
        self.assertEqual(observer.HEARTBEAT_ACTION, "com.google.android.intent.action.MCS_HEARTBEAT")
        self.assertEqual(observer.OBSERVABLE_ACTIONS, (observer.ACTION, observer.HEARTBEAT_ACTION))

    def test_heartbeat_is_explicit_and_cannot_borrow_reconnect_records(self):
        text = SECTION + record() + record(1, binder="def456", action=observer.HEARTBEAT_ACTION)
        result = summary(text, action=observer.HEARTBEAT_ACTION)
        self.assertEqual(result["observedAction"], observer.HEARTBEAT_ACTION)
        self.assertEqual(result["result"], "TARGET_RECORD_OBSERVED")
        self.assertEqual(len(result["targets"]), 1)
        self.assertEqual(summary(SECTION + record(), action=observer.HEARTBEAT_ACTION)["targets"], [])

    def test_default_preserves_c1_action_and_excludes_heartbeat(self):
        result = summary(SECTION + record(action=observer.HEARTBEAT_ACTION))
        self.assertEqual(result["observedAction"], observer.ACTION)
        self.assertEqual(result["result"], "UNKNOWN")
        self.assertEqual(result["targets"], [])
        self.assertEqual(summary(SECTION + record())["result"], "TARGET_RECORD_OBSERVED")

    def test_unsupported_actions_rejected_before_cli_collection(self):
        for action in ("", observer.HEARTBEAT_ACTION + ".other",
                       "com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW"):
            with self.subTest(action=action), self.assertRaises(ValueError):
                observer.observe(observer.Capture(b""), 10208, action=action)
        with patch.object(sys, "argv", ["observe", "--serial", "TESTSERIAL", "--expected-uid", "10208",
                                       "--action", "unsupported.action"]), \
                patch.object(observer, "collect") as collect, patch.object(sys, "stderr", io.StringIO()):
            with self.assertRaises(SystemExit):
                observer.main()
            collect.assert_not_called()

    def test_cli_selects_heartbeat_without_changing_collection_command(self):
        capture = observer.Capture((SECTION + record(action=observer.HEARTBEAT_ACTION)).encode())
        output = io.StringIO()
        with patch.object(sys, "argv", ["observe", "--serial", "TESTSERIAL", "--expected-uid", "10208",
                                       "--action", observer.HEARTBEAT_ACTION,
                                       "--not-before", LOW, "--not-after", HIGH]), \
                patch.object(observer, "collect", return_value=capture) as collect, \
                patch.object(sys, "stdout", output):
            observer.main()
        collect.assert_called_once_with("TESTSERIAL", "adb")
        result = json.loads(output.getvalue())
        self.assertEqual(result["observedAction"], observer.HEARTBEAT_ACTION)
        self.assertEqual(result["result"], "TARGET_RECORD_OBSERVED")

    def test_complete_eof_and_no_raw_private_fields(self):
        result = summary(SECTION + record())
        self.assertEqual(result["result"], "TARGET_RECORD_OBSERVED")
        self.assertEqual(result["shapeCounts"]["fullShapes"], 1)
        self.assertEqual(result["targets"][0]["reasonClass"], "REMOTE_APP")
        self.assertNotIn("DO_NOT_OUTPUT", json.dumps(result))
        self.assertNotIn("abc123", json.dumps(result))

    def test_preflight_reports_target_ids_without_claiming_event(self):
        result = observer.observe(observer.Capture((SECTION + record()).encode()), 10208)
        self.assertEqual(result["result"], "UNKNOWN")
        self.assertEqual(len(result["targets"]), 1)

    def test_cap_midrecord_and_partial_boundary_are_incomplete(self):
        for text in (SECTION + record(), SECTION + record() + "    Historical Broadcast #1:"):
            with self.subTest(text=text[-45:]):
                result = summary(text, complete=False)
                self.assertEqual(result["result"], "UNKNOWN")
                self.assertEqual(result["shapeCounts"]["completeRecords"], 0)
                self.assertEqual(result["incompleteRecords"], 1)

    def test_complete_record_survives_later_cap(self):
        result = summary(SECTION + record() + record(1, binder="def456", action="other.action")[:120], complete=False)
        self.assertEqual(result["globalCompleteness"], "INCOMPLETE")
        self.assertEqual(result["result"], "TARGET_RECORD_OBSERVED")
        self.assertEqual(result["shapeCounts"]["completeRecords"], 1)

    def test_summary_section_completes_record_but_does_not_supply_fields(self):
        result = summary(SECTION + record() + "    Historical broadcasts summary:\n" + record(1, binder="ff77"), complete=False)
        self.assertEqual(len(result["targets"]), 1)
        self.assertEqual(result["shapeCounts"]["completeRecords"], 1)

    def test_queue_formats_and_outside_history(self):
        for queue in ("", "modern", "[foreground]"):
            result = summary("Pending broadcasts:\n" + record() + "Queue:\n" + SECTION + record(queue=queue))
            self.assertEqual(len(result["targets"]), 1)
        self.assertEqual(summary("Queue:\n" + record())["targets"], [])

    def test_identity_mismatches_never_match(self):
        for changes in ({"action": observer.ACTION + ".other"}, {"package": observer.PACKAGE + ".other"},
                        {"user": 999}, {"caller": 1000}, {"uid": 10170},
                        {"stamp": "2026-10-03 11:59:59"}):
            with self.subTest(changes=changes):
                self.assertEqual(summary(SECTION + record(**changes))["result"], "UNKNOWN")

    def test_caller_uid_is_not_real_calling_uid(self):
        self.assertEqual(summary(SECTION + record(caller=1000))["targets"], [])

    def test_duplicate_or_extra_like_fields_fail_closed(self):
        text = SECTION + record().replace("      terminalCount=1", "      caller=null null pid=1 uid=2000\n      terminalCount=1")
        self.assertEqual(summary(text)["targets"], [])
        text = SECTION + record(action="other").replace("      extras:", "      extras: act=" + observer.ACTION + " pkg=" + observer.PACKAGE + " ")
        self.assertEqual(summary(text)["targets"], [])

    def test_multiple_matching_records_are_ambiguous(self):
        result = summary(SECTION + record() + record(1, binder="def456"))
        self.assertEqual(result["result"], "UNKNOWN")
        self.assertEqual(result["correlation"], "AMBIGUOUS")

    def test_exact_duplicate_record_collapses_with_raw_and_unique_counts(self):
        result = summary(SECTION + record(state="SKIPPED", reason="Exported Denial: PRIVATE")
                         + record(1, state="SKIPPED", reason="Exported Denial: PRIVATE"))
        self.assertEqual(result["rawOccurrenceCount"], 2)
        self.assertEqual(result["uniqueRecordCount"], 1)
        self.assertEqual(len(result["targets"]), 1)
        self.assertEqual(result["correlation"], "ONE_MATCH_IN_CAPTURE")
        self.assertEqual(result["result"], "TARGET_RECORD_OBSERVED")
        self.assertEqual(result["targets"][0]["targetDelivery"], "SKIPPED")
        self.assertEqual(result["targets"][0]["reasonClass"], "EXPORT_DENIAL")

    def test_same_record_id_with_any_field_conflict_is_unknown(self):
        original = summary(SECTION + record())["targets"][0]
        for key, value in original.items():
            if key == "recordId":
                continue
            conflicting = dict(original)
            conflicting[key] = not value if isinstance(value, bool) else value + "-different"
            with self.subTest(field=key):
                result = observer.summarize_targets([original, conflicting, dict(original)])
                self.assertEqual(result["rawOccurrenceCount"], 3)
                self.assertEqual(result["uniqueRecordCount"], 1)
                self.assertEqual(result["targets"], [])
                self.assertEqual(result["correlation"], "CONFLICT")
                self.assertEqual(result["result"], "UNKNOWN")

    def test_distinct_ids_remain_ambiguous_after_duplicate_collapse(self):
        first = summary(SECTION + record())["targets"][0]
        second = summary(SECTION + record(binder="def456"))["targets"][0]
        result = observer.summarize_targets([first, dict(first), second])
        self.assertEqual(result["rawOccurrenceCount"], 3)
        self.assertEqual(result["uniqueRecordCount"], 2)
        self.assertEqual(len(result["targets"]), 2)
        self.assertEqual(result["correlation"], "AMBIGUOUS")
        self.assertEqual(result["result"], "UNKNOWN")

    def test_baseline_hash_stable_across_history_ordinals(self):
        baseline = summary(SECTION + record())["targets"][0]["recordId"]
        result = summary(SECTION + record(19), baseline_ids=[baseline])
        self.assertEqual(result["targets"][0]["recordId"], baseline)
        self.assertEqual(result["result"], "UNKNOWN")

    def test_reason_belongs_to_its_receiver(self):
        text = SECTION + record(reason="assuming delivered")
        text += ("      SKIPPED (0) #1: BroadcastFilter{a111 10170/u0 ReceiverList{b222 123 other/10170/u0 remote:c333}}\n"
                 "        reason: Permission Denial: receiver not exported\n")
        result = summary(text)
        self.assertEqual(result["targets"][0]["reasonClass"], "ASSUMING_DELIVERED")
        self.assertEqual(result["targets"][0]["targetDelivery"], "DELIVERED")

    def test_denials_and_other_reasons_do_not_leak_raw_reason(self):
        for reason, expected in (("Receiver not exported PRIVATE", "EXPORT_DENIAL"),
                                 ("Exported Denial: PRIVATE", "EXPORT_DENIAL"),
                                 ("Receiver not specifying RECEIVER_EXPORTED PRIVATE", "EXPORT_DENIAL"),
                                 ("Permission Denial PRIVATE", "PERMISSION_DENIAL"),
                                 ("PRIVATE miscellaneous reason", "OTHER")):
            result = summary(SECTION + record(state="SKIPPED", reason=reason))
            self.assertEqual(result["targets"][0]["reasonClass"], expected)
            self.assertNotIn("PRIVATE", json.dumps(result))

    def test_unknown_receiver_cannot_donate_reason_to_target(self):
        text = SECTION + record(reason="assuming delivered")
        text += "      OEM_UNKNOWN (0) #1: BroadcastFilter{unknown}\n        reason: remote app\n"
        result = summary(text)
        self.assertEqual(result["targets"][0]["reasonClass"], "ASSUMING_DELIVERED")
        text = SECTION + record().rsplit("        reason:", 1)[0]
        text += "      OEM_UNKNOWN (0) #1: BroadcastFilter{unknown}\n        reason: remote app\n"
        result = summary(text)
        self.assertEqual(result["targets"][0]["reasonClass"], "OTHER")
        self.assertFalse(result["targets"][0]["terminalEvidence"])
        self.assertEqual(result["shapeCounts"]["fullShapes"], 0)

    def test_fullshape_requires_runtime_uid_and_its_own_terminal_reason(self):
        for text in ((SECTION + record()).replace("10208/u0", "unknown/u0"),
                     SECTION + record(state="PENDING")):
            result = summary(text)
            self.assertEqual(result["shapeCounts"]["fullShapes"], 0)
        result = summary(SECTION + record(uid=10170))
        self.assertEqual(result["shapeCounts"]["fullShapes"], 1)
        self.assertEqual(result["targets"], [])

    def test_unknown_section_cannot_complete_record(self):
        result = summary(SECTION + record() + "    Unknown OEM section:\n")
        self.assertEqual(result["targets"], [])
        self.assertEqual(result["incompleteRecords"], 1)

    def test_nonempty_stderr_fails_global_completeness(self):
        capture = observer.Capture((SECTION + record()).encode(), stderr_bytes=12)
        result = observer.observe(capture, 10208, LOW, HIGH)
        self.assertEqual(result["globalCompleteness"], "INCOMPLETE")
        self.assertEqual(result["result"], "UNKNOWN")

    def test_failed_eof_and_service_timeout_do_not_complete_trailing_record(self):
        for capture in (observer.Capture((SECTION + record()).encode(), returncode=1),
                        observer.Capture((SECTION + record()).encode(), timed_out=True),
                        observer.Capture((SECTION + record() + "*** DUMP TIMEOUT\n").encode())):
            self.assertEqual(observer.observe(capture, 10208, LOW, HIGH)["result"], "UNKNOWN")

    def test_no_matching_record_always_unknown(self):
        for complete in (True, False):
            self.assertEqual(summary(SECTION + record(action="other"), complete)["result"], "UNKNOWN")

    def test_parameter_validation_before_collection(self):
        for serial in ("", "-device", "valid; am broadcast", "a\nb", "a " + "b"):
            with self.assertRaises(ValueError):
                observer.adb_argv(serial)
        self.assertEqual(observer.adb_argv("192.0.2.1:5555"), ["adb", "-s", "192.0.2.1:5555", "shell", "dumpsys", "-t", "5", "activity", "-a", "broadcasts", "history"])
        for kwargs in ({"not_before": LOW}, {"not_before": HIGH, "not_after": LOW}, {"baseline_ids": ["raw-id"]}):
            with self.assertRaises(ValueError):
                observer.observe(observer.Capture(b""), 10208, **kwargs)
        with self.assertRaises(ValueError):
            observer.observe(observer.Capture(b""), 99910208)


class FakeProcess:
    def __init__(self, stdout=b"", stderr=b""):
        self.stdout = io.BytesIO(stdout)
        self.stderr = io.BytesIO(stderr)
        self.returncode = 0
        self.killed = False

    def poll(self):
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9

    def wait(self, timeout):
        return self.returncode


class CollectionTest(unittest.TestCase):
    def test_read_caps_are_bytes_and_buffers_remain_bounded(self):
        process = FakeProcess(b"x" * (observer.STDOUT_CAP + 1), b"e" * (observer.STDERR_CAP + 1))
        with patch.object(observer.subprocess, "Popen", return_value=process) as popen:
            capture = observer.collect("TESTSERIAL")
        self.assertLessEqual(len(capture.stdout), observer.STDOUT_CAP)
        self.assertLessEqual(capture.stderr_bytes, observer.STDERR_CAP)
        self.assertTrue(capture.stdout_capped or capture.stderr_capped)
        self.assertFalse(capture.complete)
        self.assertFalse(popen.call_args.kwargs["shell"])
        self.assertEqual(popen.call_args.args[0][-2:], ["broadcasts", "history"])

    def test_successful_collection(self):
        process = FakeProcess((SECTION + record()).encode())
        with patch.object(observer.subprocess, "Popen", return_value=process):
            capture = observer.collect("TESTSERIAL")
        self.assertTrue(capture.complete)
        self.assertFalse(process.killed)

    def test_launch_error_has_no_raw_error(self):
        with patch.object(observer.subprocess, "Popen", side_effect=OSError("PRIVATE launch text")):
            capture = observer.collect("TESTSERIAL")
        self.assertFalse(capture.complete)
        self.assertNotIn("PRIVATE", json.dumps(observer.observe(capture, 10208)))

    def test_timeout_kills_only_spawned_client(self):
        process = FakeProcess()
        process.returncode = None
        with patch.object(observer.subprocess, "Popen", return_value=process), patch.object(observer, "TIMEOUT", 0):
            capture = observer.collect("TESTSERIAL")
        self.assertTrue(capture.timed_out)
        self.assertTrue(process.killed)


if __name__ == "__main__":
    unittest.main()
