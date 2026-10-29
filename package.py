import hashlib
import json
from pathlib import Path
import zipfile
from tools.verify_package import verify

ROOT = Path(__file__).resolve().parent
manifest = json.loads((ROOT / "manifest.json").read_text())
members = ["Install.cmd", "Update.cmd", "Uninstall.cmd", "Status.cmd", "install.ps1",
           "README.txt", "THIRD_PARTY_NOTICES.txt", "manifest.json", *manifest["files"]]
content = {name: (ROOT / name).read_bytes() for name in members}
for name, expected in manifest["files"].items():
    if hashlib.sha256(content[name]).hexdigest() != expected:
        raise RuntimeError("Build the verified payload first: " + name)
content["SHA256SUMS.txt"] = ("\n".join(
    f"{hashlib.sha256(data).hexdigest()}  {name}" for name, data in sorted(content.items())) + "\n").encode()
output = ROOT / f"dist/Zomboid-Server-Patch-Only-42.21-{manifest['version']}.zip"
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, data in sorted(content.items()):
        info = zipfile.ZipInfo(name, (2026, 10, 29, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        archive.writestr(info, data)
print(json.dumps({"archive": str(output), **verify(output)}, indent=2))