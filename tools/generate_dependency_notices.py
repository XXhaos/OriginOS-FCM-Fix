"""Preserve resolved runtime POM declarations and upstream notice resources as APK assets.

Run ./gradlew :app:exportReleaseDependencies first, then this script. --check fails
when the committed assets differ. No network access, signing material or phone data.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def notices(data, prefix=""):
    result = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = item.filename
            if re.search(r"(?i)(^|/)(license[^/]*|notice[^/]*|copying[^/]*|al2\.0|lgpl2\.1)$", name):
                result[prefix + name] = archive.read(item)
            elif name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                result.update(notices(archive.read(item), prefix + name + "/"))
    return result


def generate(root):
    manifest = root / "app/build/reports/dependencies/release-artifacts.json"
    artifacts = json.loads(manifest.read_text(encoding="utf-8"))
    files = {}
    inventory = []
    for artifact in artifacts:
        coordinate = artifact["coordinate"]
        slug = coordinate.replace(":", "_")
        pom = ET.parse(artifact["pom"]).getroot()
        ns = {"p": "http://maven.apache.org/POM/4.0.0"}
        licenses = [{"name": node.findtext("p:name", "", ns), "url": node.findtext("p:url", "", ns)}
                    for node in pom.findall("p:licenses/p:license", ns)]
        # ListenableFuture inherits its license from the published Guava parent POM.
        if coordinate == "com.google.guava:listenablefuture:1.0" and not licenses:
            licenses = [{"name": "The Apache Software License, Version 2.0", "url": "http://www.apache.org/licenses/LICENSE-2.0.txt",
                         "inheritedFrom": "com.google.guava:guava-parent:26.0-android"}]
        if not licenses:
            raise ValueError(f"Missing reviewed license declaration: {coordinate}")
        for license_info in licenses:
            label = license_info["name"].lower()
            if "apache" not in label and "mit" not in label:
                raise ValueError(f"Review license before redistribution: {coordinate}: {label}")
        data = Path(artifact["artifact"]).read_bytes()
        extracted = notices(data)
        resource_list = []
        for index, (name, content) in enumerate(sorted(extracted.items())):
            # Archive paths are metadata only; generated filenames never use an upstream path.
            output = f"upstream/{slug}/{index:03d}-{Path(name).name}"
            files[output] = content
            resource_list.append({"upstreamPath": name, "asset": output, "sha256": digest(content)})
        inventory.append({"coordinate": coordinate, "artifactName": Path(artifact["artifact"]).name, "artifactSha256": digest(data),
                          "licenses": licenses, "upstreamNotices": resource_list})
    metadata = {"scope": "Resolved releaseRuntimeClasspath artifacts before R8; includes code R8 may remove.",
                "licenseTexts": {"Apache-2.0": "../Apache-2.0.txt", "Shizuku-MIT": "../Shizuku-API-MIT.txt"},
                "artifacts": inventory}
    files["runtime-dependencies.json"] = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode()
    lines = ["Guardian runtime dependencies", "", metadata["scope"],
             "POM license declarations below; upstream LICENSE/NOTICE resources preserved under upstream/.",
             "Full Apache 2.0 and Shizuku MIT texts are alongside this directory in APK assets.", ""]
    for item in inventory:
        lines.append(item["coordinate"] + " — " + "; ".join(x["name"] for x in item["licenses"]))
    files["THIRD-PARTY-NOTICES.txt"] = ("\n".join(lines) + "\n").encode()
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = root / "licenses/dependencies"
    expected = generate(root)
    actual = {str(path.relative_to(output)).replace("\\", "/"): path.read_bytes()
              for path in output.rglob("*") if path.is_file()} if output.exists() else {}
    if args.check:
        if actual != expected:
            raise SystemExit("Dependency notices differ: regenerate and review before release.")
    else:
        for relative in actual.keys() - expected.keys():
            (output / relative).unlink()
        for relative, data in expected.items():
            target = output / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print(f"Dependency notices {'verified' if args.check else 'generated'}: {len(expected)} files")


if __name__ == "__main__":
    main()
