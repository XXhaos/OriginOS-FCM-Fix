# Archived advanced diagnostic tools — 2026-10-12

This directory contains source and test snapshots taken from the existing working tree before removing the following optional tools from the daily APK:

- Finite deep process monitoring and the retained timeline.
- Selected-app notification fact collection and its notification listener.
- Guardian's own Firebase registration/token and controlled push self-test.

The snapshots include the original shared UI, report, privileged command, manifest and build files needed to understand their integration. They include pre-existing uncommitted v69/v70 work; they are not a clean release checkout. None of these archived files participates in the app/core Gradle source sets. No Firebase configuration, signing key, private credential, APK, or user evidence file is included.

The daily build retains application protection and rechecks, durable authority and reconciliation, core GMS/FCM connection observation/recovery/heartbeat, the external GMS Diagnostics entry point, low-frequency daily inspection/WorkManager, and combined resource/process-exit/lifecycle reports. Removing these tools does not establish a cause of heat or measured battery improvement.

The v71 removal package used report schema `guardian.combined-diagnostic.v10`; v72 used v11 and current installed v73 uses v12. The archived shared report sources are the pre-removal snapshot, not the current report contract. Removed self-test/notification/monitor sections remain omitted and `removedCapabilities` explicitly identifies the removed collectors. Historical on-device records remain untouched and are not read by current builds. No collection does not prove absence of events, delivery failures, or notifications. v73 logging/export passed; actual automatic FCM reconnection remains inconclusive and is not established by this archive.

Restoration requires deliberate integration against the current core rather than replacing shared snapshots wholesale: restore only desired tool sources/tests, their UI callbacks and collectors, the corresponding manifest services and report projection. Deep monitoring additionally needs the archived core trace parser/codec, its closed SafeCommand operation and its privileged validation/transport. Firebase self-test requires the Firebase Messaging/BOM dependency, the conditional Google Services plugin/build field and a separately supplied private configuration; no configuration is supplied by this archive. Reassess bounded execution, stop behavior and privacy, and run host checks before device validation.

Low-frequency daily inspection is intentionally retained in this package; it may be considered separately, including cancelling its durable scheduled work before any future removal.
