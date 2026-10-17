#!/usr/bin/env bash
# dev-up.sh 로 띄운 서버, 웹, 터널을 종료한다.
pkill -f cloudflared || true
pkill -f "ilsim-web.*vite" || pkill -f vite || true
pkill -f IlsimApplication || true
pkill -f "gradlew.*bootRun" || true
echo "stopped"
