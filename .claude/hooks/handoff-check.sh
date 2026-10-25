#!/usr/bin/env bash
[ -n "$ILSIM_RUNNER" ] && exit 0
input=$(cat)
case "$input" in *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) exit 0 ;; esac

repo="$(cd "$(dirname "$0")/../.." && pwd)"
handoff="$repo/docs/handoff.md"
[ -f "$handoff" ] || exit 0
grep -qE '"ilsim@ilsim" *: *true' "$repo/.claude/settings.local.json" 2>/dev/null && exit 0

newer=""
while IFS= read -r -d '' entry; do
  path="${entry:3}"
  [ "$path" = "docs/handoff.md" ] && continue
  f="$repo/$path"
  if [ -e "$f" ] && [ "$f" -nt "$handoff" ]; then newer="$path"; break; fi
done < <(git -C "$repo" status --porcelain -z --untracked-files=all 2>/dev/null)

[ -z "$newer" ] && exit 0
printf '{"decision":"block","reason":"작업 트리에 docs/handoff.md 보다 나중에 바뀐 파일이 있습니다(예: %s). 종료 전에 docs/handoff.md 를 이번 세션 내용으로 갱신하세요."}\n' "$newer"
