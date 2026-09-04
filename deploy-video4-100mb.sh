#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="/opt/schedule-smartuser-20260811"
backend_target="$app_dir/backend/target/schedule-smartuser-backend-1.0.0.jar"
frontend_target="$app_dir/frontend/dist"
backend_source="$bundle_dir/schedule-smartuser-backend-video4-100mb-20260819.jar"
frontend_source="$bundle_dir/schedule-smartuser-frontend-video4-100mb-20260819.tar.gz"
nginx_upload_conf="/etc/nginx/conf.d/schedule-smartuser-upload-size.conf"
stamp="$(date +%Y%m%d-%H%M%S)"

test -s "$backend_source"
test -s "$frontend_source"
test -s "$backend_target"
test -d "$frontend_target"

cp -p "$backend_target" "$backend_target.backup-video4-$stamp"
cp -a "$frontend_target" "$frontend_target.backup-video4-$stamp"

install -o root -g root -m 0644 "$backend_source" "$backend_target"
tar -xzf "$frontend_source" -C "$frontend_target"

if test -f "$nginx_upload_conf"; then
  cp -p "$nginx_upload_conf" "$nginx_upload_conf.backup-$stamp"
fi
printf 'client_max_body_size 110m;\n' > "$nginx_upload_conf"

systemctl restart schedule-smartuser-backend.service
sleep 12
systemctl is-active --quiet schedule-smartuser-backend.service
curl -fsS http://127.0.0.1:8088/api/health/status
echo

nginx -t
systemctl reload nginx

grep -Rqs "Inspection Videos" "$frontend_target/assets"
grep -Rqs "100 MB maximum" "$frontend_target/assets"
echo "DEPLOYMENT VERIFIED: four 100 MB video slots are live."
