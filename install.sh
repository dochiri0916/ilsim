#!/usr/bin/env bash
# Sets up the `ai` command on this PC. Target projects need nothing: run `ai 작업 내용` inside any Gradle project.
#
#   ./install.sh               check tools, build both projects, install ~/.local/bin/ai and ask-remote
#   ./install.sh --agent-hint  also tell Claude Code and Codex (global instruction files) that ai runs the checks
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
# ai pulls and rebuilds on its own after this; record what was just built so the first task does not rebuild.
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  python3 "$ROOT/code-risk-analyzer/scripts/self_update.py" --mark-built || note "자동 업데이트 기록을 남기지 못했습니다"
fi

echo "4/4 ai, ask-remote 명령 설치: $BIN"
mkdir -p "$BIN"
# The ask command was removed; drop its old wrapper if it is ours.
if grep -qs "code-risk-analyzer/scripts/ask.py" "$BIN/ask"; then
  rm -f "$BIN/ask"
  note "예전 ask 명령을 지웠습니다 (ai로 바뀜)"
fi
cat > "$BIN/ask-remote" <<EOF
#!/bin/sh
exec python3 "$ROOT/code-risk-analyzer/scripts/remote_runner.py" "\$@"
EOF
chmod +x "$BIN/ask-remote"
cat > "$BIN/ai" <<EOF
#!/bin/sh
exec python3 "$ROOT/code-risk-analyzer/scripts/agent_pick.py" "\$@"
EOF
chmod +x "$BIN/ai"
# zsh expands ? * [ ] in words before ai runs ("ai 안녕?" fails with "no matches found"); noglob passes them as typed.
if [ "$(basename "${SHELL:-}")" = zsh ]; then
  ZSHRC="${ZDOTDIR:-$HOME}/.zshrc"
  if ! grep -qs "alias ai='noglob ai'" "$ZSHRC"; then
    printf '\n# ai-orchestration: ? * 를 그대로 ai에 넘김\nalias ai='"'"'noglob ai'"'"'\n' >> "$ZSHRC"
    ZSH_ALIAS_ADDED=true
  fi
fi
PATH_MISSING=false
case ":$PATH:" in
  *":$BIN:"*) ;;
  *) PATH_MISSING=true ;;
esac

if [ "$AGENT_HINT" = true ]; then
  # Replaces the earlier ask hint; ai runs the checks after each task, so agents need not repeat them.
  python3 - "$HOME/.claude/CLAUDE.md" "$HOME/.codex/AGENTS.md" <<'PY'
import re
import sys
from pathlib import Path

HINT = ('<!-- ai-orchestration: start -->\n'
        '- ai 명령으로 실행된 작업은 끝난 뒤 build-convention 검사와 위험 분석이 자동으로 실행된다. '
        '같은 검사(./gradlew check 등)를 작업 중에 따로 반복하지 않는다.\n'
        '<!-- ai-orchestration: end -->')
OLD = re.compile(r'\n?<!-- (code-risk ask|ai-orchestration): start -->.*?<!-- (code-risk ask|ai-orchestration): end -->\n?', re.S)
for name in sys.argv[1:]:
    path = Path(name)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = path.read_text(encoding='utf-8') if path.is_file() else ''
    path.write_text(OLD.sub('\n', text).rstrip('\n') + '\n\n' + HINT + '\n', encoding='utf-8')
    print('안내 갱신: ' + name)
PY
fi

echo "완료. Gradle 프로젝트 폴더에서: ai 작업 내용 → 한도가 유리한 Claude·Codex가 작업하고 검사 결과까지 보여 줍니다 (ai --why, ai --new)"
echo "자동 업데이트: ai가 작업을 받을 때마다(최대 10분에 한 번) 이 폴더를 git pull하고 바뀐 쪽만 다시 빌드합니다 (끄기: ASK_NO_UPDATE=1)"

if [ "${ZSH_ALIAS_ADDED:-false}" = true ]; then
  echo
  echo "zsh에서 ai 뒤의 ? * 를 그대로 쓰도록 ~/.zshrc에 alias를 넣었습니다. 지금 터미널에 적용하려면: source ~/.zshrc"
fi
if [ "$PATH_MISSING" = true ]; then
  case "$(basename "${SHELL:-}")" in
    zsh) RC="~/.zshrc" ;;
    bash) if [ "$(uname)" = Darwin ]; then RC="~/.bash_profile"; else RC="~/.bashrc"; fi ;;
    *) RC="~/.profile" ;;
  esac
  echo
  echo "ai 명령을 쓰려면 아래 두 줄을 그대로 복사해 실행하세요 ($BIN 을 PATH에 등록):"
  echo
  echo "  echo 'export PATH=\"$BIN:\$PATH\"' >> $RC"
  echo "  source $RC"
fi
