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
  attribution checks. Follow docs/RELEASING.md. Official automated signing is
  authorized only in the isolated `guardian-release` GitHub environment job;
  never expose signing secrets to source builds, PRs, caches or artifacts.
- Keep user documentation concise and record public changes in CHANGELOG.md.
  Keep private development evidence outside the tracked source tree.

## Gitea source and GitHub operations

- Gitea is the source of truth for code, workflow files, tags and release
  announcements. Commit and push file changes to Gitea, then verify the GitHub
  mirror. Do not fix mirrored files only on GitHub or disclose the source host.
- Treat the actual Gitea address as private in every future change. Never put
  its URL, clone/remote address, hostname or identifying server address in
  tracked files, commit/tag messages, public logs, issues/PRs, release notes,
  APKs, source archives or other published artifacts. Use GitHub public links
  and synthetic example origins. Read the real endpoint only from authorized
  private configuration or encrypted Secrets such as `GITEA_SOURCE_URL`; never
  echo it. Before committing or publishing, inspect changes and generated
  public content for source-address disclosures. Keep private audit evidence
  outside the repository and never reintroduce old unsanitized history.
- Routine development and release announcements need only Gitea. During an
  active maintenance task, still investigate failed or missing GitHub Actions,
  delayed mirrors/releases, expired credentials, environment gates and disabled
  schedules. GitHub Settings, Secrets, environments and Actions enablement are
  service configuration maintained on GitHub; Gitea pushes do not repair them.
- With existing authorization and access, diagnose and perform routine recovery
  on the relevant service rather than defaulting to asking the user to operate
  GitHub manually. Request input or confirmation only when actually required;
  preserve the original signing identity, secret handling and release safeguards.
- Read [release troubleshooting](docs/RELEASING.md#核验与故障处理). After recovery,
  verify matching source/mirror refs and the relevant actual workflow or release
  result. A successful Git push or a mirrored tag alone does not prove a build
  or Release succeeded.
