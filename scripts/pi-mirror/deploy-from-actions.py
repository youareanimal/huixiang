#!/usr/bin/env python3
"""On GitHub only: select trusted artifacts, verify bytes, use constrained SSH.

No artifact-supplied code is executed. No GitHub Release is created. APKs are
already built/signed by huixiang-build.yml; this program only validates data.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

REPO = 'youareanimal/huixiang'
WORKFLOW = '.github/workflows/huixiang-build.yml'
CERT = '2274ff15090cc84231beecacb6c744b38ac82c6357a91ea39a75ef35704dcf42'
API = 'https://api.github.com'
MAX = 250 * 1024**2


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        require(urllib.parse.urlsplit(newurl).scheme == 'https', 'Refuse insecure artifact redirect')
        result = super().redirect_request(req, fp, code, msg, headers, newurl)
        if urllib.parse.urlsplit(newurl).hostname != 'api.github.com':
            result.remove_header('Authorization')
        return result


OPENER = urllib.request.build_opener(Redirect())


def request(path):
    require(path.startswith('/repos/' + REPO + '/'), 'Unexpected API resource')
    return urllib.request.Request(API + path, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'HuiXiang-PI-Deploy'})


def api(path):
    with OPENER.open(request(path), timeout=45) as response:
        data = response.read(8 * 1024**2 + 1)
    require(len(data) <= 8 * 1024**2, 'API response too large')
    return json.loads(data)


def trusted_run(run):
    return (run.get('status') == 'completed' and run.get('conclusion') == 'success'
            and run.get('event') in {'push', 'workflow_dispatch'}
            and run.get('head_branch') == 'main'
            and run.get('path') == WORKFLOW
            and run.get('repository', {}).get('full_name') == REPO
            and run.get('head_repository', {}).get('full_name') == REPO
            and re.fullmatch(r'[0-9a-f]{40}', run.get('head_sha', '')))


def select_run(run_id):
    if run_id:
        require(re.fullmatch(r'[1-9][0-9]{0,19}', run_id), 'Invalid build run ID')
        run = api('/repos/' + REPO + '/actions/runs/' + run_id)
    else:
        runs = api('/repos/' + REPO + '/actions/workflows/huixiang-build.yml/runs?branch=main&status=success&per_page=100')['workflow_runs']
        run = next((r for r in runs if trusted_run(r)), None)
    require(run is not None and trusted_run(run), 'Only successful trusted-main builds can deploy')
    # Enforce ancestry against current main, including when an older ID is entered.
    comparison = api('/repos/' + REPO + '/compare/' + run['head_sha'] + '...main')
    require(comparison['status'] in {'ahead', 'identical'}, 'Build commit is not an ancestor of current main')
    return run


def download(artifact, path):
    digest = artifact.get('digest', '')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'GitHub artifact SHA-256 digest required')
    total = 0
    with OPENER.open(request('/repos/' + REPO + '/actions/artifacts/' + str(artifact['id']) + '/zip'), timeout=90) as response, path.open('xb') as out:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            require(total <= MAX, 'Artifact download exceeds bound')
            out.write(chunk)
    require(sha(path) == digest.split(':')[1], 'GitHub artifact archive digest mismatch')


def unpack(path, folder):
    total, names = 0, set()
    with zipfile.ZipFile(path) as archive:
        items = archive.infolist()
        require(len(items) <= 40, 'Too many artifact members')
        for item in items:
            name = PurePosixPath(item.filename)
            require(not name.is_absolute() and '..' not in name.parts and '\\' not in item.filename, 'Unsafe ZIP filename')
            require(len(name.parts) <= 2 and (len(name.parts) == 1 or name.parts[0] == 'signed'), 'Unexpected artifact directory')
            if item.is_dir():
                continue
            require((item.external_attr >> 16) & 0o170000 in {0, 0o100000}, 'ZIP links/devices forbidden')
            require(item.filename not in names, 'Duplicate ZIP filename')
            names.add(item.filename)
            total += item.file_size
            require(0 < item.file_size <= 150 * 1024**2 and total <= MAX, 'ZIP size bound exceeded')
            require(re.fullmatch(r'(?:signed/)?(?:HuiXiang-[A-Za-z0-9._-]+\.(?:apk|zip|txt)|build-info\.json|SHA256SUMS\.txt|latest\.json|index\.html|_headers)', item.filename), 'Unexpected artifact member')
            target = folder.joinpath(*name.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            # Read data only. Never preserve executable bits or execute content.
            with archive.open(item) as source, target.open('xb') as out:
                shutil.copyfileobj(source, out, 1024 * 1024)
            require(target.stat().st_size == item.file_size, 'Truncated ZIP member')


def prepare(run, root):
    artifacts = api('/repos/' + REPO + '/actions/runs/' + str(run['id']) + '/artifacts?per_page=100')['artifacts']
    candidates = [a for a in artifacts if not a['expired'] and re.fullmatch(r'HuiXiang-[A-Za-z0-9._-]+-online-update', a['name'])]
    require(len(candidates) == 1, 'Exactly one online-update artifact required')
    online = candidates[0]
    version = online['name'][len('HuiXiang-'):-len('-online-update')]
    signed = [a for a in artifacts if not a['expired'] and a['name'] == 'HuiXiang-' + version + '-signed']
    require(len(signed) == 1, 'Matching signed artifact required')
    signed = signed[0]
    folders = {}
    for label, artifact in [('online', online), ('signed', signed)]:
        linked = artifact.get('workflow_run', {})
        if linked:
            require(linked.get('id') == run['id'] and linked.get('head_sha') == run['head_sha'], 'Artifact provenance mismatch')
        path = root / (label + '.zip')
        download(artifact, path)
        folder = root / label
        folder.mkdir()
        unpack(path, folder)
        folders[label] = folder
    online_dir = folders['online']
    signed_dir = folders['signed'] / 'signed'
    info = json.loads((signed_dir / 'build-info.json').read_text())
    require(info.get('signed') is True and info.get('signingCertificateSHA256') == CERT, 'Original signing certificate record required')
    require(info.get('repository') == REPO and str(info.get('runId')) == str(run['id']) and info.get('commit') == run['head_sha'], 'Signed build record does not match selected run')
    require(info.get('version') == version, 'Artifact version mismatch')
    feed = json.loads((online_dir / 'latest.json').read_text())
    require(feed.get('schemaVersion') == 1 and feed.get('packageName') == 'app.shengqiao.ts.debug', 'Unexpected package')
    require(type(feed.get('versionCode')) is int and feed['versionCode'] > 0, 'Invalid version code')
    require(feed.get('versionName') == version + '-preview', 'Feed version does not match signed build')
    require(set(feed.get('artifacts', {})) == {'arm64-v8a', 'armeabi-v7a', 'universal'}, 'Incomplete architectures')
    records = info.get('artifacts', [])
    require(len(records) == 3 and {a['abi'] for a in records} == set(feed['artifacts']), 'Invalid signed APK records')
    stage = root / 'stage'
    stage.mkdir()
    hashes = {}
    for item in records:
        name = item['file']
        require(name == Path(name).name and re.fullmatch(r'HuiXiang-[A-Za-z0-9._-]+\.apk', name), 'Unsafe APK filename')
        source = signed_dir / name
        expected = item['sha256']
        require(re.fullmatch(r'[0-9a-f]{64}', expected) and sha(source) == expected, 'Signed APK hash mismatch')
        row = feed['artifacts'][item['abi']]
        require(Path(urllib.parse.urlsplit(row['url']).path).name == name, 'Feed filename mismatch')
        require(row['bytes'] == item['bytes'] == source.stat().st_size and row['sha256'] == expected, 'Feed size/hash mismatch')
        require(sha(online_dir / name) == expected, 'Online APK differs from signed artifact')
        shutil.copyfile(source, stage / name)
        hashes[name] = expected
    source_name = 'HuiXiang-' + version + '-source.zip'
    require(Path(urllib.parse.urlsplit(feed['sourceUrl']).path).name == source_name, 'Invalid source ZIP name')
    source_zip = folders['signed'] / source_name
    require(sha(source_zip) == sha(online_dir / source_name), 'Source archives differ across artifacts')
    shutil.copyfile(source_zip, stage / source_name)
    hashes[source_name] = sha(source_zip)
    checksums = (signed_dir / 'SHA256SUMS.txt').read_text()
    parsed = {}
    for line in checksums.splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  (HuiXiang-[A-Za-z0-9._-]+\.apk)', line)
        require(match is not None and match.group(2) not in parsed, 'Invalid checksum record')
        parsed[match.group(2)] = match.group(1)
    require(parsed == {a['file']: a['sha256'] for a in records}, 'Checksum list does not match signed APKs')
    checksum_name = 'HuiXiang-' + version + '-SHA256SUMS.txt'
    require((online_dir / checksum_name).read_text() == checksums, 'Online checksum list mismatch')
    (stage / checksum_name).write_text(checksums)
    hashes[checksum_name] = sha(stage / checksum_name)
    shutil.copyfile(online_dir / 'latest.json', stage / 'latest.json')
    provenance = {'repository': REPO, 'workflow': WORKFLOW, 'runId': run['id'], 'commit': run['head_sha'],
                  'artifactDigests': {str(a['id']): a['digest'] for a in [online, signed]},
                  'signingCertificateSHA256': CERT, 'manifestSha256': sha(stage / 'latest.json'), 'assetHashes': hashes}
    (stage / 'deployment.json').write_text(json.dumps(provenance, indent=2))
    print(json.dumps({'verifiedBuildRun': run['id'], 'commit': run['head_sha'], 'version': feed['versionName'], 'versionCode': feed['versionCode'], 'verifiedAssets': len(hashes)}))
    return stage


def connect(stage, root, dry_run):
    host = os.environ['PI_SSH_HOST']
    port = os.environ['PI_SSH_PORT']
    user = os.environ['PI_SSH_USER']
    require(host == 'pi.animal26.site' and user == 'huixiang-deploy', 'Unexpected deployment destination')
    require(re.fullmatch(r'[0-9]{1,5}', port) and 1 <= int(port) <= 65535, 'Invalid SSH port')
    key, known = root / 'identity', root / 'known_hosts'
    key.write_text(os.environ.pop('PI_SSH_PRIVATE_KEY').strip() + '\n')
    known.write_text(os.environ.pop('PI_SSH_KNOWN_HOSTS').strip() + '\n')
    key.chmod(0o600)
    known.chmod(0o600)
    command = ['ssh', '-F', '/dev/null', '-T', '-p', port, '-l', user, '-i', str(key),
               '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
               '-o', 'HostKeyAlias=pi.animal26.site', '-o', 'GlobalKnownHostsFile=/dev/null',
               '-o', 'UserKnownHostsFile=' + str(known), '-o', 'CheckHostIP=no',
               '-o', 'ConnectTimeout=20', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', host]
    try:
        check = subprocess.run(command + ['huixiang-check'], check=True, stdin=subprocess.DEVNULL,
                               capture_output=True, text=True, timeout=50)
        status = json.loads(check.stdout)
        require(status.get('service') == 'huixiang-mirror' and status.get('protocol') == 1 and status.get('ready') is True,
                'Deployment gate is not ready')
        for name in ['sync-release.py', 'deploy-gate.py']:
            require(status.get('installedScripts', {}).get(name) == sha(Path(__file__).with_name(name)),
                    'Installed PI script differs from reviewed deployment code: ' + name)
        print(json.dumps({'sshCheck': status}))
        if dry_run:
            print('Read-only SSH dry-run completed; no release files or manifest were changed.')
            return
        bundle = root / 'payload.tar'
        with tarfile.open(bundle, 'w', format=tarfile.USTAR_FORMAT) as archive:
            for item in sorted(stage.iterdir()):
                archive.add(item, arcname=item.name, recursive=False)
        require(bundle.stat().st_size <= MAX, 'Payload too large')
        remote = 'huixiang-publish ' + sha(bundle) + ' ' + str(bundle.stat().st_size)
        with bundle.open('rb') as source:
            subprocess.run(command + [remote], check=True, stdin=source, timeout=330)
    finally:
        key.unlink(missing_ok=True)
        known.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', default='')
    parser.add_argument('--dry-run', choices=['true', 'false'], default='true')
    args = parser.parse_args()
    require(os.environ.get('GITHUB_REPOSITORY') == REPO and os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Dispatch must run on trusted main')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Manual dispatch required')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='huixiang-deploy-', dir=os.environ.get('RUNNER_TEMP')) as folder:
        root = Path(folder)
        run = select_run(args.run_id)
        stage = prepare(run, root)
        connect(stage, root, args.dry_run == 'true')


if __name__ == '__main__':
    main()
