#!/usr/bin/env bash
LABEL="com.dochiri.ilsim.server"
launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
pkill -f IlsimApplication || true
pkill -f "gradlew.*bootRun" || true
echo "stopped"
