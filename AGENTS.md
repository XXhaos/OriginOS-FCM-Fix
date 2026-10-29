# Contributor automation notes

OriginOS FCM Guardian is an Android application using external Shizuku. Read
README.md, CONTRIBUTING.md and docs/BUILDING.md before changing behavior.

## Build and verification

- Use the checked-in Gradle wrapper, JDK 17 and Android SDK 37.
- Source, local debug and unsigned release builds require no maintainer key.
- Run relevant unit tests, lint and affected builds; distinguish compiled
  AndroidTest APKs from tests actually run on a device.
- Use tools/verify_privilege_artifacts.ps1 for packaged privilege boundaries.
- After dependency changes, export and verify the committed notices as
  described in docs/RELEASING.md.

## Protection invariants

- Preserve explicit user ON/OFF, selected applications and durable state.
- Temporary process, network or Shizuku loss is not an instruction to turn OFF.
- Keep fresh identity/permission checks, unknown-result fences, bounded reads
  and serialized recovery. Do not trade protection for resource figures.
- Probe runtime capabilities; avoid device, firmware or GMS version allowlists.
- External Shizuku startup remains user-managed. Do not toggle wireless debugging,
  stop shared services, disable Doze or add permanent wake locks.
- Physical device mutations require the operator's authorization and a bounded
  verification/rollback plan. Old logs do not establish current device access.

## Repository and release safety

- Preserve unrelated work and the operator's configured Git identity.
- Never commit keys, passwords, service credentials, local SDK paths,
  device authorization records, private diagnostics or generated APKs.
- Do not commit, push, tag or publish unless requested for the active task.
- Public release artifacts must pass manifest, original-signer, checksum and
  attribution checks. Follow docs/RELEASING.md; signing stays outside public CI.
- Keep user documentation concise and record public changes in CHANGELOG.md.
  Keep private development evidence outside the tracked source tree.
