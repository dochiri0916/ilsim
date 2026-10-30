#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="com.dochiri.ilsim.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/ilsim"
DOMAIN="gui/$(id -u)"

install() {
  mkdir -p "$LOG" "$(dirname "$PLIST")"
  cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$ROOT/scripts/serve.sh</string></array>
  <key>WorkingDirectory</key><string>$ROOT</string>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$LOG/server.log</string>
  <key>StandardErrorPath</key><string>$LOG/server.log</string>
</dict>
</plist>
EOF
  "$ROOT/scripts/dev-down.sh" >/dev/null 2>&1 || true
  launchctl bootstrap "$DOMAIN" "$PLIST"
  echo "자동 시작 등록: $PLIST (로그 $LOG/server.log)"
}

uninstall() {
  launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "자동 시작 해제"
}

status() {
  if [ -f "$PLIST" ]; then
    launchctl print "$DOMAIN/$LABEL" 2>/dev/null | grep -E '^\s*(state|pid|last exit code) =' || echo "등록됨, 실행 안 함"
  else
    echo "등록 안 됨"
  fi
}

case "${1:-status}" in
  install) install ;;
  uninstall) uninstall ;;
  status) status ;;
  *) echo "사용법: $0 install|uninstall|status"; exit 1 ;;
esac
