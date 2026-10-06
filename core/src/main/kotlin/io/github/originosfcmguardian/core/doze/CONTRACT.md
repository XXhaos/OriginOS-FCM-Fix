# Doze transaction core contract

2026-10-04 safety note: the current production adapter queries UID owners in user 0 only;
this is not proof of global appId exclusivity. Other-profile and hidden-package owners remain
uncovered. Do not approve a new owned-target/GMS add or inverse experiment using that query
alone. Multi-user expansion is deferred, not implemented; schema2 and all existing engine gates
remain unchanged. Full scope/ownership/inverse evidence is required before any future write.
See docs/PROJECT_CAPABILITY_GAPS.md CAP-02. Historical scoped results do not establish new scope.

The production UserService constructs `DozeTransactionEngine` for `DozeTarget.GMS`
with the hardcoded reviewed PD2609 fingerprint (never a runtime fingerprint used
as its own authorization). Only the debug adapter may select
`DozeTarget.OWNED_DEBUG_TEST`. The device port owns the fixed target; its write
methods accept no package or command argument. `probe()` must return fresh,
bounded, complete identity and `cmd deviceidle whitelist` output. Failed, timed
out, truncated, or stderr-bearing shell calls must throw, not return partial data.

The journal port is the sole app-private writer. `load()` returns exact bytes or
null only for confirmed absence. Unknown, corrupt, orphaned, or unreadable files
must throw or return their exact bytes. `compareAndSet` atomically checks byte
equality and durably commits the replacement. It never deletes a record. These
callbacks are synchronous and must remain live throughout a UserService request;
the engine checks exact readback before every device write. Production paths must
bound callback time and data. Do not place the journal under the immutable v1
read-only baseline path.

The store can use `DozeJournalCodec.validate(bytes)` for loaded records and
`validateTransition(expected, replacement)` inside its CAS lock. Healthy GMS
system/user membership is `NO_WRITE` even when installed in another profile;
an absent target is eligible for an addition only when installed in user 0 alone,
has PCC ID -1, and uniquely owns its UID.

`apply`, `restore`, and `inspect` return `DozeOutcome`. An `UNCERTAIN` intent
is retained for manual investigation; no automatic retry or inverse is issued.
The one retained transaction blocks a new cycle even after closure. This core
does not approve first real-device execution, prove disk persistence, or infer
FCM benefit from whitelist state.
