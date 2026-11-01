"""白名单打包并扫描本机真实秘密，拒绝把私有配置、临时目录或照片带入交付包。"""
import hashlib
import json
import zipfile
from pathlib import Path
from api_client import ROOT, properties

config = properties(ROOT / "config/application-local.properties")
secrets = [config.get("spring.datasource.password", ""), config.get("app.bootstrap.password", "")]
secrets += [properties(ROOT / "config/demo-credentials.properties").get("password", "")]
test_config = ROOT / "config/test-local.properties"
if test_config.exists():
    secrets += [value for key, value in properties(test_config).items() if key.endswith("password")]
key_file = ROOT.parents[1] / "现代版/key.txt"
if key_file.exists():
    secrets.append(key_file.read_text(encoding="utf-8-sig").strip())
secrets = [value.encode("utf-8") for value in secrets if len(value) >= 8]
allowed = [ROOT / "README.md", ROOT / "pom.xml", ROOT / ".gitignore"]
for directory in ("src", "scripts", "docs", "data/demo", "data/covers", "data/replays", "evaluation", ".run"):
    allowed += [file for file in (ROOT / directory).rglob("*") if file.is_file()
                and "__pycache__" not in file.parts and "private-photos" not in file.parts]
allowed += [ROOT / "config/application-local.example.properties"]
allowed = sorted(set(allowed))
for file in allowed:
    content = file.read_bytes()
    if any(secret in content for secret in secrets):
        raise SystemExit("Refused delivery: a local secret was found in " + str(file.relative_to(ROOT)))

destination = ROOT / "dist"
destination.mkdir(exist_ok=True)
source_zip = destination / "LibraMind-backend-source.zip"
with zipfile.ZipFile(source_zip, "w", zipfile.ZIP_DEFLATED) as archive:
    for file in allowed:
        archive.write(file, "libramind-backend/" + file.relative_to(ROOT).as_posix())
handoff_zip = destination / "LibraMind-Claude-frontend-handoff.zip"
with zipfile.ZipFile(handoff_zip, "w", zipfile.ZIP_DEFLATED) as archive:
    for name in ("README.md", "docs/FRONTEND_HANDOFF.md", "docs/API_CONTRACT.md", "docs/ARCHITECTURE.md",
                 "docs/DECISIONS.md", "docs/VERIFICATION.md", "docs/openapi.json",
                 "evaluation/RETRIEVAL_REPORT.md", "evaluation/COVER_BASELINE_REPORT.md",
                 "evaluation/PENDING_REAL_WORLD_EVALUATION.md"):
        file = ROOT / name
        if file.exists(): archive.write(file, name)
    for file in (ROOT / "src/main/resources/static").rglob("*"):
        if file.is_file(): archive.write(file, "minimal-verification-ui/" + file.relative_to(ROOT / "src/main/resources/static").as_posix())

manifest = []
for file in (source_zip, handoff_zip):
    manifest.append(dict(file=file.name, bytes=file.stat().st_size, sha256=hashlib.sha256(file.read_bytes()).hexdigest()))
(destination / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
print("Source files scanned:", len(allowed), "; no real local secrets detected")
for item in manifest: print(item["file"], item["bytes"], "bytes")
