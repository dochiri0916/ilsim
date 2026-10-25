#!/usr/bin/env bash
pkill -f IlsimApplication || true
pkill -f "gradlew.*bootRun" || true
echo "stopped"
