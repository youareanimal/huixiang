"""Prepare the public release feed and assets after build-windows.ps1 and source packaging."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='https://huixiang-updates.pages.dev')
parser.add_argument('--notes', default='新增在线更新：检查版本、下载进度、匹配手机架构及系统安装。\n保留账号、书签与原有设置。')
args = parser.parse_args()
base = args.base_url.rstrip('/')
url = urlsplit(base)
assert url.scheme == 'https' and url.hostname and not url.username and not url.query and not url.fragment
gradle = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
version = re.search(r'versionName = "([^"]+)"', gradle).group(1)
code = int(re.search(r'versionCode = (\d+)', gradle).group(1))
stage = root / 'dist/update-site'
# This directory is only the upload bundle. The remote mirrors retain old immutable releases.
if stage.is_symlink():
    raise RuntimeError('Refusing to replace a symlinked update staging directory')
if stage.exists():
    shutil.rmtree(stage)
stage.mkdir(parents=True)
artifacts = {}
abi = 'arm64-v8a'
name = f'HuiXiang-{version}-preview-{abi}.apk'
    apk = root / 'dist' / name
    size = apk.stat().st_size
    assert 0 < size < 25 * 1024 * 1024, 'Asset exceeds hosting size limit'
    artifacts[abi] = {'url': base + '/' + name, 'bytes': size, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}
    shutil.copy2(apk, stage / name)
source = root / f'dist/HuiXiang-{version}-source.zip'
assert source.is_file()
shutil.copy2(source, stage / source.name)
feed = {'schemaVersion': 1, 'packageName': 'app.shengqiao.ts.debug', 'versionCode': code,
        'versionName': version + '-preview', 'minSdk': 29,
        'notes': args.notes,
        'sourceUrl': base + '/' + source.name, 'artifacts': artifacts}
(stage / 'latest.json').write_text(json.dumps(feed, ensure_ascii=False, indent=2), encoding='utf-8')
(stage / '_headers').write_text('/latest.json\n  Cache-Control: no-store\n/*.apk\n  Content-Type: application/vnd.android.package-archive\n', encoding='utf-8')
(stage / 'index.html').write_text(f'<!doctype html><meta charset="utf-8"><title>回响更新</title>'
    f'<h1>回响 {version}</h1><p><a href="{Path(artifacts["arm64-v8a"]["url"]).name}">Android 安装包</a></p>'
    f'<p><a href="{source.name}">对应版本完整源代码 (GPL-3.0)</a></p>', encoding='utf-8')
print(json.dumps({'staged': str(stage), 'version': version, 'versionCode': code, 'assets': len(list(stage.iterdir()))}))
