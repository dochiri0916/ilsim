#!/usr/bin/env bash
pkill -f cloudflared || true
pkill -f "ilsim-web.*vite" || pkill -f vite || true
pkill -f IlsimApplication || true
pkill -f "gradlew.*bootRun" || true
echo "stopped"
