"""按允许清单创建可交付 ZIP，排除运行目录、构建缓存和真实连接配置。"""
from pathlib import Path
import hashlib, json, zipfile, argparse
from datetime import date

parser = argparse.ArgumentParser(description='打包项目；冻结包使用独立名称，禁止覆盖。')
parser.add_argument('--freeze-date', type=date.fromisoformat, help='冻结日期，例如 2026-10-26')
options = parser.parse_args()

root = Path(__file__).resolve().parents[1]
destination = root.parent / '交付'
destination.mkdir(exist_ok=True)
archive = destination / '阅序-图书管理系统-传统版-v1.5.3.zip'
if options.freeze_date:
    archive = destination / f'阅序-图书管理系统-传统版-v1.5.3-frozen-{options.freeze_date.isoformat()}.zip'
files = []
for name in ('src', 'scripts', 'docs', 'tools', 'data', 'dist', 'vendor', '验收截图'):
    files.extend(p for p in (root / name).rglob('*') if p.is_file() and '__pycache__' not in p.parts and 'ui-references' not in p.parts)
files.extend((root / 'config').glob('*.example.properties'))
for name in ('README.md', 'AGENTS.md', 'pom.xml', '.gitignore', '启动项目.cmd', '启动演示.cmd', '停止项目.cmd', '初始化数据库.cmd'):
    files.append(root / name)
manifest = []
private_configs = [root / 'config' / name for name in ('db.properties', 'test-db.properties', 'admin.properties')]
secrets = []
for path in private_configs:
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if line.startswith('password=') and line[9:]:
                secrets.append(line[9:].encode())
with zipfile.ZipFile(archive, 'x' if options.freeze_date else 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(set(files)):
        relative = path.relative_to(root).as_posix()
        assert relative not in ('config/db.properties', 'config/test-db.properties', 'config/admin.properties')
        data = path.read_bytes()
        assert not any(secret in data for secret in secrets), 'Private credential found; packaging aborted.'
        z.writestr('library-traditional/' + relative, data)
        manifest.append({'path': relative, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    z.writestr('library-traditional/文件校验.json', json.dumps(manifest, ensure_ascii=False, indent=2))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    war = z.read('library-traditional/dist/library.war')
    import io
    with zipfile.ZipFile(io.BytesIO(war)) as w:
        assert not any(n.endswith(('db.properties', 'admin.properties', 'test-db.properties')) for n in w.namelist())
        for name in w.namelist():
            if not name.endswith('/'):
                assert not any(secret in w.read(name) for secret in secrets), 'Credential found inside WAR.'
digest = hashlib.sha256(archive.read_bytes()).hexdigest()
(destination / (archive.name + '.sha256')).write_text(digest + '  ' + archive.name + '\n', encoding='utf-8')
print('交付包：', archive)
print('文件数：', len(manifest), '体积：', round(archive.stat().st_size / 1024 / 1024, 2), 'MB')
print('ZIP 完整性、WAR 内容和本地密码排除检查通过。')
