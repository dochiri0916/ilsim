#!/usr/bin/env bash
# Sets up the `ask` command on this PC. Target projects need nothing: run `ask "..."` inside any Gradle project.
#
#   ./install.sh               check tools, build both projects, install ~/.local/bin/ask
#   ./install.sh --agent-hint  also tell Claude Code and Codex (global instruction files) that `ask` exists
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BIN="${ASK_BIN_DIR:-$HOME/.local/bin}"
AGENT_HINT=false
for arg in "$@"; do
  case "$arg" in
    --agent-hint) AGENT_HINT=true ;;
    -h|--help) sed -n '2,6p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

fail() { echo "설치 중단: $*" >&2; exit 1; }
note() { echo "참고: $*"; }

echo "1/4 도구 확인"
command -v java >/dev/null || fail "Java 21 이상이 필요합니다"
JAVA_MAJOR="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -ge 21 ] || fail "Java 21 이상이 필요합니다 (현재: ${JAVA_MAJOR:-알 수 없음})"
command -v python3 >/dev/null || fail "python3가 필요합니다"
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' || fail "python3가 너무 오래됐습니다 (3.9 이상, macOS 기본 python3면 됩니다)"
command -v git >/dev/null || fail "git이 필요합니다"
if ! command -v codex >/dev/null && ! command -v claude >/dev/null; then
  fail "codex 또는 claude CLI가 필요합니다 (설치 후 각 CLI에 요금제 계정으로 로그인)"
fi
command -v codex >/dev/null || note "codex CLI가 없어 codex 모델 경로는 실패합니다"
command -v claude >/dev/null || note "claude CLI가 없어 claude 모델 경로는 실패합니다"
if [ -z "${TYPESAFE_API_KEY:-}" ] && ! grep -qs "TYPESAFE_API_KEY" "${CODE_RISK_SECRETS:-$HOME/.secrets}" 2>/dev/null \
    && [ ! -f "${CODE_RISK_SECRETS:-$HOME/.secrets}/TYPESAFE_API_KEY" ]; then
  note "JEV 키가 없어 요청 판단은 키워드로만 합니다 (~/.secrets에 TYPESAFE_API_KEY=... 를 두면 JEV도 함께 판단)"
fi

echo "2/4 code-risk-analyzer 빌드"
(cd "$ROOT/code-risk-analyzer" && ./gradlew clean bootJar --no-daemon -q) || fail "code-risk-analyzer 빌드 실패"
[ -f "$ROOT/code-risk-analyzer/build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar" ] || fail "분석기 jar가 만들어지지 않았습니다"

echo "3/4 build-convention 빌드 후 로컬 Maven 저장소(~/.m2)에 설치"
(cd "$ROOT/build-convention" && ./gradlew clean publishToMavenLocal --no-daemon -q) || fail "build-convention 빌드 실패"
# ask pulls and rebuilds on its own after this; record what was just built so the first request does not rebuild.
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  python3 "$ROOT/code-risk-analyzer/scripts/self_update.py" --mark-built || note "자동 업데이트 기록을 남기지 못했습니다"
fi

echo "4/4 ask, ask-remote 명령 설치: $BIN"
mkdir -p "$BIN"
cat > "$BIN/ask" <<EOF
#!/bin/sh
exec python3 "$ROOT/code-risk-analyzer/scripts/ask.py" "\$@"
EOF
chmod +x "$BIN/ask"
cat > "$BIN/ask-remote" <<EOF
#!/bin/sh
exec python3 "$ROOT/code-risk-analyzer/scripts/remote_runner.py" "\$@"
EOF
chmod +x "$BIN/ask-remote"
case ":$PATH:" in
  *":$BIN:"*) ;;
  *) note "$BIN 이 PATH에 없습니다. ~/.zshrc에 다음 줄을 추가하세요: export PATH=\"$BIN:\$PATH\"" ;;
esac

if [ "$AGENT_HINT" = true ]; then
  HINT_START="<!-- code-risk ask: start -->"
  HINT="$HINT_START
- 코드 변경·위험 분석·검증 요청은 프로젝트 폴더에서 \`ask \"요청\"\`으로 실행할 수 있다. 계획을 확인한 뒤 \`ask --yes\`로 실행한다.
<!-- code-risk ask: end -->"
  for file in "$HOME/.claude/CLAUDE.md" "$HOME/.codex/AGENTS.md"; do
    mkdir -p "$(dirname "$file")"
    if grep -qs "$HINT_START" "$file"; then
      note "$file 에 이미 안내가 있습니다"
    else
      printf '\n%s\n' "$HINT" >> "$file"
      echo "안내 추가: $file"
    fi
  done
fi

echo "자동 업데이트: ask가 새 요청마다(최대 10분에 한 번) 이 폴더를 git pull하고 바뀐 쪽만 다시 빌드합니다 (끄기: --no-update 또는 ASK_NO_UPDATE=1)"
echo "완료. Gradle 프로젝트 폴더에서: ask \"회원 가입 기능 추가해줘\" → 초안 확인 → ask --yes"
