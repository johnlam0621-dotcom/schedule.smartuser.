#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="/opt/schedule-smartuser-20260811"
backend_target="$app_dir/backend/target/schedule-smartuser-backend-1.0.0.jar"
frontend_target="$app_dir/frontend/dist"
backend_source="$bundle_dir/schedule-smartuser-backend-required9-submit-20260819.jar"
frontend_source="$bundle_dir/schedule-smartuser-frontend-required9-submit-20260819.tar.gz"
stamp="$(date +%Y%m%d-%H%M%S)"

test -s "$backend_source"
test -s "$frontend_source"
test -s "$backend_target"
test -d "$frontend_target"

cp -p "$backend_target" "$backend_target.backup-required9-submit-$stamp"
cp -a "$frontend_target" "$frontend_target.backup-required9-submit-$stamp"

install -o root -g root -m 0644 "$backend_source" "$backend_target"
tar -xzf "$frontend_source" -C "$frontend_target"

systemctl restart schedule-smartuser-backend.service
sleep 12
systemctl is-active --quiet schedule-smartuser-backend.service
curl -fsS http://127.0.0.1:8088/api/health/status
echo

nginx -t
systemctl reload nginx

grep -Rqs "Submit inspection" "$frontend_target/assets"
echo "DEPLOYMENT VERIFIED: required 9-photo submission is live."
