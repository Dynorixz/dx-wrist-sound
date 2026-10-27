"""Package portable source and README, excluding machine paths, caches and APK outputs."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import sys

sys.stdout.reconfigure(encoding="utf-8")

root = Path(__file__).resolve().parent.parent
output = root.parent / "DX-Wrist-Sound-source.zip"
excluded = {".tooling", ".gradle", ".git", ".idea", "build", "artifacts", ".kotlin", ".codex-remote-attachments", "__pycache__"}
with ZipFile(output, "w", ZIP_DEFLATED) as archive:
    count = 0
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or excluded.intersection(relative.parts):
            continue
        if path.name in {"local.properties", "key.properties", "keystore.properties", ".env"} or (
            path.name.startswith(".env.") and path.name != ".env.example"
        ) or path.suffix.lower() in {".jks", ".keystore", ".dpapi", ".p12", ".pfx", ".iml", ".key", ".apk", ".zip", ".log"}:
            continue
        archive.write(path, Path(root.name) / relative)
        count += 1
print(f"Source package: {output} ({count} files, {output.stat().st_size} bytes)")
