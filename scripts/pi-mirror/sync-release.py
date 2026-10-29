#!/usr/bin/env python3
"""Publish only public release assets, replacing the manifest after immutable assets are ready."""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--stage', type=Path, required=True)
parser.add_argument('--base-url', default='https://pi.animal26.site:8443/huixiang')
parser.add_argument('--dest', type=Path, default=Path('/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/public'))
args = parser.parse_args()
stage = args.stage.resolve(strict=True)
dest = args.dest
base = args.base_url.rstrip('/')
url = urlsplit(base)
assert url.scheme == 'https' and url.hostname == 'pi.animal26.site' and not url.username and not url.query and not url.fragment
assert url.path == '/huixiang'
feed = json.loads((stage / 'latest.json').read_text())
assert feed['schemaVersion'] == 1 and feed['packageName'] == 'app.shengqiao.ts.debug'
assert isinstance(feed['versionCode'], int) and feed['versionCode'] > 0
assert set(feed['artifacts']) == {'arm64-v8a', 'armeabi-v7a', 'universal'}
pattern = re.compile(r'HuiXiang-[A-Za-z0-9._-]+\.(?:apk|zip|txt)\Z')

def filename(value):
    name = Path(urlsplit(value).path).name
    assert pattern.fullmatch(name)
    item = stage / name
    assert item.is_file() and not item.is_symlink()
    return name

for row in feed['artifacts'].values():
    name = filename(row['url']); data = (stage / name).read_bytes()
    assert len(data) == row['bytes'] and hashlib.sha256(data).hexdigest() == row['sha256']
    row['url'] = base + '/' + name
source = filename(feed['sourceUrl'])
feed['sourceUrl'] = base + '/' + source
dest.mkdir(parents=True, exist_ok=True)
assert not dest.is_symlink()
if (dest / 'latest.json').exists():
    previous = json.loads((dest / 'latest.json').read_text())
    assert previous['versionCode'] <= feed['versionCode'], 'Refuse to roll the mirror back'

copied = 0
for item in stage.iterdir():
    if not item.is_file() or item.is_symlink() or not pattern.fullmatch(item.name):
        continue
    target = dest / item.name
    if target.exists():
        assert not target.is_symlink() and hashlib.sha256(target.read_bytes()).digest() == hashlib.sha256(item.read_bytes()).digest(), 'Preserve immutable release files'
        continue
    pending = dest / ('.' + item.name + '.upload')
    shutil.copyfile(item, pending)
    pending.chmod(0o644)
    os.replace(pending, target)
    copied += 1

def atomic(name, text):
    tmp = dest / ('.' + name + '.tmp')
    tmp.write_text(text, encoding='utf-8'); tmp.chmod(0o644)
    os.replace(tmp, dest / name)

version = html.escape(feed['versionName'])
arm = html.escape(Path(urlsplit(feed['artifacts']['arm64-v8a']['url']).path).name)
atomic('index.html', '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
    '<title>回响 PI 镜像</title><h1>回响 ' + version + '</h1><p>PI 直连下载镜像</p>'
    '<p><a href="' + arm + '">下载 arm64 安装包（华为畅享70等近年手机）</a></p>'
    '<p><a href="' + html.escape(source) + '">对应源代码 ZIP</a></p>')
atomic('latest.json', json.dumps(feed, ensure_ascii=False, indent=2))
print(json.dumps({'version': feed['versionName'], 'copiedPublicAssets': copied, 'feed': base + '/latest.json'}))
