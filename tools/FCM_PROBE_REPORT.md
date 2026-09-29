# Offline FCM probe correlation report

`fcm_probe_report.py` correlates one sanitized audit produced by
`fcm_probe_sender.py` with an optional exported Guardian
`GUARDIAN_FCM_EVIDENCE_V1` or `GUARDIAN_FCM_EVIDENCE_V2` receipt file. It
performs no network, ADB, device,
credential, or Firebase access. It writes one bounded JSON object to stdout;
errors contain only fixed codes.

This is an evidence correlation tool. `SENT_ACCEPTED` means the FCM HTTP API
accepted the request. It does not mean that a device received or displayed it.
Only a receipt with both the exact `testId` and exact `sentAtEpochMillis`
confirms the owned Guardian callback.

Guardian keeps the existing filename `fcm-self-test-evidence-v1.txt` when its
header upgrades. V1 has the original ten pipe-delimited fields. V2 has the same
ten fields in the same order plus `processStartedAtElapsedMillis` as field 11;
that field is an unsigned long or `-`. Unsupported headers, wrong field counts,
negative values, and process-start values later than
`receivedAtElapsedMillis` fail closed as `INVALID_INPUT`.

## Basic use

```bash
python3 tools/fcm_probe_report.py \
  --sender diagnostics-private/phase3/fcm-probe-EXAMPLE.jsonl
```

With an exported receipt file:

```bash
python3 tools/fcm_probe_report.py \
  --sender diagnostics-private/phase3/fcm-probe-EXAMPLE.jsonl \
  --receipt /local/export/fcm-self-test-evidence-v1.txt
```

The second command can confirm a matching callback immediately. If the valid
snapshot has no match, its result remains `UNKNOWN` unless a trusted export
descriptor and host observation time establish the intended observation
window.

## Trusted bounded snapshot

The descriptor is deliberately small and explicit. It attests that the named
bytes are a complete export of Guardian's current retained receipt file and records
the host-clock observation window for this experiment. It does not turn the
50-entry retained history into an unlimited delivery log.

```json
{
  "schema": "GUARDIAN_FCM_TRUSTED_EXPORT_V1",
  "trust": "EXPLICIT_OPERATOR_TRUST",
  "receipt_sha256": "64-lowercase-or-uppercase-hex-digits",
  "complete_snapshot": true,
  "history_capacity": 50,
  "receipt_count": 3,
  "window_start_host_epoch_ms": 1790507000000,
  "window_end_host_epoch_ms": 1790507300000
}
```

`receipt_sha256` binds the descriptor to the exact receipt bytes and
`receipt_count` must match the decoded rows. The target sender timestamp must
fall inside the inclusive host window. The separately supplied observation
timestamp must be at or after the window end:

```bash
python3 tools/fcm_probe_report.py \
  --sender /local/evidence/sender.jsonl \
  --receipt /local/evidence/fcm-self-test-evidence-v1.txt \
  --export-descriptor /local/evidence/export-descriptor.json \
  --observation-host-epoch-ms 1790507300000
```

With all of those conditions, absence becomes `NOT_OBSERVED`: the exact ID was
not present in that trusted bounded snapshot. It never means “definitely never
received.” `history_limit_reached: true` calls out the especially important
case where all 50 retained rows are occupied. Without a receipt export, the
result stays `AWAITING_DEVICE_EVIDENCE` even after the message TTL; a disconnected
phone is never converted into a false failure.

If reconnecting, launching the app, or another recovery action may have caused
a late callback, the descriptor may include a device-wall-clock cutoff:

```json
"recovery_cutoff_device_epoch_ms": 1790507315000
```

A matching receipt at or after that cutoff is still a confirmed receipt fact,
but is marked `POST_RECOVERY_CONTAMINATED`. The cutoff uses the same device wall
clock as `receivedAtEpochMillis`; it is not a host timestamp.

## Fixed result dimensions

`report_status` is one of:

- `RECEIVED`: exact owned callback receipt matched. An uncertain HTTP outcome
  cannot erase this receipt fact; `sender_status` remains uncertain.
- `AWAITING_DEVICE_EVIDENCE`: no receipt export was supplied for a send that was
  not definitely rejected.
- `NOT_OBSERVED`: no matching row in an explicitly trusted, hash-bound,
  time-bounded snapshot.
- `UNKNOWN`: a snapshot lacked the ID but provenance, observation time, or
  window coverage was insufficient.
- `SEND_REJECTED`: sender evidence establishes a definite rejection and no
  matching receipt contradicts it.
- `EVIDENCE_CONFLICT`: the same test ID has a different sent-at value, or a
  definitely rejected send has a matching callback.
- `INVALID_INPUT`: corrupt, duplicate, conflicting, oversized, or otherwise
  nonconforming evidence. This is emitted on stderr with a fixed error code.

`sender_status` independently preserves `SENT_ACCEPTED`, `SEND_REJECTED`, or
`SEND_OUTCOME_UNCERTAIN`. A sender intent whose outcome record is absent is
treated as uncertain. Inputs are bounded to 64 KiB for sender and receipt data,
8 KiB for the descriptor, two sender records, and 50 receipt rows. Unknown enum
values, duplicate JSON keys, duplicate receipt IDs, extra schema fields, and
descriptor hash/count conflicts fail closed.

For a matching receipt, notification evidence remains separate:
`notification_outcome: POST_REQUESTED` means the app requested posting. The
report keeps `notification_display_status: NOT_CONFIRMED`; it does not claim the
notification appeared. `network_at_callback: UNAVAILABLE` is context sampled at
the callback. Missing receipts and host transport failures do not imply that the
phone was offline.

For a matching V2 receipt, `process_started_at_elapsed_ms` repeats the bounded
elapsed-realtime observation and `process_age_at_callback_ms` is
`receivedAtElapsedMillis - processStartedAtElapsedMillis`. Both are `null` for
V1, or when V2 records `-`; `process_start_not_observed_cause` distinguishes the
legacy unavailable field from a V2 observation that was unavailable. These
elapsed-clock values can support a future combined long-lock/process-lifecycle
verification, but they are not independent proof that the process was absent
before send or that FCM exclusively triggered the process. The fixed
`lifecycle_claim_limit` field makes that limit explicit in every matched report.

Handler duration is assessed against a caller-selected engineering threshold:

```bash
python3 tools/fcm_probe_report.py \
  --sender /local/evidence/sender.jsonl \
  --receipt /local/evidence/fcm-self-test-evidence-v1.txt \
  --processing-slow-threshold-ms 1000
```

The default is 1000 ms. A duration strictly greater than the threshold produces
`PROCESSING_SLOW`; a missing duration produces `PROCESSING_PENDING`, not zero.
This threshold is an engineering triage boundary, not a delivery SLA or an OEM
health claim.

## Clock interpretation

The default timing output is only `raw_wall_delta_ms = device received wall
time - host sent payload time`. It may be negative when clocks differ. The tool
does not label a probe healthy or delayed from this value.

An explicit phone-minus-host offset and its symmetric error bound may add an
adjusted range:

```bash
python3 tools/fcm_probe_report.py \
  --sender /local/evidence/sender.jsonl \
  --receipt /local/evidence/fcm-self-test-evidence-v1.txt \
  --phone-minus-host-offset-ms -648.5 \
  --clock-error-ms 30
```

Both calibration flags are required together. The adjusted result remains a
host-send-to-callback estimate, not pure network latency. It receives no
healthy/delayed label.

## Scope limits and frozen real evidence

The frozen public
`docs/fixtures/phase3/20260927-process-absence/verification.json` records an
exact process-absence callback; the corresponding exact sender audit plus V1
receipt is the kind of pair this tool classifies as `RECEIVED`. The frozen
`docs/fixtures/phase3/20260927-smart-locked/verification.json` records HTTP
acceptance and a user notification observation while its exact receipt was
still pending; without that receipt export, this tool conservatively reports
`AWAITING_DEVICE_EVIDENCE`. These are descriptions of existing real fixtures,
not synthetic test results and not claims that the private artifacts were read
by this documentation workflow.

A Guardian receipt confirms only the owned Guardian callback and its recorded
local context. It does not establish smart-battery causality, Doze success,
notification display, third-party app delivery or execution, Telegram repair,
long-connection health, or repair efficacy.
