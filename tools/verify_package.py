import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(path):
    source_manifest = json.loads((ROOT / "manifest.json").read_text())
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if archive.testzip() is not None or len(names) != len(set(name.casefold() for name in names)):
            raise RuntimeError("ZIP CRC or duplicate path error")
        for name in names:
            relative = PurePosixPath(name)
            if relative.is_absolute() or ".." in relative.parts or "\\" in name or ":" in name:
                raise RuntimeError("Unsafe ZIP path: " + name)
        manifest = json.loads(archive.read("manifest.json"))
        if manifest != source_manifest:
            raise RuntimeError("Package manifest differs from source")
        required = {"Install.cmd", "Update.cmd", "Uninstall.cmd", "Status.cmd", "install.ps1",
                    "README.txt", "THIRD_PARTY_NOTICES.txt", "manifest.json", "SHA256SUMS.txt",
                    *manifest["files"]}
        if set(names) != required:
            raise RuntimeError("Unexpected or missing server installation file")
        checksums = {}
        for line in archive.read("SHA256SUMS.txt").decode().splitlines():
            digest, name = line.split("  ", 1)
            checksums[name] = digest
        if set(checksums) != set(names) - {"SHA256SUMS.txt"}:
            raise RuntimeError("Checksum inventory differs")
        for name in names:
            content = archive.read(name)
            if name != "SHA256SUMS.txt" and hashlib.sha256(content).hexdigest() != checksums[name]:
                raise RuntimeError("Checksum mismatch: " + name)
            if name in manifest["files"] and hashlib.sha256(content).hexdigest() != manifest["files"][name]:
                raise RuntimeError("Payload hash differs")
            if name.endswith(".class"):
                raise RuntimeError("Loose class in package")
            if name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(content)) as library:
                    if any(entry.startswith("zombie/") or "PacketInbox" in entry for entry in library.namelist()):
                        raise RuntimeError("Game or experimental queue class in distributed jar")
        for name in ("Install.cmd", "Update.cmd", "Uninstall.cmd", "Status.cmd", "install.ps1"):
            if archive.read(name) != (ROOT / name).read_bytes():
                raise RuntimeError("Installer differs from source")
    return {"passed": True, "members": len(names), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "ballistics_included": False, "game_classes_distributed": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    print(json.dumps(verify(parser.parse_args().archive), indent=2))