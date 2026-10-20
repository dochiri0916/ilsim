#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${TMPDIR:-/tmp}/ilsim-dev"
mkdir -p "$LOG"

"$ROOT/scripts/dev-down.sh" >/dev/null 2>&1 || true

(cd "$ROOT" && nohup ./gradlew :ilsim-server:bootRun --console=plain >"$LOG/server.log" 2>&1 &)
(cd "$ROOT/ilsim-web" && nohup npm run dev >"$LOG/web.log" 2>&1 &)

for _ in $(seq 1 60); do
  curl -sf -o /dev/null localhost:8080/api/status && break
  sleep 1
done
curl -sf -o /dev/null localhost:8080/api/status || { echo "서버 기동 실패: $LOG/server.log"; exit 1; }
echo "server: http://localhost:8080  web: http://localhost:5173  logs: $LOG"

if [ "${1:-}" = "--tunnel" ]; then
  nohup cloudflared tunnel --url http://localhost:5173 >"$LOG/tunnel.log" 2>&1 &
  for _ in $(seq 1 30); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG/tunnel.log" | head -1 || true)
    [ -n "$URL" ] && break
    sleep 1
  done
  echo "external: ${URL:-터널 URL 확인 실패 ($LOG/tunnel.log)}"
  echo "주의: 인증이 없으니 테스트 후 scripts/dev-down.sh 로 종료"
fi
