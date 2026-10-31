#!/usr/bin/env python3
"""Forced SSH command: read-only check or bounded, validated release data only."""
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import signal
import subprocess
import sys
import tarfile
import tempfile
import time

HOME = Path('/var/lib/huixiang-deploy')
MIRROR = Path('/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/public')
MAX_BYTES = 250 * 1024**2
NAME = re.compile(r'(?:latest\.json|deployment\.json|HuiXiang-[A-Za-z0-9._-]+\.(?:apk|zip|txt))\Z')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def main():
    os.umask(0o077)
    signal.alarm(300)
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024**2, 256 * 1024**2))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_BYTES, MAX_BYTES))
    resource.setrlimit(resource.RLIMIT_CPU, (90, 90))
    command = os.environ.get('SSH_ORIGINAL_COMMAND', '')
    if command == 'huixiang-check':
        feed = json.loads((MIRROR / 'latest.json').read_text())
        versions = {name: hashlib.sha256((Path('/usr/local/lib/huixiang-mirror') / name).read_bytes()).hexdigest() for name in ['sync-release.py', 'deploy-gate.py']}
        print(json.dumps({'service': 'huixiang-mirror', 'protocol': 1, 'ready': os.access(MIRROR, os.W_OK) and os.access(HOME / 'state', os.W_OK), 'versionName': feed['versionName'], 'versionCode': feed['versionCode'], 'installedScripts': versions}))
        return
    match = re.fullmatch(r'huixiang-publish ([0-9a-f]{64}) ([1-9][0-9]{0,8})', command)
    require(match is not None, 'Only huixiang-check or verified huixiang-publish is permitted')
    expected, size = match.group(1), int(match.group(2))
    require(size <= MAX_BYTES, 'Upload too large')
    # Remove only abandoned private upload directories older than one day.
    # No release asset or historical manifest is ever deleted.
    import shutil
    incoming = HOME / 'incoming'
    for old in incoming.glob('upload-*'):
        if old.is_dir() and not old.is_symlink() and time.time() - old.stat().st_mtime > 86400:
            shutil.rmtree(old)
    with tempfile.TemporaryDirectory(prefix='upload-', dir=incoming) as temporary:
        folder = Path(temporary)
        bundle = folder / 'payload.tar'
        digest = hashlib.sha256()
        remaining = size
        with bundle.open('xb') as out:
            while remaining:
                chunk = sys.stdin.buffer.read(min(1024 * 1024, remaining))
                require(bool(chunk), 'Truncated upload')
                out.write(chunk)
                digest.update(chunk)
                remaining -= len(chunk)
            require(not sys.stdin.buffer.read(1), 'Trailing upload bytes')
        require(digest.hexdigest() == expected, 'Upload SHA-256 mismatch')
        stage = folder / 'stage'
        stage.mkdir()
        total = 0
        names = set()
        with tarfile.open(bundle, mode='r:') as archive:
            for item in archive:
                require(item.isfile() and not item.issym() and not item.islnk(), 'Only regular files accepted')
                require(NAME.fullmatch(item.name) and item.name not in names, 'Invalid or duplicate filename')
                require(0 < item.size <= 150 * 1024**2, 'Invalid file size')
                total += item.size
                names.add(item.name)
                require(total <= MAX_BYTES and len(names) <= 10, 'Payload bounds exceeded')
                with archive.extractfile(item) as source, (stage / item.name).open('xb') as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
        require({'latest.json', 'deployment.json'} <= names, 'Missing release metadata')
        subprocess.run(['/usr/bin/python3', '/usr/local/lib/huixiang-mirror/sync-release.py', '--stage', str(stage)], check=True, timeout=180, env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never include request content, credentials, or arbitrary tracebacks.
        print('Deployment refused: ' + str(error)[:200], file=sys.stderr)
        sys.exit(1)
