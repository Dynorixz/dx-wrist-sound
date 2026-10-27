"""Archive the completed project and both signed APK pairs, without build caches/keys."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from hashlib import sha256
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
root = Path(__file__).resolve().parent.parent
base = root.parent
metadata = json.loads((root / "mobile/build/outputs/apk/release/output-metadata.json").read_text(encoding="utf-8"))
version = metadata["elements"][0]["versionName"]
if not all(c.isdigit() or c == "." for c in version):
    raise ValueError("Unexpected version")
subprocess.run([sys.executable, str(root / "tools/package-source.py")], check=True)
source_zip = base / "DX-Wrist-Sound-source.zip"
output = base / f"DX-Wrist-Sound-{version}-project.zip"
prefix = f"DX-Wrist-Sound-{version}"
entries = [(source_zip, f"{prefix}/source/{source_zip.name}")]
for name in ("README.md", "BUILD_VERIFICATION.md"):
    entries.append((root / name, f"{prefix}/{name}"))
for kind in ("Compatible", "Release"):
    folder_name = f"APK-{version}-{kind}"
    folder = base / folder_name
    suffix = kind.lower()
    names = [f"DX-Wrist-Sound-{device}-{version}-{suffix}.apk" for device in ("Phone", "Wear")]
    names += [f"Установка-{version}.txt", "SHA256SUMS.txt"]
    if kind == "Release":
        names += ["RELEASE-CERTIFICATE.pem", "signature-mobile.txt", "signature-wear.txt", "release-info.json"]
    for name in names:
        entries.append((folder / name, f"{prefix}/{folder_name}/{name}"))
manifest = []
with ZipFile(output, "w", ZIP_DEFLATED) as archive:
    for path, name in entries:
        if not path.is_file():
            raise FileNotFoundError(path)
        archive.write(path, name)
        manifest.append({"path": name, "bytes": path.stat().st_size, "sha256": sha256(path.read_bytes()).hexdigest()})
    archive.writestr(f"{prefix}/MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2))
with ZipFile(output) as archive:
    assert archive.testzip() is None
    for entry in manifest:
        assert sha256(archive.read(entry["path"])).hexdigest() == entry["sha256"]
    assert not any(Path(n).suffix.lower() in {".keystore", ".jks", ".p12", ".pfx", ".dpapi"} for n in archive.namelist())
digest = sha256(output.read_bytes()).hexdigest()
output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="utf-8")
print(json.dumps({"archive": str(output), "files": len(manifest), "bytes": output.stat().st_size, "sha256": digest}, ensure_ascii=False))
