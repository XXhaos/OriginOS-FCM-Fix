"""Bounded READ-ONLY fixed GMS event history observation; never sends a broadcast.

Only fixed-field target summaries and aggregate shape counts leave memory. Record
boundaries follow AOSP BroadcastHistory.dumpLocked / BroadcastRecord.dump;
unknown OEM shapes stay UNKNOWN. Expected UID must be freshly verified by caller.
"""

import argparse
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import re
import subprocess
import threading
import time

ACTION = "com.google.android.intent.action.GCM_RECONNECT"
HEARTBEAT_ACTION = "com.google.android.intent.action.MCS_HEARTBEAT"
OBSERVABLE_ACTIONS = (ACTION, HEARTBEAT_ACTION)
PACKAGE = "com.google.android.gms"
STDOUT_CAP = 2 * 1024 * 1024
STDERR_CAP = 8 * 1024
TIMEOUT = 8.0
TERMINAL = {"DELIVERED", "SKIPPED", "TIMEOUT", "FAILURE"}
STATES = TERMINAL | {"PENDING", "SCHEDULED", "DEFERRED"}
SECTION = re.compile(r"^(\s*)Historical broadcasts(?: \[[^\]\r\n]+\])?:\s*$")
HEADER = re.compile(r"^(\s*)Historical Broadcast(?: (?:\[[^\]\r\n]+\]|[\w.-]+))? #\d+:\s*$")
END_SECTION = re.compile(r"^\s*(?:Historical broadcasts summary(?: \[[^\]\r\n]+\])?|Pending broadcasts|Frozen broadcasts):\s*$")
STAMP = r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,6})?"
DELIVERY = re.compile(r"^\s*(DELIVERED|SKIPPED|TIMEOUT|FAILURE|PENDING|SCHEDULED|DEFERRED)\s+.*?#\d+:\s+(.*)$")


@dataclass
class Capture:
    stdout: bytes
    stdout_capped: bool = False
    stderr_bytes: int = 0
    stderr_capped: bool = False
    timed_out: bool = False
    returncode: int | None = 0
    read_failed: bool = False
    streams_eof: bool = True

    @property
    def complete(self):
        # dumpsys can report a service timeout even when its process exits zero.
        service_error = re.search(rb"(?:DUMP TIMEOUT|Error dumping service|Can't find service)", self.stdout)
        return (self.returncode == 0 and self.streams_eof and not service_error
                and self.stderr_bytes == 0 and len(self.stdout) <= STDOUT_CAP
                and not self.stdout_capped and not self.stderr_capped
                and not self.timed_out and not self.read_failed)


def adb_argv(serial, adb="adb"):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", serial):
        raise ValueError("invalid device serial")
    return [adb, "-s", serial, "shell", "dumpsys", "-t", "5", "activity", "-a", "broadcasts", "history"]


def collect(serial, adb="adb"):
    """Cap both pipes before accumulation; kill only this spawned adb client."""
    argv = adb_argv(serial, adb)
    buffers = [bytearray(), bytearray()]
    caps = [False, False]
    eof = [False, False]
    errors = [False, False]
    stop = threading.Event()
    try:
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   stdin=subprocess.DEVNULL, shell=False, bufsize=0)
    except OSError:
        return Capture(b"", returncode=None, read_failed=True, streams_eof=False)

    def read_pipe(index, pipe, limit):
        try:
            while not stop.is_set():
                chunk = pipe.read(min(8192, limit - len(buffers[index]) + 1))
                if not chunk:
                    eof[index] = True
                    return
                remaining = limit - len(buffers[index])
                buffers[index].extend(chunk[:remaining])
                if len(chunk) > remaining:
                    caps[index] = True
                    stop.set()
                    return
        except (OSError, ValueError):
            errors[index] = True
            stop.set()

    readers = [threading.Thread(target=read_pipe, args=(i, pipe, cap), daemon=True)
               for i, (pipe, cap) in enumerate(((process.stdout, STDOUT_CAP),
                                               (process.stderr, STDERR_CAP)))]
    for reader in readers:
        reader.start()
    deadline = time.monotonic() + TIMEOUT
    timed_out = False
    while not (process.poll() is not None and all(not r.is_alive() for r in readers)):
        if stop.is_set() or time.monotonic() >= deadline:
            timed_out = not stop.is_set()
            stop.set()
            if process.poll() is None:
                process.kill()
            break
        stop.wait(min(0.02, max(0, deadline - time.monotonic())))
    try:
        process.wait(timeout=0.5)
    except subprocess.TimeoutExpired:
        errors[0] = True
    for reader in readers:
        reader.join(timeout=0.5)
    # Closing a pipe while a reader still uses it can block on Windows. Such a
    # reader is daemonized and owns at most one capped buffer; fail closed.
    for reader, pipe in zip(readers, (process.stdout, process.stderr)):
        if not reader.is_alive():
            pipe.close()
    return Capture(bytes(buffers[0]), caps[0], len(buffers[1]), caps[1], timed_out,
                   process.returncode, any(errors) or any(r.is_alive() for r in readers), all(eof))


def parse_time(value):
    if not re.fullmatch(STAMP, value):
        raise ValueError("use device-local YYYY-MM-DD HH:MM:SS[.fraction]")
    return datetime.fromisoformat(value)


def reason_class(value):
    reason = value.strip().lower()
    if reason == "remote app":
        return "REMOTE_APP"
    if reason == "assuming delivered":
        return "ASSUMING_DELIVERED"
    if ("not exported" in reason or "non-exported" in reason
            or reason.startswith("exported denial:")
            or "not specifying receiver_exported" in reason):
        return "EXPORT_DENIAL"
    if "permission" in reason and any(word in reason for word in ("denial", "denied", "requires", "require", "not allowed")):
        return "PERMISSION_DENIAL"
    return "OTHER"


def record_fields(lines, expected_uid):
    """Parse only anchored framework fields; associate reasons per receiver."""
    values = {"binder": [], "action": [], "package": [], "user": [], "caller": [], "time": []}
    deliveries = []
    current_delivery = None
    for line in lines:
        text = line.strip()
        match = re.fullmatch(r"BroadcastRecord\{([0-9a-fA-F]+)\s+.*\}\s+to user (-?\d+)", text)
        if match:
            values["binder"].append(match[1])
            values["user"].append(int(match[2]))
        if text.startswith("Intent { ") and text.endswith(" }"):
            values["action"].extend(re.findall(r"(?:^|\s)act=([^\s}]+)", text))
            values["package"].extend(re.findall(r"(?:^|\s)pkg=([^\s}]+)", text))
        if text.startswith("caller="):
            values["caller"].extend(int(v) for v in re.findall(r"(?:^|\s)uid=(\d+)(?=\s|$)", text))
        if text.startswith("enqueueClockTime="):
            match = re.match(r"enqueueClockTime=(" + STAMP + r")(?=\s+dispatchClockTime=|$)", text)
            if match:
                try:
                    parse_time(match[1])
                    values["time"].append(match[1])
                except ValueError:
                    pass
        match = DELIVERY.fullmatch(line)
        if match:
            runtime = re.fullmatch(r"BroadcastFilter\{[0-9a-fA-F]+ (\d+)/u(\d+) ReceiverList\{[0-9a-fA-F]+ \d+ .+/(\d+)/u(\d+) (?:remote|local):[0-9a-fA-F]+\}\}", match[2])
            runtime_valid = bool(runtime and runtime[1] == runtime[3] and runtime[2] == runtime[4])
            target = bool(runtime and tuple(map(int, runtime.groups())) == (expected_uid, 0, expected_uid, 0))
            current_delivery = {"state": match[1], "target": target, "runtimeValid": runtime_valid, "reasons": []}
            deliveries.append(current_delivery)
        elif re.match(r"^\s*\S+\s+.*?#\d+:\s*", line):
            # A receiver-looking OEM state/line cannot donate its reason to the
            # last recognized receiver. No inference across an unknown shape.
            current_delivery = None
        elif text.startswith("reason:") and current_delivery is not None:
            current_delivery["reasons"].append(reason_class(text[len("reason:"):]))
    fields = {key: items[0] if len(items) == 1 else None for key, items in values.items()}
    return fields, deliveries


def summarize_targets(targets):
    """Collapse only identical fixed-field summaries; conflicts fail closed."""
    by_id = {}
    conflicts = set()
    for target in targets:
        record_id = target["recordId"]
        if record_id not in by_id:
            by_id[record_id] = target
        elif by_id[record_id] != target:
            conflicts.add(record_id)
    unique = [target for record_id, target in by_id.items() if record_id not in conflicts]
    eligible = [target for target in unique if target["inWindow"] and not target["baselineRecord"]]
    correlation = ("CONFLICT" if conflicts else "AMBIGUOUS" if len(eligible) > 1
                   else "ONE_MATCH_IN_CAPTURE" if eligible else "NO_MATCH_IN_CAPTURE")
    return {"targets": unique, "rawOccurrenceCount": len(targets), "uniqueRecordCount": len(by_id),
            "correlation": correlation,
            "result": "TARGET_RECORD_OBSERVED" if not conflicts and len(eligible) == 1 else "UNKNOWN"}


def observe(capture, expected_uid, not_before=None, not_after=None, baseline_ids=(), *, action=ACTION):
    if action not in OBSERVABLE_ACTIONS:
        raise ValueError("unsupported observation action")
    if not 10000 <= expected_uid < 100000:
        raise ValueError("expected UID must be a freshly verified user0 application UID")
    if (not_before is None) != (not_after is None):
        raise ValueError("both enqueue window endpoints are required")
    low, high = (parse_time(not_before), parse_time(not_after)) if not_before is not None else (None, None)
    if low is not None and low > high:
        raise ValueError("enqueue window is reversed")
    if any(not re.fullmatch(r"[0-9a-f]{64}", item) for item in baseline_ids):
        raise ValueError("baseline record IDs must be SHA256 hashes")
    counts = dict.fromkeys(("completeRecords", "callerUid", "user", "action", "enqueueTime", "terminalStatus", "reason", "fullShapes"), 0)
    targets = []
    incomplete = 0

    def finish(lines, complete):
        nonlocal incomplete
        if lines is None:
            return
        if not complete:
            incomplete += 1
            return
        fields, deliveries = record_fields(lines, expected_uid)
        counts["completeRecords"] += 1
        present = {"callerUid": fields["caller"] is not None,
                   "user": fields["user"] is not None, "action": fields["action"] is not None,
                   "enqueueTime": fields["time"] is not None,
                   "terminalStatus": any(d["state"] in TERMINAL for d in deliveries),
                   "reason": any(len(d["reasons"]) == 1 for d in deliveries)}
        for key, exists in present.items():
            counts[key] += int(exists)
        owned_terminal_reason = any(d["runtimeValid"] and d["state"] in TERMINAL
                                    and len(d["reasons"]) == 1 for d in deliveries)
        counts["fullShapes"] += int(all(present.values()) and fields["binder"] is not None and owned_terminal_reason)
        if not (fields["action"] == action and fields["package"] == PACKAGE
                and fields["user"] == 0 and fields["caller"] == 2000
                and fields["time"] is not None and fields["binder"] is not None):
            return
        intended = [d for d in deliveries if d["target"]]
        if not intended:
            return
        identity = json.dumps([fields[k] for k in ("binder", "time", "action", "package", "user", "caller")], separators=(",", ":"))
        record_id = hashlib.sha256(identity.encode()).hexdigest()
        in_window = low is not None and low <= parse_time(fields["time"]) <= high
        one = intended[0] if len(intended) == 1 else None
        targets.append({"recordId": record_id, "enqueueTimestamp": fields["time"],
                        "actionMatches": True, "packageMatches": True, "user0Matches": True,
                        "callerUid2000Matches": True, "receiverUidMatches": True,
                        "recordComplete": True, "inWindow": in_window,
                        "baselineRecord": record_id in baseline_ids,
                        "terminalEvidence": bool(one and one["state"] in TERMINAL and len(one["reasons"]) == 1),
                        "targetDelivery": one["state"] if one else "UNKNOWN",
                        "reasonClass": one["reasons"][0] if one and len(one["reasons"]) == 1 else "OTHER"})

    active = False
    section_indent = 0
    record = None
    # No partial last line may establish a record/section boundary on a cap.
    text = capture.stdout[:STDOUT_CAP].decode("utf-8", errors="replace")
    lines = text.splitlines()
    if not capture.complete and text and not text.endswith(("\n", "\r")):
        lines = lines[:-1]
    for line in lines:
        section = SECTION.fullmatch(line)
        header = HEADER.fullmatch(line)
        if section:
            finish(record, True)
            record = None
            active = True
            section_indent = len(section[1])
        elif active and header:
            finish(record, True)
            record = []
        elif active and line.strip() and len(line) - len(line.lstrip()) <= section_indent and line.rstrip().endswith(":"):
            finish(record, bool(END_SECTION.fullmatch(line)))
            record = None
            active = False
        elif record is not None:
            record.append(line)
    finish(record, capture.complete)
    return {"observedAction": action,
            "globalCompleteness": "COMPLETE" if capture.complete else "INCOMPLETE",
            "stdoutBytes": min(len(capture.stdout), STDOUT_CAP), "stdoutCapped": capture.stdout_capped,
            "stderrBytes": capture.stderr_bytes, "stderrCapped": capture.stderr_capped,
            "timedOut": capture.timed_out, "readFailed": capture.read_failed,
            "processSucceeded": capture.returncode == 0, "shapeCounts": counts,
            "incompleteRecords": incomplete, **summarize_targets(targets)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-uid", required=True, type=int)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--action", choices=OBSERVABLE_ACTIONS, default=ACTION)
    parser.add_argument("--not-before")
    parser.add_argument("--not-after")
    parser.add_argument("--baseline-record-id", action="append", default=[])
    args = parser.parse_args()
    try:
        # Validate every parameter before the sole read-only subprocess.
        adb_argv(args.serial, args.adb)
        observe(Capture(b""), args.expected_uid, args.not_before, args.not_after,
                args.baseline_record_id, action=args.action)
        summary = observe(collect(args.serial, args.adb), args.expected_uid,
                          args.not_before, args.not_after, args.baseline_record_id, action=args.action)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(summary, ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    main()
