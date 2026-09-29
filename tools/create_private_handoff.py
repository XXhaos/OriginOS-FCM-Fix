#!/usr/bin/env python3
"""Create a local, private companion to Git. Does not upload, send pushes, or touch a phone."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import tarfile
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New .tar.gz path inside .private-transfer")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    private = root / ".private-transfer"
    private.mkdir(mode=0o700, exist_ok=True)
    output = Path(args.output).expanduser().resolve()
    if not output.is_relative_to(private.resolve()) or output.suffixes[-2:] != [".tar", ".gz"]:
        parser.error("Output must be a .tar.gz file inside the project's .private-transfer directory")
    sources = [root / "diagnostics-private", root / "app/google-services.json",
               root / "google-services.json", private / "signing/debug.keystore"]
    missing = [str(p.relative_to(root)) for p in sources if not p.exists()]
    files = []
    omitted = []
    for source in sources:
        candidates = sorted(source.rglob("*")) if source.is_dir() else [source]
        for path in candidates:
            if not path.exists() and not path.is_symlink():
                continue
            relative = path.relative_to(root)
            # Live SSH sockets and local transport artifacts are not project evidence.
            if relative.parts[:2] == ("diagnostics-private", "remote"):
                continue
            if path.is_symlink() or (not path.is_file() and not path.is_dir()):
                omitted.append(str(relative))
            elif path.is_file():
                files.append((path, str(relative)))
    if not files:
        parser.error("No private handoff files found")
    # Exclusive creation prevents accidental overwrite; no fallback to a public directory.
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as destination:
            with tarfile.open(fileobj=destination, mode="w:gz", compresslevel=1) as archive:
                for path, relative in files:
                    archive.add(path, arcname=relative, recursive=False)
                manifest = json.dumps({
                    "createdAt": datetime.now(timezone.utc).isoformat(),
                    "private": True, "encrypted": False,
                    "warning": "Contains private diagnostics and signing material. Never commit or publicly upload.",
                    "files": [relative for _, relative in files],
                    "missing": missing, "omittedSpecialFiles": omitted,
                }, indent=2).encode()
                info = tarfile.TarInfo(".private-transfer/private-handoff-manifest.json")
                info.size, info.mode = len(manifest), 0o600
                archive.addfile(info, io.BytesIO(manifest))
    except BaseException:
        # Keep any partial archive for diagnosis; caller must not treat it as complete.
        print("Archive creation failed; output may be incomplete.")
        raise
    digest = hashlib.sha256()
    with output.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    checksum = output.with_suffix(output.suffix + ".sha256")
    with checksum.open("x") as stream:
        stream.write(digest.hexdigest() + "  " + output.name + "\n")
    os.chmod(checksum, 0o600)
    print(json.dumps({"archive": str(output), "files": len(files), "bytes": output.stat().st_size,
                      "sha256": digest.hexdigest(), "missing": missing,
                      "omittedSpecialFileCount": len(omitted)}))


if __name__ == "__main__":
    main()
