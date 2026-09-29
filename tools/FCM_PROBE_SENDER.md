# Controlled FCM probe sender

`fcm_probe_sender.py` prepares or sends one HTTP v1 data message to one registration
token owned by the tester. It defaults to a network-free dry run and has no third-party
Python dependencies.

## Safe dry run

```bash
python3 tools/fcm_probe_sender.py \
  --project example-project \
  --test-id local_check_1
```

Dry run does not read token or access-token files even if their paths are supplied. Its
output contains a redacted target, the fixed Firebase endpoint, and the exact data shape.

## One controlled send

Place the single owned registration token and a short-lived OAuth2 access token in separate
local files. Do not put either value on the command line, in chat, or in the repository.
Then run:

```bash
python3 tools/fcm_probe_sender.py \
  --send \
  --project example-project \
  --token-file /private/path/owned-device-token.txt \
  --access-token-file /private/path/oauth-access-token.txt \
  --test-id owned_probe_1
```

The default priority is `normal`. `--priority high` is reserved for a controlled,
user-visible test. Android TTL is fixed at 60 seconds. There is one attempt, a 10-second
request timeout, and no retry. The URL is always
`https://fcm.googleapis.com/v1/projects/{validated-project}/messages:send`.
All HTTP redirects are rejected without a follow-up request, and redirect locations and
bodies are discarded. The bearer credential is therefore never forwarded to a redirect
target.

Before network access, the sender exclusively creates a mode-`0600` JSON Lines artifact at
`diagnostics-private/phase3/fcm-probe-{test-id}.jsonl` (or at `--output`) and durably records
sanitized intent. It appends the sanitized outcome after the request. Existing output is
never overwritten. Keep this private diagnostics directory out of version control.

The payload is data-only and has exactly these application data keys:

```text
guardian_probe_version = 1
test_id = caller-supplied correlation ID
sent_at_epoch_ms = positive sender host wall-clock milliseconds
```

`SENT_ACCEPTED` means the FCM HTTP endpoint accepted the request. It does not mean that a
device received it, that the app callback ran, or that a notification was posted. The
artifact therefore keeps `delivery_status` as `UNKNOWN`. Sender host time before the request
and after the response are stored separately. Subtracting a device timestamp from sender
wall-clock time requires an explicit clock offset/error assumption and must not be described
as raw network latency.

On a transport exception or server-side failure where acceptance may be ambiguous, the tool
records `SEND_OUTCOME_UNCERTAIN`, performs no retry, and preserves the pre-request intent.
It never stores response text, token values, authorization values, or credential paths.
Only a valid FCM message name and allowlisted error status/code may be retained.

This tool does not create Firebase projects, register Android apps, obtain OAuth credentials,
or prove third-party app delivery. Follow the official [FCM HTTP v1 sending and authorization
guide](https://firebase.google.com/docs/cloud-messaging/send/v1-api) and [Android message
handling guide](https://firebase.google.com/docs/cloud-messaging/android/receive) for those
separate setup and client-side steps.

Run the offline test suite with:

```bash
python3 -m unittest discover -s tools/tests -p 'test_fcm_probe_sender.py'
```

The HTTP transport rejects redirects (including301/302/303/307/308); it never follows a
Location with authorization headers. Redirects stay uncertain terminal outcomes without a
retry. Credential file reads and response reads have size limits. Error status strings must
match a closed set and must not contain either supplied secret; arbitrary server text is
never retained. Offline tests include actual loopback HTTP redirect behavior, not only a
mock transport. The loopback tests use dummy values and do not contact Firebase.
