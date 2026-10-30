#!/usr/bin/env python3
"""Certbot hook for the PI's existing Cloudflare DDNS configuration. No secret is copied or logged."""
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request

CONFIG = Path('/opt/1panel/apps/ddns-go/ddns-go/data/.ddns_go_config.yaml')
STATE = Path('/run/huixiang-acme-txt.json')
ZONE = 'animal26.site'
DOMAIN = 'pi.animal26.site'

def scalar(value):
    value = value.strip()
    if value.startswith('"'):
        return json.loads(value)
    if value.startswith("'") and value.endswith("'"):
        return value[1:-1].replace("''", "'")
    return value

def credentials():
    # Read only the immediate scalar fields in the single existing Cloudflare DNS block.
    lines = CONFIG.read_text().splitlines()
    found = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r'(\s*)dns:\s*', line)
        if not match:
            continue
        indent = len(match[1]); fields = {}
        for child in lines[index + 1:]:
            if child.strip() and len(child) - len(child.lstrip()) <= indent:
                break
            m = re.fullmatch(r'\s{%d}([a-z]+):\s*(.*)' % (indent + 2), child)
            if m and m[1] in ('name', 'id', 'secret'):
                fields[m[1]] = scalar(m[2])
        if fields.get('name') == 'cloudflare':
            found.append(fields)
    if len(found) != 1 or not found[0].get('secret'):
        raise RuntimeError('Expected exactly one existing Cloudflare DNS credential')
    item = found[0]
    if item.get('id'):
        return {'X-Auth-Email': item['id'], 'X-Auth-Key': item['secret']}
    return {'Authorization': 'Bearer ' + item['secret']}

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None

def api(method, path, body=None):
    headers = credentials() | {'Content-Type': 'application/json', 'User-Agent': 'HuiXiang-PI-certificate'}
    req = urllib.request.Request('https://api.cloudflare.com/client/v4' + path,
        data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=25) as response:
            data = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError('DNS provider HTTP status ' + str(error.code)) from None
    if not data.get('success'):
        raise RuntimeError('DNS provider rejected request')
    return data['result']

def main():
    action = sys.argv[1]
    if action == 'cleanup':
        if STATE.exists():
            data = json.loads(STATE.read_text())
            api('DELETE', '/zones/' + data['zone'] + '/dns_records/' + data['record'])
            STATE.unlink()
        return
    zones = api('GET', '/zones?name=' + ZONE + '&status=active')
    if len(zones) != 1:
        raise RuntimeError('DNS zone unavailable')
    if action == 'check':
        print('Existing DNS credential can access the requested zone; no DNS changes made.')
        return
    assert action == 'auth' and os.environ.get('CERTBOT_DOMAIN') == DOMAIN
    value = os.environ['CERTBOT_VALIDATION']
    assert value and not STATE.exists()
    record = api('POST', '/zones/' + zones[0]['id'] + '/dns_records',
        {'type': 'TXT', 'name': '_acme-challenge.' + DOMAIN, 'content': value, 'ttl': 120})
    descriptor = os.open(STATE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as file:
        json.dump({'zone': zones[0]['id'], 'record': record['id']}, file)
    time.sleep(30)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Certificate DNS operation failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
