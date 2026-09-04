#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
frontend_target="/opt/schedule-smartuser-20260811/frontend/dist"
stamp="$(date +%Y%m%d-%H%M%S)"

test -d "$frontend_target"
test -s "$bundle_dir/frontend.tar.gz"
cp -a "$frontend_target" "$frontend_target.backup-booking-range-$stamp"
find "$frontend_target" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
tar -xzf "$bundle_dir/frontend.tar.gz" -C "$frontend_target"

nginx -t
systemctl reload nginx
grep -Rqs "Refresh the Sheet tabs and import the week covering this date range" "$frontend_target/assets"
echo "DEPLOYMENT VERIFIED: booking date-range message is live."
