# Single-attempt FCM Toolbox sender

This narrow test helper sends one synthetic data-only Ping to the user's own Toolbox token.
It is separate from Guardian's authenticated Firebase-project sender. It does not schedule work.

```sh
python3 tools/fcm_toolbox_sender.py --test-id toolbox_charging_locked_example
python3 tools/fcm_toolbox_sender.py --execute --test-id toolbox_charging_locked_example \
  --run-dir diagnostics-private/phase4/example \
  --token-file diagnostics-private/phase4/example/owned-token.txt
```

The first command is a no-network dry run. Real sends require an explicit `--execute`, a regular
non-symlink mode0600 token file inside the selected private run directory, and an FCM registration
token matching the tested format. It accepts no topic, arbitrary endpoint or payload. Keep this
directory Git-ignored. The request is fixed HIGH priority, TTL60 seconds, `ping:{}` and synthetic
`testId`, to the official public Toolbox callable endpoint.

Before sending, an exclusive durable attempt marker is written. The same test ID/run directory
cannot send again, including after a timeout or crash. Do not change the ID to bypass this guard.
There is no automatic retry or redirect. After an actual attempt the token file is removed if its
identity still matches; validation failures leave it untouched. A process crash before cleanup
can leave a token file, so inspect the marker and clean up that owned secret without resending.

Only status/HTTP/message ID/times/test ID/cleanup status are saved. Response bodies, token values
and exception messages are not printed. `ACCEPTED` means backend FCM acceptance, not receipt;
malformed responses, timeout, transport failure or ambiguous backend errors remain `UNKNOWN`.
The frontend Firebase SDK exposes callable results as `data`; the wire protocol uses `result`.
User-visible notification and exact Toolbox receipt must be verified separately.

Sources: [callable wire protocol](https://firebase.google.com/docs/functions/callable-reference),
[Toolbox1.11.1 function](https://github.com/SimonMarquis/FCM-toolbox/blob/1.11.1/firebase/functions/index.js).
Endpoint/source review is recorded in `docs/PHASE4_FCM_TOOLBOX.md`. No third-party code was copied.

Run mocked checks with `python3 -m unittest discover -s tools/tests -p test_fcm_toolbox_sender.py`.
Tests never contact Firebase or the phone. A dry run does not prove the live adapter has sent.
