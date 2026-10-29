# Third-party notices

Guardian's own source is licensed under Apache-2.0; third-party components retain
their original licenses and attribution.

## Runtime dependencies

The committed [resolved inventory](licenses/dependencies/runtime-dependencies.json)
lists release runtime artifacts, their SHA-256 digests, POM license declarations
and any license/NOTICE resources found inside the original AAR/JAR files. The
[generated notices](licenses/dependencies/THIRD-PARTY-NOTICES.txt) and independently
named [upstream resources](licenses/dependencies/upstream/) ship in APK assets.
The inventory describes dependencies before R8, including code R8 may remove.

- **Shizuku API/provider/aidl/shared 13.1.5**: MIT, Copyright (c) 2021 RikkaW.
  [Upstream](https://github.com/RikkaApps/Shizuku-API),
  [full license](licenses/Shizuku-API-MIT.txt). Published artifacts are used without
  upstream modifications. No Shizuku manager or embedded startup implementation is shipped.
- **AndroidX/Compose, Kotlin, kotlinx.coroutines and other resolved artifacts**:
  consult the per-artifact declarations and exact upstream notices. This
  project's license does not replace a dependency's terms.

## Build tooling

The Gradle 9.3.1 wrapper scripts and JAR are generated upstream tooling under
Apache-2.0. Original headers and embedded licenses are preserved.
[Upstream](https://github.com/gradle/gradle/tree/v9.3.1),
[full license](licenses/Apache-2.0.txt). The distribution checksum is pinned in
gradle/wrapper/gradle-wrapper.properties.

## Research references

Earlier development researched Shizuku, GKD, HyperOS-Millet-Guard and other public
projects to understand platform behavior and interaction patterns. Research-only
references are not packaged dependencies. Guardian implements its own protection
boundaries; no GKD/GPL implementation or root-only recovery scripts are distributed
in the current source snapshot or APK. Historical discontinued components are not
part of the 1.0.0 release tree.

## Updating dependencies

Regenerate and check the inventory after dependency changes. Preserve upstream
notices rather than relabeling every component Apache-2.0. Instructions are in
[docs/RELEASING.md](docs/RELEASING.md).
