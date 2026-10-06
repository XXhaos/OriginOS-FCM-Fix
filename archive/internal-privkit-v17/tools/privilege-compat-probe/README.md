# Priv Kit compiler probe (host only)

2026-10-01 continuation: main Guardian now uses Kotlin/JVM/Compose2.4.10 and Priv Kit0.16.1.
This separate probe remains a historical reproduction of the original2.2.10 incompatibility;
it is not the main-project verification or the current next task. See docs/PROJECT_STATE.md.

This isolated Android library project is **not included in Guardian's settings**, not an APK,
not installed, and not a privileged service. It only compiles a reference to the real
`priv.kit.core.Privilege.serverState.value` Kotlin API. No copied upstream implementation.

Run from the repository root with JDK17 and `ANDROID_HOME` pointing to the installed SDK:

```powershell
.\gradlew.bat -p tools/privilege-compat-probe compileDebugKotlin --console=plain
.\gradlew.bat -p tools/privilege-compat-probe compileDebugKotlin '-PprobeKotlinVersion=2.4.10' --console=plain
```

Results, 2026-10-01, Gradle9.3.1 / AGP9.1.1 / SDK37:

- Current built-in Kotlin2.2.10: **expected compatibility failure**, stdlib2.4.10 metadata2.4
  cannot be read (expected2.2). Normal dependency resolution is not evidence of compilation.
- Isolated KGP2.4.10 override: **compile PASS**. No `-Xskip-metadata-version-check`, forced
  stdlib downgrade or main project toolchain modification was used.
- An intermediate unquoted PowerShell `-P` invocation was split into a task name and failed
  before compilation; the quoted command above is the correct reproducible invocation.

The supported higher-KGP mechanism follows
[AGP documentation](https://developer.android.com/build/releases/agp-9-0-0-release-notes#upgrade-to-a-higher-kgp-version).
This does **not** validate the full Guardian/Compose migration, D8/R8/native packaging,
APK manifest, on-device initialization, Binder identity, activation or reboot recovery.
Next: review a coherent main Kotlin/Compose/AGP compatibility choice before dependency integration;
do not silently change the shipped baseline based on this compile probe alone.

Dependency: `io.github.priv-kit:priv-core:0.16.1`; its published Maven POM declares Apache2.0.
See root THIRD_PARTY_NOTICES.md and docs/PRIVILEGE_STARTUP_PLAN.md. Generated caches/build output
are ignored; no pairing keys or device data belong in this project.
