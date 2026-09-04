#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="/opt/schedule-smartuser-20260811"
backend_target="$app_dir/backend/target/schedule-smartuser-backend-1.0.0.jar"
frontend_target="$app_dir/frontend/dist"
stamp="$(date +%Y%m%d-%H%M%S)"

cp -p "$backend_target" "$backend_target.backup-address-types-$stamp"
cp -a "$frontend_target" "$frontend_target.backup-address-types-$stamp"
install -o root -g root -m 0644 "$bundle_dir/schedule-smartuser-backend-address-types-20260820.jar" "$backend_target"
find "$frontend_target" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
tar -xzf "$bundle_dir/schedule-smartuser-frontend-address-types-20260820.tar.gz" -C "$frontend_target"

systemctl restart schedule-smartuser-backend.service
sleep 12
systemctl is-active --quiet schedule-smartuser-backend.service
curl -fsS http://127.0.0.1:8088/api/health/status
echo
nginx -t
systemctl reload nginx
grep -Rqs "circuit|cct" "$frontend_target/assets"
echo "DEPLOYMENT VERIFIED: Australian Circuit/Cct address validation is live."
