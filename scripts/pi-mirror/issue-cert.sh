#!/bin/sh
set -eu
certbot certonly --manual --preferred-challenges dns \
  --manual-auth-hook '/usr/local/lib/huixiang-mirror/certbot-dns.py auth' \
  --manual-cleanup-hook '/usr/local/lib/huixiang-mirror/certbot-dns.py cleanup' \
  --deploy-hook /usr/local/lib/huixiang-mirror/renew-deploy.sh \
  --cert-name huixiang-mirror -d pi.animal26.site \
  --agree-tos --register-unsafely-without-email --non-interactive
