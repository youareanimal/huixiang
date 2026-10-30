#!/bin/sh
set -eu
cert=/etc/letsencrypt/live/huixiang-mirror
site=/opt/1panel/apps/openresty/openresty/www/sites/huixiang-mirror/ssl
install -d -m 700 "$site"
install -m 644 "$cert/fullchain.pem" "$site/fullchain.pem"
install -m 600 "$cert/privkey.pem" "$site/privkey.pem"
docker exec 1Panel-openresty-B656 nginx -t
docker exec 1Panel-openresty-B656 nginx -s reload
