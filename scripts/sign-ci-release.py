"""Sign collected CI APKs with the original upgrade key; never publish credentials."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

root = Path(__file__).resolve().parents[1]
source = root / 'dist/unsigned'
dest = root / 'dist/signed'
dest.mkdir(parents=True, exist_ok=True)
tools = Path('/opt/android-sdk/build-tools/37.0.0')
info = json.loads((source / 'build-info.json').read_text())
expected = '2274ff15090cc84231beecacb6c744b38ac82c6357a91ea39a75ef35704dcf42'

def run(args):
    result = subprocess.run([str(a) for a in args], capture_output=True)
    if result.returncode:
        raise RuntimeError('Signing tool failed; sensitive output suppressed')
    return result.stdout.decode(errors='replace')

for item in info['artifacts']:
    assert Path(item['file']).name == item['file']
    apk = source / item['file']
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == item['sha256']
    aligned = dest / (item['file'] + '.aligned')
    signed = dest / item['file'].replace('-unsigned.apk', '.apk')
    try:
        run([tools / 'zipalign', '-P', '16', '-f', '4', apk, aligned])
        run([tools / 'apksigner', 'sign', '--ks', '/keys/original.keystore',
             '--ks-key-alias', os.environ['HUIXIANG_KEY_ALIAS'],
             '--ks-pass', 'env:HUIXIANG_STORE_PASSWORD', '--key-pass', 'env:HUIXIANG_KEY_PASSWORD',
             '--v4-signing-enabled', 'false', '--out', signed, aligned])
        verification = run([tools / 'apksigner', 'verify', '--verbose', '--print-certs', signed])
        certs = set(re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', verification))
        assert certs == {expected}, 'Original signing certificate required'
        run([tools / 'zipalign', '-c', '-P', '16', '4', signed])
        metadata = run([tools / 'aapt2', 'dump', 'badging', signed])
        assert "package: name='app.shengqiao.ts.debug'" in metadata
        item.update(file=signed.name, bytes=signed.stat().st_size,
                    sha256=hashlib.sha256(signed.read_bytes()).hexdigest())
    finally:
        aligned.unlink(missing_ok=True)
info.update(signed=True, signingCertificateSHA256=expected)
(dest / 'build-info.json').write_text(json.dumps(info, indent=2))
(dest / 'SHA256SUMS.txt').write_text(''.join(f"{a['sha256']}  {a['file']}\n" for a in info['artifacts']))
print('Original certificate verified; signed APKs and checksums prepared.')
