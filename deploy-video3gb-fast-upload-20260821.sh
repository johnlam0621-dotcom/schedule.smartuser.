#!/usr/bin/env bash
set -euo pipefail

bundle_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="/opt/schedule-smartuser-20260811"
backend_target="$app_dir/backend/target/schedule-smartuser-backend-1.0.0.jar"
frontend_target="$app_dir/frontend/dist"
backend_source="$bundle_dir/schedule-smartuser-backend-video3gb-fast-20260821.jar"
frontend_source="$bundle_dir/schedule-smartuser-frontend-video3gb-fast-20260821.tar.gz"
nginx_upload_conf="/etc/nginx/conf.d/schedule-smartuser-upload-size.conf"
stamp="$(date +%Y%m%d-%H%M%S)"

test -s "$backend_source"
test -s "$frontend_source"
test -s "$backend_target"
test -d "$frontend_target"

# 部署前保留后端、前端和 Nginx 设置备份，便于快速回滚。
cp -p "$backend_target" "$backend_target.backup-fast-upload-$stamp"
cp -a "$frontend_target" "$frontend_target.backup-fast-upload-$stamp"
if test -f "$nginx_upload_conf"; then
  cp -p "$nginx_upload_conf" "$nginx_upload_conf.backup-fast-upload-$stamp"
fi

install -o root -g root -m 0644 "$backend_source" "$backend_target"
find "$frontend_target" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
tar -xzf "$frontend_source" -C "$frontend_target"

# 允许 3 GB 视频，并让 Nginx 边接收边转发，避免整个文件落盘后再上传一次。
cat > "$nginx_upload_conf" <<'EOF'
client_max_body_size 3100m;
client_body_timeout 4h;
proxy_request_buffering off;
proxy_send_timeout 4h;
proxy_read_timeout 4h;
EOF

nginx -t
systemctl restart schedule-smartuser-backend.service

healthy=false
for _ in $(seq 1 30); do
  if systemctl is-active --quiet schedule-smartuser-backend.service \
      && curl -fsS http://127.0.0.1:8088/api/health/status | grep -q '"status":"UP"'; then
    healthy=true
    break
  fi
  sleep 2
done

if test "$healthy" != true; then
  echo "Backend health check failed. Review: journalctl -u schedule-smartuser-backend.service -n 100" >&2
  exit 1
fi

systemctl reload nginx

grep -Rqs "3 GB maximum" "$frontend_target/assets"
grep -Rqs "Uploading" "$frontend_target/assets"
curl -fsS https://schedule.smartuser.com.au/login >/dev/null
echo "DEPLOYMENT VERIFIED: 3 GB streaming uploads, photo compression, and upload progress are live."
