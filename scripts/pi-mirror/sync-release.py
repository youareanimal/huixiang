#!/usr/bin/env python3
"""Verify immutable public assets, preserve history, atomically replace latest.json."""
import argparse
import fcntl
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlsplit

BASE = 'https://pi.animal26.site:8443/huixiang'
DEST = Path('/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/public')
STATE = Path('/var/lib/huixiang-deploy/state')
PATTERN = re.compile(r'HuiXiang-[A-Za-z0-9._-]+\.(?:apk|zip|txt)\Z')
DIGEST = re.compile(r'[0-9a-f]{64}\Z')
ABIS = {'arm64-v8a', 'armeabi-v7a', 'universal'}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def canonical(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def atomic(path, data, mode=0o644):
    fd, name = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            os.fchmod(f.fileno(), mode)
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
        folder = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(folder)
        finally:
            os.close(folder)
    finally:
        Path(name).unlink(missing_ok=True)


def prepare(stage):
    require(not stage.is_symlink(), 'Stage must not be a symlink')
    feed = json.loads((stage / 'latest.json').read_text())
    require(feed.get('schemaVersion') == 1 and feed.get('packageName') == 'app.shengqiao.ts.debug', 'Unexpected package/schema')
    require(type(feed.get('versionCode')) is int and feed['versionCode'] > 0, 'Invalid versionCode')
    version = feed.get('versionName', '')
    require(isinstance(version, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', version), 'Invalid versionName')
    require(set(feed.get('artifacts', {})) == ABIS, 'Exactly three APK architectures required')
    require((stage / 'deployment.json').is_file(), 'Verified build provenance required')
    proof = json.loads((stage / 'deployment.json').read_text())
    require(proof.get('repository') == 'youareanimal/huixiang' and proof.get('workflow') == '.github/workflows/huixiang-build.yml', 'Wrong build provenance')
    require(re.fullmatch(r'[0-9a-f]{40}', proof.get('commit', '')) and type(proof.get('runId')) is int, 'Invalid provenance identifiers')
    expected = proof.get('assetHashes', {})
    require(isinstance(expected, dict) and 4 <= len(expected) <= 6, 'Invalid verified asset list')
    names = set()
    for name, digest in expected.items():
        require(PATTERN.fullmatch(name) and isinstance(digest, str) and DIGEST.fullmatch(digest), 'Invalid asset hash')
        item = stage / name
        require(item.is_file() and not item.is_symlink(), 'Missing asset or symlink')
        require(0 < item.stat().st_size <= 150 * 1024**2 and sha(item) == digest, 'Asset digest mismatch')
        names.add(name)
    require(sha(stage / 'latest.json') == proof.get('manifestSha256'), 'Manifest digest mismatch')
    apk_names = set()
    for abi, row in feed['artifacts'].items():
        name = Path(urlsplit(row.get('url', '')).path).name
        require(name in names and name.endswith('.apk') and name not in apk_names, 'Invalid APK filename')
        require(type(row.get('bytes')) is int and row['bytes'] == (stage / name).stat().st_size, 'APK size mismatch')
        require(row.get('sha256') == expected[name], 'APK feed hash mismatch')
        apk_names.add(name)
        row['url'] = BASE + '/' + name
    source = Path(urlsplit(feed.get('sourceUrl', '')).path).name
    require(source in names and source.endswith('-source.zip'), 'Verified source ZIP required')
    require({n for n in names if n.endswith('.apk')} == apk_names, 'Unexpected APK in asset list')
    feed['sourceUrl'] = BASE + '/' + source
    actual = {p.name for p in stage.iterdir()}
    require(actual == names | {'latest.json', 'deployment.json'}, 'Unexpected payload files')
    return feed, names, proof


def publish(stage, dest=DEST, state=STATE):
    feed, names, proof = prepare(stage)
    require(dest.is_dir() and not dest.is_symlink(), 'Invalid mirror directory')
    require(state.is_dir() and not state.is_symlink(), 'Invalid private state directory')
    with (state / 'publish.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        current = dest / 'latest.json'
        require(current.is_file() and not current.is_symlink(), 'Existing manifest required')
        previous = json.loads(current.read_text())
        require(feed['versionCode'] >= previous['versionCode'], 'Refuse version rollback')
        if feed['versionCode'] == previous['versionCode']:
            require(canonical(feed) == canonical(previous), 'Refuse same versionCode with different manifest')
        # Compare both the current and every archived version before copying.
        history = state / 'history'
        history.mkdir(mode=0o750, exist_ok=True)
        old_manifests = [previous] + [json.loads(p.read_text()) for p in history.glob('*.json')]
        require(feed['versionCode'] >= max(old['versionCode'] for old in old_manifests), 'Refuse rollback below archived high-water version')
        for old in old_manifests:
            if old.get('versionName') == feed['versionName'] or old.get('versionCode') == feed['versionCode']:
                require(canonical(old) == canonical(feed), 'Refuse rewriting an existing version')
        for name in names:
            target = dest / name
            if target.exists() or target.is_symlink():
                require(target.is_file() and not target.is_symlink() and sha(target) == proof['assetHashes'][name], 'Preserve immutable existing assets')
        copied = 0
        for name in sorted(names):
            target = dest / name
            if target.exists():
                continue
            fd, temporary = tempfile.mkstemp(prefix='.' + name + '.', dir=dest)
            try:
                with os.fdopen(fd, 'wb') as out, (stage / name).open('rb') as source:
                    os.fchmod(out.fileno(), 0o644)
                    shutil.copyfileobj(source, out, 1024 * 1024)
                    out.flush()
                    os.fsync(out.fileno())
                # link() never overwrites an existing filename, even across a race.
                try:
                    os.link(temporary, target)
                    copied += 1
                except FileExistsError:
                    require(not target.is_symlink() and sha(target) == proof['assetHashes'][name], 'Concurrent immutable asset conflict')
            finally:
                Path(temporary).unlink(missing_ok=True)
        for manifest in [previous, feed]:
            history_name = str(manifest['versionCode']) + '-' + hashlib.sha256(canonical(manifest)).hexdigest() + '.json'
            path = history / history_name
            if not path.exists():
                atomic(path, canonical(manifest), 0o640)
        # Persist the high-water manifest before replacing the public pointer;
        # a retry of the same immutable release remains safe after a crash.
        atomic(state / 'last-deployment.json', canonical(proof), 0o640)
        arm = html.escape(Path(urlsplit(feed['artifacts']['arm64-v8a']['url']).path).name, quote=True)
        source = html.escape(Path(urlsplit(feed['sourceUrl']).path).name, quote=True)
        index = ('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                 '<title>回响 PI 镜像</title><h1>回响 ' + html.escape(feed['versionName']) + '</h1><p>PI 直连下载镜像</p>'
                 '<p><a href="' + arm + '">下载 arm64 安装包</a></p><p><a href="' + source + '">对应源代码 ZIP</a></p>')
        atomic(dest / 'index.html', index.encode())
        atomic(current, json.dumps(feed, ensure_ascii=False, indent=2).encode())
        print(json.dumps({'version': feed['versionName'], 'versionCode': feed['versionCode'], 'copiedPublicAssets': copied, 'runId': proof['runId'], 'feed': BASE + '/latest.json'}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', required=True, type=Path)
    parser.add_argument('--base-url', default=BASE)
    parser.add_argument('--dest', type=Path, default=DEST)
    parser.add_argument('--state', type=Path, default=STATE)
    args = parser.parse_args()
    try:
        require(args.base_url.rstrip('/') == BASE, 'PI mirror base URL is fixed')
        publish(args.stage, args.dest, args.state)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, 'Publish refused: ' + str(error) + '\n')
