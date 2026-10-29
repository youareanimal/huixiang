"""Collect GitHub-built unsigned APKs; never include a signing key in CI."""
from pathlib import Path
import hashlib
import json
import os
import re
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text()).group(1)
source = root / 'out/app/outputs/apk/compact'
target = root / 'dist/unsigned'
target.mkdir(parents=True, exist_ok=True)
outputs = []
for abi in ['arm64-v8a', 'armeabi-v7a', 'universal']:
    matches = list(source.glob(f'app-{abi}-compact-unsigned.apk'))
    if len(matches) != 1:
        raise RuntimeError(f'Expected one unsigned {abi} APK, got {len(matches)}')
    data = matches[0].read_bytes()
    with zipfile.ZipFile(matches[0]) as archive:
        assert archive.testzip() is None
        assert not any(name.upper().endswith(('.RSA', '.DSA', '.EC')) and name.startswith('META-INF/') for name in archive.namelist())
    name = f'HuiXiang-{version}-preview' + ('' if abi == 'universal' else f'-{abi}') + '-unsigned.apk'
    (target / name).write_bytes(data)
    outputs.append({'abi': abi, 'file': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
(target / 'build-info.json').write_text(json.dumps({'version': version, 'commit': os.environ['GITHUB_SHA'],
    'runId': os.environ['GITHUB_RUN_ID'], 'repository': os.environ['GITHUB_REPOSITORY'], 'signed': False,
    'artifacts': outputs}, indent=2), encoding='utf-8')
print(json.dumps({'version': version, 'unsignedAPKs': len(outputs)}))
