#!/bin/sh
set -eu
: "${HUIXIANG_CERT_DOMAIN:?Set the certificate domain in the local service environment}"
certbot certonly --manual --preferred-challenges dns \
  --manual-auth-hook '/usr/local/lib/huixiang-mirror/certbot-dns.py auth' \
  --manual-cleanup-hook '/usr/local/lib/huixiang-mirror/certbot-dns.py cleanup' \
  --deploy-hook /usr/local/lib/huixiang-mirror/renew-deploy.sh \
  --cert-name huixiang-mirror -d "$HUIXIANG_CERT_DOMAIN" \
  --agree-tos --register-unsafely-without-email --non-interactive
