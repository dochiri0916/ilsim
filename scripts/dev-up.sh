#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${TMPDIR:-/tmp}/ilsim-dev"
mkdir -p "$LOG"

"$ROOT/scripts/dev-down.sh" >/dev/null 2>&1 || true

(cd "$ROOT" && nohup ./gradlew :ilsim-server:bootRun --console=plain >"$LOG/server.log" 2>&1 &)

for _ in $(seq 1 60); do
  curl -sf -o /dev/null localhost:8080/api/status && break
  sleep 1
done
curl -sf -o /dev/null localhost:8080/api/status || { echo "서버 기동 실패: $LOG/server.log"; exit 1; }
echo "server: http://localhost:8080  logs: $LOG"
