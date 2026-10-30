#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

for _ in $(seq 1 120); do
  docker info >/dev/null 2>&1 && break
  sleep 5
done
docker compose up -d --wait

exec ./gradlew :ilsim-server:bootRun --console=plain
